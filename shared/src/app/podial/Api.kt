package app.podial

import kotlinx.coroutines.Dispatchers
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
        1502 to "Leisure", 1310 to "Music", 1489 to "News", 1314 to "Religion & Spirituality", 1533 to "Science",
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

    private fun podcast(o: JSONObject): Item? {
        val feed = o.optString("feedUrl")
        if (feed.isEmpty()) return null
        return Item("podcast", o.optString("collectionName"), o.optString("artistName"),
            o.optString("artworkUrl600").ifEmpty { o.optString("artworkUrl100") }, feed = feed)
    }

    suspend fun searchPodcasts(term: String): List<Item> = withContext(Dispatchers.IO) {
        val r = JSONObject(get("https://itunes.apple.com/search?media=podcast&limit=40&country=${country()}&term=${enc(term)}"))
        r.getJSONArray("results").objects().mapNotNull(::podcast)
    }

    /** Classement du pays, éventuellement par genre. Le flux du classement ne donne pas l'adresse RSS : on la demande ensuite. */
    suspend fun topPodcasts(genre: Int?, limit: Int = 40): List<Item> = withContext(Dispatchers.IO) {
        val cc = country().lowercase()
        val ids = if (genre == null) {
            JSONObject(get("https://rss.marketingtools.apple.com/api/v2/$cc/podcasts/top/$limit/podcasts.json"))
                .getJSONObject("feed").getJSONArray("results").objects().map { it.getString("id") }
        } else {
            JSONObject(get("https://itunes.apple.com/$cc/rss/toppodcasts/limit=$limit/genre=$genre/json"))
                .getJSONObject("feed").getJSONArray("entry").objects()
                .map { it.getJSONObject("id").getJSONObject("attributes").getString("im:id") }
        }
        val found = JSONObject(get("https://itunes.apple.com/lookup?entity=podcast&id=${ids.joinToString(",")}"))
            .getJSONArray("results").objects().mapNotNull { podcast(it)?.let { p -> it.optLong("collectionId").toString() to p } }.toMap()
        ids.mapNotNull { found[it] }
    }

    suspend fun fetchFeed(feed: String): Pair<String, List<Episode>> = withContext(Dispatchers.IO) {
        val c = URL(feed).openConnection() as HttpURLConnection
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

    /** Clé de déduplication : « FIP », « FIP (HD) » et « Fip 128k » désignent la même radio. */
    fun stationKey(name: String): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
        s = s.replace(Regex("[\\[(].*?[\\])]"), " ").replace(Regex("\\b(hd|sd|aac|mp3|\\d+ ?k(bps)?|hq|lq)\\b"), " ")
        return s.replace(Regex("[^a-z0-9]+"), "")
    }

    suspend fun radios(country: String, name: String? = null, order: String = "clickcount", limit: Int = 60): List<Item> =
        withContext(Dispatchers.IO) {
            var q = "/json/stations/search?hidebroken=true&order=$order&reverse=true&limit=${limit * 2}&countrycode=$country"
            if (!name.isNullOrBlank()) q += "&name=${enc(name)}"
            // « FIP », « FIP (HD) » et « Fip 128k » sont la même radio : on garde la première, mais on lui prend le logo d'un doublon si elle n'en a pas
            val byKey = LinkedHashMap<String, Item>()
            for (s in JSONArray(radioGet(q)).objects()) {
                val stream = s.optString("url_resolved").ifEmpty { s.optString("url") }
                val n = s.optString("name").trim()
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
            Logos.fill(byKey.values.take(limit))
        }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
