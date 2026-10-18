package app.podial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Une émission de la grille du jour. start/end au format « 15h00 » ; endMinutes peut dépasser 24 h pour une émission qui passe minuit. */
data class Show(val start: String, val end: String, val title: String, val host: String) {
    val startMin get() = toMinutes(start)
    val endMin get() = toMinutes(end).let { if (it <= startMin) it + 24 * 60 else it }

    companion object {
        fun toMinutes(t: String): Int {
            val m = Regex("(\\d{1,2})h(\\d{2})").find(t) ?: return 0
            return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        }
    }
}

/**
 * Grille du jour de certaines radios. Il n'existe pas d'annuaire commun des programmes : chaque station a sa source.
 * Pour l'instant : RTL (page publique « Programme du jour » de rtl.fr, une seule requête par jour et par station).
 */
object Programs {
    private val cache = HashMap<String, List<Show>>()

    /** Seule la radio principale « RTL » (pas RTL2, RTL 100% Hits…) a cette grille. */
    fun supports(item: Item) = item.kind == "radio" && Regex("(?i)^rtl( france)?$").matches(item.name.trim()) &&
        (item.homepage.isEmpty() || item.homepage.contains("rtl.fr", true))

    fun sourceName(item: Item) = if (supports(item)) "rtl.fr" else ""

    suspend fun today(item: Item): List<Show>? {
        if (!supports(item)) return null
        val paris = ZonedDateTime.now(ZoneId.of("Europe/Paris"))
        val day = paris.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        cache[day]?.let { return it }
        val shows = withContext(Dispatchers.IO) { parseRtl(fetch("https://www.rtl.fr/grille/$day")) }
        if (shows.isNotEmpty()) cache[day] = shows
        return shows
    }

    /** Minutes écoulées depuis minuit à Paris (les grilles sont à l'heure de Paris). */
    fun nowMinutes(): Int = ZonedDateTime.now(ZoneId.of("Europe/Paris")).let { it.hour * 60 + it.minute }

    /** Émission en cours : la grille de RTL commence à 4h30, donc avant 4h30 on est dans l'émission de la veille (qui finit à 4h30). */
    fun current(shows: List<Show>): Show? {
        val now = nowMinutes()
        return shows.firstOrNull { now in it.startMin until it.endMin } ?: shows.firstOrNull { now + 24 * 60 in it.startMin until it.endMin }
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Podial/1.0 (+https://github.com/Mickaelb06/podial)")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun text(html: String) = decode(html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim())

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }

    fun parseRtl(html: String): List<Show> {
        val cards = Regex("<div class=\"programme-card[ \"]").split(html).drop(1)
        val raw = cards.mapNotNull { c ->
            val title = Regex("programme-card__title[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL).find(c)?.groupValues?.get(1)?.let(::text)
                ?: return@mapNotNull null
            val host = Regex("programme-card__hosts[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL).find(c)?.groupValues?.get(1)?.let(::text) ?: ""
            val time = Regex("programme-card__time[^>]*>\\s*([^<]+)<").find(c)?.groupValues?.get(1)?.trim() ?: ""
            Triple(time, title, host)
        }
        // la carte « en direct » n'affiche pas son horaire : elle va de la fin de la précédente au début de la suivante
        return raw.mapIndexed { i, (time, title, host) ->
            val range = Regex("(\\d{1,2}h\\d{2})\\s*-\\s*(\\d{1,2}h\\d{2})").find(time)
            val start = range?.groupValues?.get(1) ?: raw.getOrNull(i - 1)?.first?.let { Regex("-\\s*(\\d{1,2}h\\d{2})").find(it)?.groupValues?.get(1) } ?: ""
            val end = range?.groupValues?.get(2) ?: raw.getOrNull(i + 1)?.first?.let { Regex("(\\d{1,2}h\\d{2})").find(it)?.groupValues?.get(1) } ?: ""
            Show(start, end, title, host)
        }.filter { it.start.isNotEmpty() && it.end.isNotEmpty() }
    }
}
