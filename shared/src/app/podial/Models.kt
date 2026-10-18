package app.podial

import org.json.JSONObject

/** Un podcast (feed) ou une radio (stream). */
data class Item(
    val kind: String,          // "podcast" ou "radio"
    val name: String,
    val artist: String,
    val art: String,
    val feed: String = "",     // podcast : adresse du flux RSS
    val stream: String = "",   // radio : adresse du flux audio
    val tags: String = "",
    val homepage: String = "",  // radio : site web de la station
    val quality: String = "",   // radio : « MP3 · 128 kbps »
) {
    val key: String get() = if (kind == "radio") stream else feed

    fun toJson() = JSONObject().put("kind", kind).put("name", name).put("artist", artist).put("art", art)
        .put("feed", feed).put("stream", stream).put("tags", tags).put("homepage", homepage).put("quality", quality)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            o.getString("kind"), o.getString("name"), o.optString("artist"), o.optString("art"),
            o.optString("feed"), o.optString("stream"), o.optString("tags"), o.optString("homepage"), o.optString("quality"),
        )
    }
}

/** [transcript] : adresse du texte publié par le podcast (balise podcast:transcript), [transcriptType] : son type MIME. */
data class Episode(val title: String, val url: String, val date: String, val duration: String, val art: String = "",
                   val transcript: String = "", val transcriptType: String = "")

/** Épisode téléchargé, lisible hors ligne. */
data class Download(val episode: Episode, val podcast: Item, val file: String, val size: Long) {
    fun toJson() = JSONObject().put("title", episode.title).put("url", episode.url).put("date", episode.date)
        .put("duration", episode.duration).put("podcast", podcast.toJson()).put("file", file).put("size", size)
        .put("art", episode.art).put("transcript", episode.transcript).put("transcriptType", episode.transcriptType)

    companion object {
        fun fromJson(o: JSONObject) = Download(
            Episode(o.getString("title"), o.getString("url"), o.optString("date"), o.optString("duration"), o.optString("art"),
                o.optString("transcript"), o.optString("transcriptType")),
            Item.fromJson(o.getJSONObject("podcast")), o.getString("file"), o.optLong("size"),
        )
    }
}
