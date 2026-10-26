package app.podial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Accès réseau : iTunes (catalogue de podcasts), flux RSS, annuaire radio-browser.info. Aucune clé, aucun compte. */
object Api {
    private const val UA = "Podial-Android/0.1 (+https://github.com/podial/podial)"

    val APPLE_GENRES = linkedMapOf(
        1301 to "Arts", 1321 to "Business", 1303 to "Comedy", 1304 to "Education", 1483 to "Fiction",
        1511 to "Government", 1487 to "History", 1512 to "Health & Fitness", 1305 to "Kids & Family",
        1502 to "Leisure", 1310 to "Music", 1489 to "News", 1526 to "Daily News", 1527 to "Politics", 1490 to "Business News", 1528 to "Tech News", 1314 to "Religion & Spirituality", 1533 to "Science",
        1324 to "Society & Culture", 1545 to "Sports", 1318 to "Technology", 1488 to "True Crime", 1309 to "TV & Film",
    )

    fun country(): String = Locale.getDefault().country.ifEmpty { "US" }.uppercase()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, timeout: Int = 20000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", UA)
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /**
     * Apple n'indique plus l'adresse du flux RSS de certains podcasts (France Inter, Radio France…) : on les garde quand même,
     * avec l'identifiant provisoire « itunes:<id> » que fetchFeed() transforme en vraie adresse à l'ouverture du podcast.
     */
    internal const val PENDING = "itunes:"

    private fun podcast(o: JSONObject): Item? {
        val id = o.optLong("collectionId")
        val feed = o.optString("feedUrl").ifEmpty { if (id > 0 && o.optString("kind", "podcast") == "podcast") "$PENDING$id" else return null }
        return Item("podcast", o.optString("collectionName"), o.optString("artistName"),
            o.optString("artworkUrl600").ifEmpty { o.optString("artworkUrl100") }, feed = feed)
    }

    private val resolvedFeeds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val feedsFile get() = java.io.File(System.getProperty("java.io.tmpdir") ?: ".", "podial-feeds.json")

    /** Adresse du flux lue dans la page web d'Apple du podcast (champ « feedUrl » de ses données), gardée pour la prochaine fois. */
    internal fun resolveFeed(id: String): String {
        resolvedFeeds[id]?.let { return it }
        if (resolvedFeeds.isEmpty()) runCatching { JSONObject(feedsFile.readText()).let { o -> o.keys().forEach { resolvedFeeds.putIfAbsent(it, o.getString(it)) } } }
        resolvedFeeds[id]?.let { return it }
        val c = URL("https://podcasts.apple.com/${country().lowercase()}/podcast/id$id").openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Podial)")
        val html = try { c.inputStream.use { it.readNBytes(3_000_000) }.toString(Charsets.UTF_8) } finally { c.disconnect() }
        val url = feedUrlIn(html) ?: throw java.io.IOException("feed not found for $id")
        resolvedFeeds[id] = url
        runCatching { feedsFile.writeText(JSONObject(resolvedFeeds as Map<*, *>).toString()) }
        return url
    }

    internal fun feedUrlIn(html: String): String? {
        val key = "\"feedUrl\":\""
        val i = html.indexOf(key).takeIf { it >= 0 } ?: return null
        val j = html.indexOf('"', i + key.length).takeIf { it > 0 } ?: return null
        return html.substring(i + key.length, j).replace("\\/", "/").replace("\\u0026", "&").takeIf { it.startsWith("http") }
    }

    suspend fun searchPodcasts(term: String): List<Item> = withContext(Dispatchers.IO) {
        val r = JSONObject(get("https://itunes.apple.com/search?media=podcast&limit=40&country=${country()}&term=${enc(term)}"))
        r.getJSONArray("results").objects().mapNotNull(::podcast)
    }

    /** Classement du pays, éventuellement par genre. Le flux du classement ne donne pas l'adresse RSS : on la demande ensuite. */
    suspend fun topPodcasts(genre: Int?, limit: Int = 40): List<Item> = withContext(Dispatchers.IO) {
        val cc = country().lowercase()
        val n = if (genre == null) limit else maxOf(limit, 100)   // un genre est plus étroit : on en montre davantage (les journaux des radios sont loin dans le classement)
        // service des classements iTunes (rapide, avec ou sans genre) ; pour le classement général, l'ancien service Apple en secours
        val ids = try {
            chartIds("https://itunes.apple.com/$cc/rss/toppodcasts/limit=$n${genre?.let { "/genre=$it" }.orEmpty()}/json")
        } catch (e: Exception) {
            if (genre != null) throw e
            JSONObject(get("https://rss.marketingtools.apple.com/api/v2/$cc/podcasts/top/$limit/podcasts.json"))
                .getJSONObject("feed").getJSONArray("results").objects().map { it.getString("id") }
        }
        // le pays est nécessaire : un podcast qui n'existe que dans le catalogue français est introuvable sans lui
        val found = JSONObject(get("https://itunes.apple.com/lookup?entity=podcast&country=${cc.uppercase()}&id=${ids.joinToString(",")}"))
            .getJSONArray("results").objects().mapNotNull { podcast(it)?.let { p -> it.optLong("collectionId").toString() to p } }.toMap()
        ids.mapNotNull { found[it] }
    }

    /** Résultats iTunes d'une radio ou d'un éditeur : par identifiant d'éditeur (jusqu'à 200), sinon par son nom exact (jusqu'à 100). */
    private fun sourceResults(s: Source): List<JSONObject> {
        val cc = country().uppercase()
        return if (s.artistId > 0)
            JSONObject(get("https://itunes.apple.com/lookup?id=${s.artistId}&entity=podcast&limit=200&country=$cc"))
                .getJSONArray("results").objects().filter { it.optString("kind") == "podcast" }
        else {
            val key = stationKey(s.name)
            JSONObject(get("https://itunes.apple.com/search?media=podcast&attribute=artistTerm&limit=100&country=$cc&term=${enc(s.name)}"))
                .getJSONArray("results").objects().filter { stationKey(it.optString("artistName")) == key }
        }
    }

    /** Tous les podcasts d'une radio ou d'un éditeur. */
    suspend fun sourcePodcasts(s: Source): List<Item> = withContext(Dispatchers.IO) { sourceResults(s).mapNotNull(::podcast).distinctBy { it.feed } }

    /** Flashs d'info et journaux locaux : les podcasts « Actus du jour » des radios locales (ICI, Alouette, K6FM…). */
    suspend fun localFlashes(all: List<Source>, local: Local): List<Item> = withContext(Dispatchers.IO) {
        local.sources.mapNotNull { n -> all.firstOrNull { it.name == n } }.flatMap { s ->
            runCatching { sourceResults(s) }.getOrDefault(emptyList()).filter { o ->
                // « Actus du jour », ou un nom de flash / journal / infos (certaines radios rangent leurs flashs dans « Actualités »)
                o.optJSONArray("genreIds")?.let { g -> (0 until g.length()).any { g.optString(it) == local.genre.toString() } } == true ||
                    Regex("(?i)flash|journal|journaux|\\binfos?\\b").containsMatchIn(o.optString("collectionName"))
            }
        }.distinctBy { it.optLong("collectionId") }.mapNotNull(::podcast)
    }

    private fun chartIds(url: String): List<String> =
        JSONObject(get(url)).getJSONObject("feed").getJSONArray("entry").objects()
            .map { it.getJSONObject("id").getJSONObject("attributes").getString("im:id") }

    suspend fun fetchFeed(feed: String): Pair<String, List<Episode>> = withContext(Dispatchers.IO) {
        val real = if (feed.startsWith(PENDING)) resolveFeed(feed.removePrefix(PENDING)) else feed
        val c = URL(real).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", UA)
        c.inputStream.use { parseFeed(it) }
    }

    private class StopParsing : RuntimeException()

    internal fun parseFeed(input: java.io.InputStream): Pair<String, List<Episode>> {
        var desc = ""
        val eps = mutableListOf<Episode>()
        var inItem = false
        var title = ""; var url = ""; var date = ""; var dur = ""; var art = ""; var tr = ""; var trType = ""
        val text = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, q: String, a: Attributes) {
                text.setLength(0)
                when (q) {
                    "item" -> { inItem = true; title = ""; url = ""; date = ""; dur = ""; art = ""; tr = ""; trType = "" }
                    "podcast:transcript" -> if (inItem) {
                        val type = a.getValue("type") ?: ""
                        if (tr.isEmpty() || Transcripts.rank(type) < Transcripts.rank(trType)) { tr = a.getValue("url") ?: ""; trType = type }
                    }
                    "enclosure" -> if (inItem) url = a.getValue("url") ?: ""
                    "itunes:image" -> if (inItem) art = a.getValue("href") ?: ""
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
            override fun endElement(uri: String?, local: String?, q: String) {
                val t = text.toString().trim()
                when (q) {
                    "title" -> if (inItem) title = t
                    "description" -> if (!inItem && desc.isEmpty()) desc = t.replace(Regex("<[^>]+>"), " ").trim()
                    "pubDate" -> if (inItem) date = t.take(16)
                    "itunes:duration" -> if (inItem) dur = formatDuration(t)
                    "item" -> {
                        inItem = false
                        if (url.isNotEmpty()) eps += Episode(title.ifEmpty { "—" }, url, date, dur, art, tr, trType)
                        if (eps.size >= 200) throw StopParsing()
                    }
                }
            }
        }
        val factory = SAXParserFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        try { factory.newSAXParser().parse(input, handler) } catch (e: StopParsing) { /* assez d'épisodes */ }
        return desc to eps
    }

    private fun formatDuration(t: String): String {
        val secs = try {
            if (":" in t) t.split(":").fold(0L) { a, x -> a * 60 + x.toLong() } else t.toDouble().toLong()
        } catch (e: NumberFormatException) { return t }
        if (secs < 60) return ""
        val h = secs / 3600; val m = secs % 3600 / 60
        return if (h > 0) "%d h %02d".format(h, m) else "$m min"
    }

    // ---------- radios ----------
    private var servers: List<String>? = null

    private fun radioGet(path: String): String {
        val hosts = servers ?: runCatching {
            JSONArray(get("https://all.api.radio-browser.info/json/servers", 8000)).objects()
                .map { "https://" + it.getString("name") }.distinct().shuffled()
        }.getOrDefault(emptyList()).ifEmpty { listOf("https://de1.api.radio-browser.info") }.also { servers = it }
        var last: Exception? = null
        for (h in hosts) {
            try { return get(h + path) } catch (e: Exception) { last = e }
        }
        throw last ?: java.io.IOException("no radio server")
    }

    /** France Bleu s'appelle « ici » depuis 2025 : l'annuaire garde l'ancien nom, on affiche le nouveau. */
    fun niceName(name: String): String = if (name.startsWith("France Bleu ", ignoreCase = true)) "ici " + name.substring(12).trim() else name

    /** Clé de déduplication : « FIP », « FIP (HD) » et « Fip 128k » désignent la même radio. */
    fun stationKey(name: String): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
        s = s.replace(Regex("[\\[(].*?[\\])]"), " ").replace(Regex("\\b(hd|sd|aac|mp3|\\d+ ?k(bps)?|hq|lq)\\b"), " ")
        return s.replace(Regex("[^a-z0-9]+"), "")
    }

    /**
     * Radios d'un pays. [tags] : étiquettes d'un genre (une requête par étiquette, résultats réunis) ; [names] : morceaux de nom d'une marque
     * (réunis, et on ne garde que les stations dont le nom en contient un). Sinon [name] est la recherche libre.
     */
    suspend fun radios(country: String, name: String? = null, order: String = "clickcount", limit: Int = 60, withLogos: Boolean = true,
                       tags: List<String> = emptyList(), names: List<String> = emptyList(), world: Boolean = false): List<Item> =
        withContext(Dispatchers.IO) {
            val base = "/json/stations/search?hidebroken=true&order=$order&reverse=true&limit=${limit * 2}" + if (world) "" else "&countrycode=$country"
            val queries = when {
                tags.isNotEmpty() -> tags.map { "$base&tag=${enc(it)}&tagExact=true" }
                names.isNotEmpty() -> names.map { "$base&name=${enc(it.trim())}" }
                !name.isNullOrBlank() -> {
                    // « ici Provence » se cherche aussi sous son ancien nom « France Bleu Provence »
                    val rest = Regex("^ici\\s+(.+)", RegexOption.IGNORE_CASE).find(name.trim())?.groupValues?.get(1)
                    listOfNotNull("$base&name=${enc(name)}", rest?.let { "$base&name=${enc("France Bleu $it")}" })
                }
                else -> listOf(base)
            }
            val rows = coroutineScope { queries.map { q -> async { runCatching { JSONArray(radioGet(q)).objects() }.getOrNull() } }.awaitAll() }
            if (rows.all { it == null }) throw java.io.IOException("no radio server")
            var all = rows.filterNotNull().flatten().distinctBy { it.optString("stationuuid").ifEmpty { it.optString("name") + it.optString("url") } }
            if (queries.size > 1) all = all.sortedByDescending { if (order == "clickcount") it.optInt("clickcount") else 0 }
            if (names.isNotEmpty()) all = all.filter { s -> names.any { s.optString("name").contains(it.trim(), ignoreCase = true) } }
            // « FIP », « FIP (HD) » et « Fip 128k » sont la même radio : on garde la première, mais on lui prend le logo d'un doublon si elle n'en a pas
            val byKey = LinkedHashMap<String, Item>()
            for (s in all) {
                val stream = s.optString("url_resolved").ifEmpty { s.optString("url") }
                val n = niceName(s.optString("name").trim())
                if (n.isEmpty()) continue
                val key = stationKey(n)
                val icon = s.optString("favicon")
                val known = byKey[key]
                if (known != null) { if (known.art.isEmpty() && icon.isNotEmpty()) byKey[key] = known.copy(art = icon); continue }
                if (stream.isEmpty()) continue
                byKey[key] = Item("radio", n, listOf(s.optString("state"), s.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                    icon, stream = stream, tags = s.optString("tags"), homepage = s.optString("homepage"),
                    quality = listOf(s.optString("codec").uppercase().takeIf { it.isNotBlank() && it != "UNKNOWN" },
                        s.optInt("bitrate").takeIf { it > 0 }?.let { "$it kbps" }).filterNotNull().joinToString(" · "))
            }
            val extras = if (tags.isNotEmpty()) emptyList() else Extras.matching(country, names.firstOrNull() ?: name, strict = names.isNotEmpty()).filter { it.key !in byKey.values.map { r -> r.key } && stationKey(it.name) !in byKey }
            (byKey.values.take(limit) + extras).let { if (withLogos) Logos.fill(it) else it }
        }

    /** Webradios d'une marque ; pour « ici » (ex-France Bleu), la liste officielle par région passe devant, les autres stations de l'annuaire suivent. */
    suspend fun brandRadios(catalog: RadioCatalog?, b: RadioBrand): List<Item> {
        val net = runCatching { radios(b.country, names = b.queries, limit = 200) }
        if (!b.regional || catalog == null || catalog.ici.isEmpty()) return net.getOrThrow()
        val mine = Logos.fill(catalog.iciItems(b.logo))
        val keys = mine.map { stationKey(it.name) }.toSet()
        return mine + net.getOrDefault(emptyList()).filter { stationKey(it.name) !in keys }
    }

    /** Radios d'un genre : ses étiquettes et ses noms de stations, dans le pays choisi ou dans le monde entier pour un thème sans pays. */
    suspend fun genreRadios(g: RadioGenre, country: String): List<Item> {
        val byTag = if (g.tags.isNotEmpty()) radios(country, tags = g.tags, limit = 100, world = g.world) else emptyList()
        val byName = if (g.names.isNotEmpty()) runCatching { radios(country, names = g.names, limit = 100, world = g.world) }.getOrDefault(emptyList()) else emptyList()
        return (byTag + byName).distinctBy { stationKey(it.name) }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
