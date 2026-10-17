package app.podial

import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Minuteur de sommeil : dans N minutes, ou à la fin de l'épisode. Le volume baisse doucement avant l'arrêt. */
object SleepTimer {
    private const val FADE_MS = 10_000L
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var deadline = 0L
    private var fading = false

    /** Secondes restantes (null = pas de minuteur). */
    private val _remaining = MutableStateFlow<Int?>(null)
    val remaining: StateFlow<Int?> = _remaining
    private val _endOfEpisode = MutableStateFlow(false)
    val endOfEpisode: StateFlow<Boolean> = _endOfEpisode

    fun attach(p: ExoPlayer) { player = p }

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                p.pause(); p.volume = 1f; fading = false
                _remaining.value = null
                return
            }
            _remaining.value = (left / 1000).toInt() + 1
            if (left <= FADE_MS) { fading = true; p.volume = (left.toFloat() / FADE_MS).coerceIn(0f, 1f) }
            handler.postDelayed(this, if (left <= FADE_MS) 100 else 500)
        }
    }

    fun startMinutes(minutes: Int) {
        cancel()
        deadline = System.currentTimeMillis() + minutes * 60_000L
        handler.post(tick)
    }

    fun startEndOfEpisode() {
        cancel()
        player?.pauseAtEndOfMediaItems = true
        _endOfEpisode.value = true
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        _remaining.value = null
        _endOfEpisode.value = false
        player?.let { it.pauseAtEndOfMediaItems = false; if (fading) it.volume = 1f }
        fading = false
    }

    /** Appelé quand la lecture s'arrête d'elle-même (fin de l'épisode) pour effacer le minuteur « fin d'épisode ». */
    fun onPlaybackEnded() { if (_endOfEpisode.value) cancel() }
}
