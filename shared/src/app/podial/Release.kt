package app.podial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Vérification de mise à jour : interroge GitHub (dernière « release » publiée). Rien d'autre n'est envoyé. */
object Release {
    const val REPO = "Mickaelb06/podial"
    const val SITE = "https://github.com/$REPO"
    const val SUPPORT = "https://buymeacoffee.com/mickaelb06"

    private fun parts(v: String) = Regex("\\d+").findAll(v.substringBefore('-')).map { it.value.toInt() }.take(4).toList()

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Renvoie (version, adresse) si une version plus récente existe, null si on est à jour ou si rien n'est publié. */
    suspend fun check(current: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Podial/$current")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return@withContext null   // aucune release publiée
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name")
            if (tag.isNotEmpty() && isNewer(tag, current)) tag.trimStart('v') to o.optString("html_url", "$SITE/releases") else null
        } finally { c.disconnect() }
    }
}
