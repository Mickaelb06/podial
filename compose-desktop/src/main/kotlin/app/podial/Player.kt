package app.podial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Lecteur audio de bureau (JavaFX Media). L'état est observable par l'interface Compose. */
object Player {
    var key by mutableStateOf("")          // identifiant de ce qui est lu (adresse de l'épisode ou de la radio)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var art by mutableStateOf<String?>(null)
    var live by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var volume by mutableFloatStateOf(1f)
    var error by mutableStateOf<String?>(null)

    private var mp: MediaPlayer? = null
    private var started = false

    private fun fx(block: () -> Unit) {
        if (!started) {
            started = true
            try { Platform.startup {} } catch (e: IllegalStateException) { /* déjà démarré */ }
            Platform.setImplicitExit(false)
        }
        Platform.runLater(block)
    }

    /** Lit un épisode (fichier local s'il est téléchargé) ou une radio. */
    fun play(item: Item, ep: Episode?) {
        val url: String; val k: String
        if (ep == null) { url = item.stream; k = "radio|" + item.stream }
        else {
            val local = Store.downloaded(ep.url)?.file
            url = if (local != null) File(local).toURI().toString() else ep.url
            k = "ep|" + ep.url
        }
        key = k; title = ep?.title ?: item.name; artist = if (ep != null) item.name else item.artist
        art = ep?.art?.takeIf { it.isNotEmpty() } ?: item.art.takeIf { it.isNotEmpty() }
        live = ep == null; positionMs = 0; durationMs = 0; speed = 1f; error = null; isPlaying = true
        fx {
            mp?.dispose()
            try {
                val player = MediaPlayer(Media(url))
                player.volume = volume.toDouble()
                player.setOnReady { durationMs = if (live) 0 else player.totalDuration.toMillis().toLong().coerceAtLeast(0) }
                player.setOnEndOfMedia { isPlaying = false; Sleep.onEnded() }
                player.setOnError { error = T["stream_error"]; isPlaying = false }
                mp = player
                player.play()
            } catch (e: Exception) { error = T["stream_error"]; isPlaying = false }
        }
    }

    fun toggle() { val p = mp ?: return; fx { if (isPlaying) { p.pause(); isPlaying = false } else { p.play(); isPlaying = true } } }
    fun pause() { val p = mp ?: return; fx { p.pause(); isPlaying = false } }
    fun seekTo(ms: Long) { val p = mp ?: return; fx { p.seek(Duration.millis(ms.toDouble())) } }
    fun skip(ms: Long) { if (!live) seekTo((positionMs + ms).coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) }
    fun changeSpeed(r: Float) { speed = r; val p = mp ?: return; fx { p.rate = r.toDouble() } }
    /** Volume de l'utilisateur ; le minuteur peut le baisser temporairement via [applyVolume]. */
    fun changeVolume(v: Float) { volume = v; applyVolume(v) }
    fun applyVolume(v: Float) { val p = mp ?: return; fx { p.volume = v.toDouble() } }

    /** Appelé régulièrement par l'interface pour rafraîchir la position. */
    fun poll() {
        val p = mp ?: return
        positionMs = p.currentTime.toMillis().toLong()
        if (!live && durationMs == 0L) durationMs = p.totalDuration.toMillis().takeIf { !it.isNaN() && !it.isInfinite() }?.toLong() ?: 0L
    }
}

/** Minuteur de sommeil : dans N minutes, ou à la fin de l'épisode ; le volume baisse doucement avant l'arrêt. */
object Sleep {
    private const val FADE_MS = 10_000L
    var remainingSec by mutableStateOf<Int?>(null)
    var endOfEpisode by mutableStateOf(false)
    private var job: Job? = null

    fun startMinutes(min: Int) {
        cancel()
        val deadline = System.currentTimeMillis() + min * 60_000L
        job = Store.scope.launch {
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) { Player.pause(); Player.applyVolume(Player.volume); remainingSec = null; break }
                remainingSec = (left / 1000).toInt() + 1
                if (left <= FADE_MS) Player.applyVolume(Player.volume * (left.toFloat() / FADE_MS))
                delay(if (left <= FADE_MS) 100 else 500)
            }
        }
    }

    fun startEndOfEpisode() { cancel(); endOfEpisode = true }

    fun cancel() { job?.cancel(); job = null; remainingSec = null; endOfEpisode = false; Player.applyVolume(Player.volume) }

    fun onEnded() { if (endOfEpisode) cancel() }
}
