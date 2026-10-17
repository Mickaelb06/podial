package app.podial

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
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

    private fun parseFeed(input: java.io.InputStream): Pair<String, List<Episode>> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        var desc = ""
        val eps = mutableListOf<Episode>()
        var inItem = false
        var title = ""; var url = ""; var date = ""; var dur = ""; var art = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item" -> { inItem = true; title = ""; url = ""; date = ""; dur = ""; art = "" }
                    "title" -> if (inItem) title = p.nextText().trim()
                    "description" -> if (!inItem && desc.isEmpty()) desc = p.nextText().replace(Regex("<[^>]+>"), " ").trim()
                    "pubDate" -> if (inItem) date = p.nextText().trim().take(16)
                    "enclosure" -> if (inItem) url = p.getAttributeValue(null, "url") ?: ""
                    "duration" -> if (inItem) dur = formatDuration(p.nextText().trim())
                    "image" -> if (inItem && p.prefix == "itunes") art = p.getAttributeValue(null, "href") ?: ""
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
                if (url.isNotEmpty()) eps += Episode(title.ifEmpty { "—" }, url, date, dur, art)
                if (eps.size >= 200) break
            }
        }
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
            val seen = HashSet<String>()
            JSONArray(radioGet(q)).objects().mapNotNull { s ->
                val stream = s.optString("url_resolved").ifEmpty { s.optString("url") }
                val n = s.optString("name").trim()
                if (stream.isEmpty() || n.isEmpty() || !seen.add(stationKey(n))) null
                else Item("radio", n, listOf(s.optString("state"), s.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                    s.optString("favicon"), stream = stream, tags = s.optString("tags"))
            }.take(limit)
        }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
