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
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = _active
    private val jobs = HashMap<String, Job>()

    init {
        runCatching {
            val o = org.json.JSONObject(libFile.readText())
            fun items(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { Item.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            _podcasts.value = items("podcasts"); _radios.value = items("radios")
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
