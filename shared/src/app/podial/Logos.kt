package app.podial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Beaucoup de radios n'ont pas de logo dans l'annuaire. On le cherche alors sur leur propre site
 * (balise apple-touch-icon ou icon de la page d'accueil) : le téléphone ne contacte que la radio elle-même,
 * aucun service tiers. Le résultat est gardé en mémoire et dans le dossier temporaire de l'appli
 * (vidé par « Effacer le cache » sur Android).
 */
object Logos {
    private const val MAX_PER_LIST = 30
    private const val BUDGET_MS = 4500L
    private const val CHK = "chk:"   // préfixe des adresses de logo déjà vérifiées ("1" = ok, "0" = morte)
    private val found = ConcurrentHashMap<String, String>()   // adresse du site -> logo ("" = rien trouvé)
    @Volatile private var loaded = false

    /** Logos fournis (shared/data/radio_logos.json) : nom de radio normalisé -> logo, pour les stations dont l'annuaire n'a rien ou une adresse morte. */
    @Volatile private var table: Map<String, String> = emptyMap()

    fun loadTable(json: String) {
        table = runCatching { JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrDefault(emptyMap())
    }

    private val file get() = File(System.getProperty("java.io.tmpdir") ?: ".", "podial-logos.json")

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file.readText())
            o.keys().forEach { found.putIfAbsent(it, o.getString(it)) }
        }
    }

    private fun save() = runCatching { file.writeText(JSONObject(found as Map<*, *>).toString()) }

    /**
     * Rend à chaque radio un logo qui s'affiche : adresse Wikimedia remise à une taille acceptée, adresse morte écartée (404, 403…),
     * puis, pour celles qui restent sans logo, icône lue sur le site de la station.
     * On n'attend que quelques secondes : les résultats déjà obtenus sont gardés pour la prochaine fois.
     */
    suspend fun fill(items: List<Item>): List<Item> = withContext(Dispatchers.IO) {
        load()
        val fixed = items.map { r ->
            val given = if (r.kind == "radio") table[Api.stationKey(r.name)] else null
            when {
                given != null -> r.copy(art = given)
                r.art.isEmpty() -> r
                else -> r.copy(art = wikimedia(r.art))
            }
        }
        withTimeoutOrNull(BUDGET_MS) {
            coroutineScope {
                fixed.map { it.art }.filter { it.isNotEmpty() && !found.containsKey(CHK + it) }.distinct().take(MAX_PER_LIST * 3)
                    .map { u -> async { runCatching { alive(u) }.getOrNull()?.let { ok -> found[CHK + u] = if (ok) "1" else "0" } } }.awaitAll()
                val todo = fixed.filter { (it.art.isEmpty() || found[CHK + it.art] == "0") && it.homepage.startsWith("http") && !found.containsKey(it.homepage) }
                    .map { it.homepage }.distinct().take(MAX_PER_LIST)
                todo.map { h -> async { found[h] = runCatching { find(h) }.getOrNull().orEmpty() } }.awaitAll()
            }
        }
        save()
        fixed.map {
            if (it.art.isNotEmpty() && found[CHK + it.art] != "0") it
            else it.copy(art = found[it.homepage]?.takeIf { l -> l.isNotEmpty() && found[CHK + l] != "0" }.orEmpty())
        }
    }

    /** Wikimedia refuse les miniatures de taille quelconque (« Use thumbnail steps ») : on demande 330 px, une taille standard. */
    internal fun wikimedia(url: String): String =
        if ("upload.wikimedia.org" in url && "/thumb/" in url) url.replace(Regex("/((?:lang[a-z-]+-)?)\\d+px-"), "/\$1" + "330px-") else url

    /** Vrai si l'adresse renvoie une image, faux si elle est définitivement cassée (404, 403, pas une image), null si on ne peut pas savoir (limite de débit, panne du serveur). */
    internal fun alive(url: String): Boolean? {
        var u = url
        repeat(4) {
            val c = open(u)
            try {
                c.setRequestProperty("Range", "bytes=0-31")
                c.setRequestProperty("Accept", "image/*,*/*;q=0.5")
                val code = c.responseCode
                if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location") ?: return false).toString(); return@repeat }
                if (code == 429 || code >= 500) return null
                if (code !in 200..299) return false
                val head = c.inputStream.use { it.readNBytes(16) }
                return (c.contentType ?: "").startsWith("image/") || looksLikeImage(head)
            } finally { c.disconnect() }
        }
        return null
    }

    private fun looksLikeImage(b: ByteArray): Boolean {
        fun at(vararg v: Int) = b.size >= v.size && v.indices.all { (b[it].toInt() and 0xFF) == v[it] }
        return at(0x89, 0x50, 0x4E, 0x47) || at(0xFF, 0xD8, 0xFF) || at(0x47, 0x49, 0x46) || at(0x52, 0x49, 0x46, 0x46) || at(0, 0, 1, 0)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 3000; readTimeout = 3000; instanceFollowRedirects = false
        setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Podial)")
        setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")   // certains sites refusent l'en-tête Accept par défaut de Java (403)
        setRequestProperty("Accept-Language", "fr,en;q=0.8")
    }

    /** HttpURLConnection ne suit pas les redirections http -> https : on les suit nous-mêmes (5 au plus). */
    internal fun find(home: String): String? {
        var url = home
        repeat(5) {
            val c = open(url)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: return null).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val html = c.inputStream.use { it.readNBytes(300_000) }.toString(Charsets.UTF_8)
                return pick(html, url)
            } finally { c.disconnect() }
        }
        return null
    }

    /** Choisit l'icône déclarée par la page : apple-touch-icon (la plus grande) d'abord, puis icon. */
    internal fun pick(html: String, base: String): String? {
        val tags = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
        fun attr(tag: String, name: String) = Regex("\\b$name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
        val icons = tags.mapNotNull { t ->
            val rel = attr(t, "rel")?.lowercase() ?: return@mapNotNull null
            val href = attr(t, "href") ?: return@mapNotNull null
            if ("icon" !in rel || href.startsWith("data:") || href.length > 500) return@mapNotNull null
            val size = Regex("(\\d+)x\\d+").find(attr(t, "sizes") ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Triple("apple-touch-icon" in rel, size, href)
        }
        val best = icons.filter { it.first }.maxByOrNull { it.second } ?: icons.filter { !it.third.endsWith(".svg", true) }.maxByOrNull { it.second } ?: icons.firstOrNull()
        val url = runCatching { URL(URL(base), best?.third ?: return null).toString() }.getOrNull() ?: return null
        return url.takeIf { it.startsWith("http") && !it.endsWith(".svg", true) }
    }
}
