package app.podial

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32

/** Bibliothèque locale (abonnements, radios favorites) et épisodes téléchargés. Tout reste sur le téléphone. */
object Store {
    lateinit var ctx: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs get() = ctx.getSharedPreferences("podial", Context.MODE_PRIVATE)
    private val _podcasts = MutableStateFlow<List<Item>>(emptyList())
    private val _radios = MutableStateFlow<List<Item>>(emptyList())
    private val _downloads = MutableStateFlow<List<Download>>(emptyList())
    val podcasts: StateFlow<List<Item>> = _podcasts
    val radios: StateFlow<List<Item>> = _radios
    val downloads: StateFlow<List<Download>> = _downloads
    /** Téléchargements en cours : adresse de l'épisode -> avancement (0..1). */
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = _active
    private val jobs = HashMap<String, Job>()

    // ----- épisodes écoutés et nouveautés -----
    private val _played = MutableStateFlow<Set<String>>(emptySet())
    val played: StateFlow<Set<String>> = _played
    /** Podcasts suivis : adresse du flux -> nombre de nouveaux épisodes. */
    private val _newCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val newCounts: StateFlow<Map<String, Int>> = _newCounts

    // ----- citations -----
    private val _quotes = MutableStateFlow<List<Quote>>(emptyList())
    val quotes: StateFlow<List<Quote>> = _quotes

    @Synchronized fun addQuote(q: Quote) {
        if (_quotes.value.any { it.id == q.id }) return
        _quotes.value = listOf(q) + _quotes.value
        saveQuotes()
    }
    @Synchronized fun removeQuote(id: String) { _quotes.value = _quotes.value.filter { it.id != id }; saveQuotes() }
    @Synchronized fun setNote(id: String, note: String) { _quotes.value = _quotes.value.map { if (it.id == id) it.copy(note = note) else it }; saveQuotes() }
    private fun saveQuotes() = prefs.edit().putString("quotes", JSONArray(_quotes.value.map { it.toJson() }).toString()).apply()

    /** Transcription d'un épisode : lue depuis le cache du téléphone, sinon téléchargée puis gardée pour l'écoute hors ligne. */
    fun loadTranscript(episodeUrl: String, url: String, type: String): List<Cue> {
        val dir = File(ctx.filesDir, "transcripts").apply { mkdirs() }
        val f = File(dir, "%08x.txt".format(CRC32().apply { update(episodeUrl.toByteArray()) }.value))
        val body = if (f.exists()) f.readText() else Transcripts.download(url).also { if (it.isNotBlank()) f.writeText(it) }
        return Transcripts.parse(body, type)
    }

    private fun feeds() = JSONObject(prefs.getString("feeds", "{}") ?: "{}")

    private fun refreshCounts() {
        val f = feeds()
        _newCounts.value = f.keys().asSequence().associateWith { f.getJSONObject(it).optInt("new") }.filterValues { it > 0 }
    }

    @Synchronized fun knownFor(feed: String): Set<String>? =
        feeds().optJSONObject(feed)?.optJSONArray("known")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }

    /** L'utilisateur a vu ces épisodes (page ouverte, abonnement) : plus de nouveautés pour ce flux. */
    @Synchronized fun markSeen(feed: String, urls: Collection<String>) {
        val known = (knownFor(feed) ?: emptySet()) + urls
        prefs.edit().putString("feeds", feeds().put(feed, JSONObject().put("known", JSONArray(known.toList().takeLast(600))).put("new", 0)).toString()).apply()
        refreshCounts()
    }

    /** Compare les épisodes d'un flux à ceux déjà vus ; renvoie le nombre de nouveaux. Premier passage : on mémorise sans rien signaler. */
    @Synchronized fun recordCheck(feed: String, eps: List<Episode>): Int {
        val known = knownFor(feed)
        if (known == null) { markSeen(feed, eps.map { it.url }); return 0 }
        val n = Updates.fresh(eps, known, _played.value).size
        val f = feeds()
        f.getJSONObject(feed).put("new", n)
        prefs.edit().putString("feeds", f.toString()).apply()
        refreshCounts()
        return n
    }

    @Synchronized fun setPlayed(url: String, flag: Boolean) {
        _played.value = if (flag) _played.value + url else _played.value - url
        prefs.edit().putString("played", JSONArray(_played.value.toList().takeLast(5000)).toString()).apply()
    }

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        _podcasts.value = readItems("podcasts")
        _radios.value = readItems("radios")
        _played.value = runCatching { JSONArray(prefs.getString("played", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
        refreshCounts()
        _quotes.value = runCatching { JSONArray(prefs.getString("quotes", "[]")).let { a -> (0 until a.length()).map { Quote.fromJson(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        _downloads.value = runCatching {
            val a = JSONArray(prefs.getString("downloads", "[]"))
            (0 until a.length()).map { Download.fromJson(a.getJSONObject(it)) }.filter { File(it.file).exists() }
        }.getOrDefault(emptyList())
    }

    private fun readItems(k: String): List<Item> = runCatching {
        val a = JSONArray(prefs.getString(k, "[]")); (0 until a.length()).map { Item.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save() {
        prefs.edit()
            .putString("podcasts", JSONArray(_podcasts.value.map { it.toJson() }).toString())
            .putString("radios", JSONArray(_radios.value.map { it.toJson() }).toString())
            .putString("downloads", JSONArray(_downloads.value.map { it.toJson() }).toString())
            .apply()
    }

    fun isSaved(item: Item) = (if (item.kind == "radio") _radios else _podcasts).value.any { it.key == item.key }

    fun toggle(item: Item) {
        val flow = if (item.kind == "radio") _radios else _podcasts
        flow.value = if (isSaved(item)) flow.value.filter { it.key != item.key } else flow.value + item
        save()
        PlaybackService.libraryChanged()
    }

    fun downloaded(url: String) = _downloads.value.firstOrNull { it.episode.url == url }

    fun download(podcast: Item, ep: Episode) {
        if (jobs.containsKey(ep.url) || downloaded(ep.url) != null) return
        jobs[ep.url] = scope.launch {
            val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
            val name = "%08x-%s.%s".format(CRC32().apply { update(ep.url.toByteArray()) }.value,
                ep.title.replace(Regex("[^A-Za-z0-9]+"), "-").take(40).trim('-'),
                ep.url.substringBefore('?').substringAfterLast('.', "mp3").takeIf { it.length in 2..4 } ?: "mp3")
            val part = File(dir, "$name.part")
            try {
                setProgress(ep.url, 0f)
                val c = URL(ep.url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Podial-Android/0.1")
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            ensureActive()
                            out.write(buf, 0, n); done += n
                            if (total > 0) setProgress(ep.url, done.toFloat() / total)
                        }
                    }
                }
                val file = File(dir, name)
                part.renameTo(file)
                _downloads.value = _downloads.value + Download(ep, podcast, file.absolutePath, file.length())
                save()
            } catch (e: Exception) {
                part.delete()
            } finally {
                setProgress(ep.url, null); jobs.remove(ep.url)
            }
        }
    }

    fun cancel(url: String) { jobs[url]?.cancel() }

    fun removeDownload(url: String) {
        val d = downloaded(url) ?: return
        File(d.file).delete()
        _downloads.value = _downloads.value.filter { it.episode.url != url }
        save()
        PlaybackService.libraryChanged()
    }

    private fun setProgress(url: String, v: Float?) {
        _active.value = if (v == null) _active.value - url else _active.value + (url to v)
    }
}
