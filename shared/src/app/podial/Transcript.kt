package app.podial

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Une phrase ou un paragraphe de la transcription. [startMs] vaut -1 quand le texte n'est pas minuté. */
data class Cue(val startMs: Long, val text: String)

/** Citation enregistrée par l'utilisateur (phrase surlignée ou moment marqué). */
data class Quote(
    val id: String, val text: String, val startMs: Long,
    val episodeUrl: String, val episodeTitle: String, val podcast: String, val feed: String, val art: String,
    val note: String = "", val created: Long = System.currentTimeMillis(),
) {
    fun toJson() = JSONObject().put("id", id).put("text", text).put("start", startMs).put("episodeUrl", episodeUrl)
        .put("episodeTitle", episodeTitle).put("podcast", podcast).put("feed", feed).put("art", art).put("note", note).put("created", created)

    companion object {
        fun fromJson(o: JSONObject) = Quote(o.getString("id"), o.optString("text"), o.optLong("start", -1), o.getString("episodeUrl"),
            o.optString("episodeTitle"), o.optString("podcast"), o.optString("feed"), o.optString("art"), o.optString("note"), o.optLong("created"))

        fun idFor(episodeUrl: String, startMs: Long, text: String) = "%08x".format((episodeUrl + "|" + startMs + "|" + text).hashCode())

        /** Texte prêt à partager : « phrase » — Podcast, Épisode (12:34). */
        fun shareText(q: Quote): String {
            val at = if (q.startMs >= 0) " (" + Transcripts.clock(q.startMs) + ")" else ""
            val body = q.text.ifBlank { q.note }
            return "“$body”\n— ${q.podcast}, ${q.episodeTitle}$at"
        }
    }
}

/** Lecture des transcriptions publiées dans les flux (balise podcast:transcript) : JSON, WebVTT, SRT, texte ou HTML. */
object Transcripts {
    /** Plus petit = préféré : le JSON et les formats minutés passent avant le texte brut. */
    fun rank(type: String): Int = when {
        type.contains("json") -> 0
        type.contains("vtt") -> 1
        type.contains("subrip") || type.contains("srt") -> 2
        type.contains("html") -> 4
        type.isEmpty() -> 5
        else -> 3
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Télécharge le texte brut (5 Mo maximum). */
    fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Podial/0.5 (+https://github.com/Mickaelb06/podial)")
        c.instanceFollowRedirects = true
        return c.inputStream.use { it.readNBytes(5_000_000).toString(Charsets.UTF_8) }
    }

    fun parse(body: String, type: String = ""): List<Cue> {
        val t = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            t.startsWith("{") || t.startsWith("[") || type.contains("json") -> runCatching { parseJson(t) }.getOrDefault(emptyList())
            "-->" in t -> parseTimed(t)
            else -> parsePlain(t, type.contains("html") || t.startsWith("<"))
        }
    }

    private fun parseJson(t: String): List<Cue> {
        val arr: JSONArray = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("segments") ?: return emptyList()
        val out = ArrayList<Cue>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("body").trim()
            if (text.isNotEmpty()) out += Cue((o.optDouble("startTime", -0.001) * 1000).toLong(), text)
        }
        return out
    }

    private val timeLine = Regex("""((?:\d+:)?\d+:\d+(?:[.,]\d+)?)\s*-->""")

    private fun stamp(s: String): Long {
        val parts = s.replace(',', '.').split(":")
        var secs = 0.0
        for (p in parts) secs = secs * 60 + p.toDouble()
        return (secs * 1000).toLong()
    }

    private fun parseTimed(t: String): List<Cue> {
        val out = ArrayList<Cue>()
        for (block in t.replace("\r", "").split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val i = lines.indexOfFirst { "-->" in it }
            if (i < 0) continue
            val m = timeLine.find(lines[i]) ?: continue
            val text = lines.drop(i + 1).joinToString(" ") { it.replace(Regex("<[^>]+>"), "").trim() }.trim()
            if (text.isNotEmpty()) out += Cue(runCatching { stamp(m.groupValues[1]) }.getOrDefault(-1), text)
        }
        return out
    }

    private fun parsePlain(t: String, html: Boolean): List<Cue> {
        var s = t
        if (html) {
            s = s.replace(Regex("(?is)<(script|style).*?</\\1>"), "").replace(Regex("(?i)</p>|<br\\s*/?>|</div>|</h\\d>"), "\n\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        }
        return s.replace("\r", "").split(Regex("\n\\s*\n|\n")).map { it.trim() }.filter { it.isNotEmpty() }.map { Cue(-1, it) }
    }

    /** Index de la phrase en cours à la position donnée (-1 si le texte n'est pas minuté). */
    fun indexAt(cues: List<Cue>, positionMs: Long): Int {
        if (cues.isEmpty() || cues[0].startMs < 0) return -1
        var lo = 0; var hi = cues.size - 1; var ans = 0
        while (lo <= hi) { val mid = (lo + hi) / 2; if (cues[mid].startMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        return ans
    }
}
