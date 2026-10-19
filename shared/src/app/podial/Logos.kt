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
    private const val BUDGET_MS = 4000L
    private val found = ConcurrentHashMap<String, String>()   // adresse du site -> logo ("" = rien trouvé)
    @Volatile private var loaded = false

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

    /** Complète le logo des radios qui n'en ont pas. Rend la liste telle quelle si le réseau est lent : on n'attend que quelques secondes. */
    suspend fun fill(items: List<Item>): List<Item> = withContext(Dispatchers.IO) {
        load()
        val todo = items.filter { it.art.isEmpty() && it.homepage.startsWith("http") && !found.containsKey(it.homepage) }.take(MAX_PER_LIST)
        if (todo.isNotEmpty()) {
            withTimeoutOrNull(BUDGET_MS) {
                coroutineScope { todo.map { it.homepage }.distinct().map { h -> async { found[h] = runCatching { find(h) }.getOrNull().orEmpty() } }.awaitAll() }
            }
            save()
        }
        items.map { if (it.art.isEmpty()) found[it.homepage]?.takeIf { l -> l.isNotEmpty() }?.let { l -> it.copy(art = l) } ?: it else it }
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
