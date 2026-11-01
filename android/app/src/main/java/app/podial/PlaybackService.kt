package app.podial

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.future
import java.io.File

/**
 * Service de lecture : sert l'application, la notification, les écouteurs Bluetooth et Android Auto.
 * Arborescence affichée dans la voiture : Podcasts suivis → épisodes, Radios favorites, Téléchargements.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) SleepTimer.onPlaybackEnded() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) SleepTimer.onPlaybackEnded()
            }
        })
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) Widgets.refresh(this@PlaybackService)
                if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) {
                    saveResume()
                    resumeHandler.removeCallbacks(resumeTick)
                    if (player.isPlaying) resumeHandler.postDelayed(resumeTick, 10_000)
                }
            }
        })
        SleepTimer.attach(player)
        restoreResume(player)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, player, Callback()).setSessionActivity(open).build()
        instance = this
        Widgets.refresh(this)
    }

    fun player(): Player? = session?.player

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    // ---- reprise : la dernière lecture est gardée, pour la retrouver (en pause) à la réouverture de l'appli ----
    private val resumeHandler = Handler(Looper.getMainLooper())
    private val resumeTick = object : Runnable {
        override fun run() { saveResume(); if (session?.player?.isPlaying == true) resumeHandler.postDelayed(this, 10_000) }
    }
    private fun resumePrefs() = getSharedPreferences("podial_resume", MODE_PRIVATE)

    private fun saveResume() {
        val p = session?.player ?: return
        val item = p.currentMediaItem
        if (item == null || p.playbackState == Player.STATE_ENDED) { resumePrefs().edit().clear().apply(); return }
        val md = item.mediaMetadata
        val j = JSONObject().put("id", item.mediaId).put("uri", item.localConfiguration?.uri?.toString() ?: "")
            .put("title", md.title?.toString() ?: "").put("artist", md.artist?.toString() ?: "").put("album", md.albumTitle?.toString() ?: "")
            .put("art", md.artworkUri?.toString() ?: "").put("type", md.mediaType ?: -1)
            .put("pos", if (p.isCurrentMediaItemLive) 0L else p.currentPosition.coerceAtLeast(0))
        md.extras?.let { e -> for (k in listOf("feed", "transcript", "transcriptType", "date")) j.put(k, e.getString(k) ?: "") }
        resumePrefs().edit().putString("item", j.toString()).apply()
    }

    private fun restoreResume(p: Player) {
        if (p.mediaItemCount > 0) return
        val j = runCatching { JSONObject(resumePrefs().getString("item", null) ?: return) }.getOrNull() ?: return
        val uri = j.optString("uri")
        if (uri.isEmpty()) return
        if (uri.startsWith("file:") && !File(Uri.parse(uri).path ?: "").exists()) return   // épisode téléchargé supprimé depuis
        val md = MediaMetadata.Builder().setTitle(j.optString("title")).setArtist(j.optString("artist")).setIsBrowsable(false).setIsPlayable(true)
            .setArtworkUri(j.optString("art").takeIf { it.isNotEmpty() }?.let(Uri::parse))
        j.optString("album").takeIf { it.isNotEmpty() }?.let { md.setAlbumTitle(it) }
        j.optInt("type", -1).takeIf { it >= 0 }?.let { md.setMediaType(it) }
        if (j.has("feed")) md.setExtras(Bundle().apply { for (k in listOf("feed", "transcript", "transcriptType", "date")) putString(k, j.optString(k)) })
        p.setMediaItem(MediaItem.Builder().setMediaId(j.optString("id")).setUri(uri).setMediaMetadata(md.build()).build(), j.optLong("pos"))
        p.prepare()   // en pause : on retrouve la lecture, un appui la relance
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveResume()
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        saveResume()
        resumeHandler.removeCallbacks(resumeTick)
        instance = null
        session?.run { player.release(); release() }
        session = null
        Widgets.refresh(applicationContext)
        super.onDestroy()
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, getString(R.string.app_name)), params))

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(cache[mediaId]?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
                                   page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Store.scope.future {
                val items: List<MediaItem> = when {
                    parentId == ROOT -> listOf(folder(PODCASTS, getString(R.string.tab_podcasts)),
                        folder(RADIOS, getString(R.string.tab_radios)), folder(DOWNLOADS, getString(R.string.tab_downloads)))
                    parentId == PODCASTS -> {
                        // podcasts suivis, puis le Top : on peut écouter en ligne sans rien suivre ni télécharger
                        val followed = Store.podcasts.value
                        val top = runCatching { Api.topPodcasts(null) }.getOrDefault(emptyList())
                            .filter { t -> followed.none { it.feed == t.feed } }
                        (followed + top).map { podcastFolder(it) }
                    }
                    parentId == RADIOS -> {
                        val favs = Store.radios.value
                        val top = runCatching { Api.radios(Store.radioCountry, limit = 30) }.getOrDefault(emptyList())
                            .filter { t -> favs.none { it.key == t.key } }
                        (favs + top).map { radioItem(it) }
                    }
                    parentId == DOWNLOADS -> Store.downloads.value.map { episodeItem(it.podcast, it.episode) }
                    parentId.startsWith("pod|") -> {
                        val feed = parentId.removePrefix("pod|")
                        val p = Store.podcasts.value.firstOrNull { it.feed == feed } ?: podcastsSeen[feed] ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                        Api.fetchFeed(p.feed).second.take(50).map { episodeItem(p, it) }
                    }
                    else -> emptyList()
                }
                val from = page * pageSize
                LibraryResult.ofItemList(ImmutableList.copyOf(items.drop(from).take(pageSize)), params)
            }

        /** Android Auto renvoie des éléments sans adresse : on les retrouve grâce à leur identifiant. */
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                     mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { cache[it.mediaId] ?: rebuild(it) }.toMutableList())

        /** Cache vide (service redémarré depuis la dernière navigation) : l'adresse audio est dans l'identifiant. */
        private fun rebuild(it: MediaItem): MediaItem {
            val url = when {
                it.mediaId.startsWith("radio|") -> it.mediaId.removePrefix("radio|")
                it.mediaId.startsWith("ep|") -> it.mediaId.removePrefix("ep|")
                else -> return it
            }
            return it.buildUpon().setUri(url).build()
        }
    }

    companion object {
        const val ROOT = "root"
        const val PODCASTS = "podcasts"
        const val RADIOS = "radios"
        const val DOWNLOADS = "downloads"
        var instance: PlaybackService? = null
        /** Identifiant -> élément déjà construit (pour retrouver l'adresse audio quand le contrôleur ne la renvoie pas). */
        private val cache = HashMap<String, MediaItem>()
        /** Podcasts du Top affichés dans Android Auto (non suivis) : pour retrouver leur flux au moment de les ouvrir. */
        private val podcastsSeen = HashMap<String, Item>()

        fun libraryChanged() {
            val s = instance?.session ?: return
            for (id in listOf(PODCASTS, RADIOS, DOWNLOADS)) s.notifyChildrenChanged(id, Int.MAX_VALUE, null)
        }

        private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build().also { cache[id] = it }

        private fun podcastFolder(p: Item): MediaItem {
            val id = "pod|" + p.feed
            podcastsSeen[p.feed] = p
            return MediaItem.Builder().setMediaId(id).setMediaMetadata(
                MediaMetadata.Builder().setTitle(p.name).setArtist(p.artist).setIsBrowsable(true).setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .setArtworkUri(p.art.takeIf { it.isNotEmpty() }?.let(Uri::parse)).build()).build().also { cache[id] = it }
        }

        fun radioItem(r: Item): MediaItem {
            val id = "radio|" + r.stream
            return MediaItem.Builder().setMediaId(id).setUri(r.stream).setMediaMetadata(
                MediaMetadata.Builder().setTitle(r.name).setArtist(r.artist).setIsBrowsable(false).setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .setArtworkUri(r.art.takeIf { it.isNotEmpty() }?.let(Uri::parse)).build()).build().also { cache[id] = it }
        }

        fun episodeItem(p: Item, e: Episode): MediaItem {
            val id = "ep|" + e.url
            val local = Store.downloaded(e.url)?.file
            val art = e.art.ifEmpty { p.art }
            return MediaItem.Builder().setMediaId(id).setUri(if (local != null) Uri.fromFile(File(local)) else Uri.parse(e.url)).setMediaMetadata(
                MediaMetadata.Builder().setTitle(e.title).setArtist(p.name).setAlbumTitle(p.name)
                    .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setArtworkUri(art.takeIf { it.isNotEmpty() }?.let(Uri::parse)).setExtras(Bundle().apply { putString("feed", p.feed); putString("transcript", e.transcript); putString("transcriptType", e.transcriptType); putString("date", e.date) }).build()).build()
                .also { cache[id] = it }
        }
    }
}
