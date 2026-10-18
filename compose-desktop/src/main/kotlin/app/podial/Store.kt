package app.podial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32

/** Bibliothèque et téléchargements, dans le dossier de données de l'utilisateur (aucun compte, aucun serveur). */
object Store {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dir: File = run {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val base = when {
            "win" in os -> System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
            "mac" in os -> "$home/Library/Application Support"
            else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
        }
        File(base, "Podial-Desktop").apply { mkdirs() }
    }
    private val libFile = File(dir, "library.json")
    private val dlDir = File(dir, "downloads").apply { mkdirs() }

    private val _podcasts = MutableStateFlow<List<Item>>(emptyList())
    private val _radios = MutableStateFlow<List<Item>>(emptyList())
    private val _downloads = MutableStateFlow<List<Download>>(emptyList())
    val podcasts: StateFlow<List<Item>> = _podcasts
    val radios: StateFlow<List<Item>> = _radios
    val downloads: StateFlow<List<Download>> = _downloads

    // ----- citations -----
    private val _quotes = MutableStateFlow<List<Quote>>(emptyList())
    val quotes: StateFlow<List<Quote>> = _quotes

    @Synchronized fun addQuote(q: Quote) {
        if (_quotes.value.any { it.id == q.id }) return
        _quotes.value = listOf(q) + _quotes.value; save()
    }
    @Synchronized fun removeQuote(id: String) { _quotes.value = _quotes.value.filter { it.id != id }; save() }
    @Synchronized fun setNote(id: String, note: String) { _quotes.value = _quotes.value.map { if (it.id == id) it.copy(note = note) else it }; save() }

    /** Transcription d'un épisode : lue depuis le cache, sinon téléchargée puis gardée pour l'écoute hors ligne. */
    fun loadTranscript(episodeUrl: String, url: String, type: String): List<Cue> {
        val d = File(dir, "transcripts").apply { mkdirs() }
        val f = File(d, "%08x.txt".format(CRC32().apply { update(episodeUrl.toByteArray()) }.value))
        val body = if (f.exists()) f.readText() else Transcripts.download(url).also { if (it.isNotBlank()) f.writeText(it) }
        return Transcripts.parse(body, type)
    }
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = _active
    private val jobs = HashMap<String, Job>()

    // ----- épisodes écoutés et nouveautés -----
    private val _played = MutableStateFlow<Set<String>>(emptySet())
    val played: StateFlow<Set<String>> = _played
    private val _newCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val newCounts: StateFlow<Map<String, Int>> = _newCounts
    private val known = HashMap<String, Set<String>>()   // flux -> épisodes déjà vus
    private val fresh = HashMap<String, Int>()            // flux -> nombre de nouveaux

    private fun refreshCounts() { _newCounts.value = fresh.filterValues { it > 0 } }

    @Synchronized fun knownFor(feed: String): Set<String>? = known[feed]

    /** L'utilisateur a vu ces épisodes (page ouverte, abonnement) : plus de nouveautés pour ce flux. */
    @Synchronized fun markSeen(feed: String, urls: Collection<String>) {
        known[feed] = ((known[feed] ?: emptySet()) + urls).toList().takeLast(600).toSet()
        fresh[feed] = 0; refreshCounts(); save()
    }

    /** Compare les épisodes d'un flux à ceux déjà vus ; premier passage : on mémorise sans rien signaler. */
    @Synchronized fun recordCheck(feed: String, eps: List<Episode>): Int {
        val k = known[feed]
        if (k == null) { markSeen(feed, eps.map { it.url }); return 0 }
        val n = Updates.fresh(eps, k, _played.value).size
        fresh[feed] = n; refreshCounts(); save()
        return n
    }

    @Synchronized fun setPlayed(url: String, flag: Boolean) {
        _played.value = if (flag) _played.value + url else _played.value - url
        save()
    }

    /** Vérifie tous les podcasts suivis ; renvoie les noms de ceux qui ont du nouveau. */
    suspend fun checkAll(): List<String> {
        val names = mutableListOf<String>()
        for (p in _podcasts.value) {
            val eps = try { Api.fetchFeed(p.feed).second } catch (e: Exception) { continue }
            if (recordCheck(p.feed, eps) > 0) names += p.name
        }
        return names
    }

    init {
        runCatching {
            val o = org.json.JSONObject(libFile.readText())
            fun items(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { Item.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            _podcasts.value = items("podcasts"); _radios.value = items("radios")
            o.optJSONArray("quotes")?.let { a -> _quotes.value = (0 until a.length()).map { Quote.fromJson(a.getJSONObject(it)) } }
            o.optJSONArray("played")?.let { a -> _played.value = (0 until a.length()).map { a.getString(it) }.toSet() }
            o.optJSONObject("feeds")?.let { f ->
                for (k in f.keys()) {
                    val e = f.getJSONObject(k)
                    known[k] = e.getJSONArray("known").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    fresh[k] = e.optInt("new")
                }
            }
            refreshCounts()
            _downloads.value = o.optJSONArray("downloads")?.let { a ->
                (0 until a.length()).map { Download.fromJson(a.getJSONObject(it)) }.filter { File(it.file).exists() }
            } ?: emptyList()
        }
    }

    private fun save() {
        val o = org.json.JSONObject()
            .put("podcasts", JSONArray(_podcasts.value.map { it.toJson() }))
            .put("radios", JSONArray(_radios.value.map { it.toJson() }))
            .put("downloads", JSONArray(_downloads.value.map { it.toJson() }))
            .put("quotes", JSONArray(_quotes.value.map { it.toJson() }))
            .put("played", JSONArray(_played.value.toList().takeLast(5000)))
            .put("feeds", org.json.JSONObject().also { f ->
                for ((k, v) in known) f.put(k, org.json.JSONObject().put("known", JSONArray(v.toList())).put("new", fresh[k] ?: 0))
            })
        val tmp = File(dir, "library.json.tmp")
        tmp.writeText(o.toString(1))
        tmp.renameTo(libFile)
    }

    fun isSaved(item: Item) = (if (item.kind == "radio") _radios else _podcasts).value.any { it.key == item.key }

    fun toggle(item: Item) {
        val flow = if (item.kind == "radio") _radios else _podcasts
        flow.value = if (isSaved(item)) flow.value.filter { it.key != item.key } else flow.value + item
        save()
    }

    fun downloaded(url: String) = _downloads.value.firstOrNull { it.episode.url == url }

    fun download(podcast: Item, ep: Episode) {
        if (jobs.containsKey(ep.url) || downloaded(ep.url) != null) return
        jobs[ep.url] = scope.launch {
            val name = "%08x-%s.%s".format(CRC32().apply { update(ep.url.toByteArray()) }.value,
                ep.title.replace(Regex("[^A-Za-z0-9]+"), "-").take(40).trim('-'),
                ep.url.substringBefore('?').substringAfterLast('.', "mp3").takeIf { it.length in 2..4 } ?: "mp3")
            val part = File(dlDir, "$name.part")
            try {
                setProgress(ep.url, 0f)
                val c = URL(ep.url).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Podial-Desktop/1.0")
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
                val file = File(dlDir, name)
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
    }

    private fun setProgress(url: String, v: Float?) {
        _active.value = if (v == null) _active.value - url else _active.value + (url to v)
    }
}
