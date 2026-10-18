package app.podial

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Widgets d'écran d'accueil : un compact (pochette + boutons) et un long (vinyle qui tourne). Pilotés par le service de lecture. */
object Widgets {
    const val ACT_PLAY = "app.podial.widget.PLAY"
    const val ACT_BACK = "app.podial.widget.BACK"
    const val ACT_FWD = "app.podial.widget.FWD"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var artUrl: String? = null
    private var art: Bitmap? = null

    private val providers = listOf(SmallWidget::class.java, VinylWidget::class.java)

    private fun ids(ctx: Context): Map<Class<*>, IntArray> {
        val m = AppWidgetManager.getInstance(ctx)
        return providers.associateWith { m.getAppWidgetIds(ComponentName(ctx, it)) }.filterValues { it.isNotEmpty() }
    }

    /** À appeler à chaque changement de lecture (titre, pochette, lecture/pause). */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        if (ids(app).isEmpty()) return
        val p = PlaybackService.instance?.player()
        val url = p?.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
        if (url != artUrl) {
            artUrl = url; art = null
            if (url != null) scope.launch {
                val bmp = runCatching {
                    val req = ImageRequest.Builder(app).data(url).allowHardware(false).size(256, 256).build()
                    (ImageLoader(app).execute(req) as? SuccessResult)?.drawable?.toBitmap(256, 256)
                }.getOrNull()
                if (artUrl == url && bmp != null) { art = bmp; render(app) }
            }
        }
        render(app)
    }

    private fun render(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        for ((cls, ids) in ids(ctx)) {
            val vinyl = cls == VinylWidget::class.java
            m.updateAppWidget(ids, views(ctx, vinyl))
        }
    }

    private fun views(ctx: Context, vinyl: Boolean): RemoteViews {
        val p = PlaybackService.instance?.player()
        val item = p?.currentMediaItem
        val active = p != null && item != null
        val playing = active && (p!!.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING))
        val rv = RemoteViews(ctx.packageName, if (vinyl) R.layout.widget_vinyl else R.layout.widget_small)
        val meta = item?.mediaMetadata
        rv.setTextViewText(R.id.w_title, if (active) (meta?.title ?: "") else ctx.getString(R.string.widget_idle))
        rv.setTextViewText(R.id.w_sub, if (active) (meta?.artist ?: meta?.albumTitle ?: "") else "")
        rv.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        val bmp = art?.takeIf { active }
        if (bmp != null) rv.setImageViewBitmap(R.id.w_cover, if (vinyl) shaped(bmp, 0.5f) else shaped(bmp, 0.2f))
        else rv.setImageViewResource(R.id.w_cover, R.drawable.ic_launcher)
        if (vinyl) {
            rv.setViewVisibility(R.id.w_vinyl_spin, if (playing) View.VISIBLE else View.GONE)
            rv.setViewVisibility(R.id.w_vinyl_static, if (playing) View.GONE else View.VISIBLE)
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        rv.setOnClickPendingIntent(R.id.w_root, open)
        val cls = if (vinyl) VinylWidget::class.java else SmallWidget::class.java
        fun button(id: Int, action: String, code: Int) = rv.setOnClickPendingIntent(id,
            if (active) PendingIntent.getBroadcast(ctx, code + if (vinyl) 10 else 0, Intent(ctx, cls).setAction(action), PendingIntent.FLAG_IMMUTABLE) else open)
        button(R.id.w_play, ACT_PLAY, 1); button(R.id.w_back, ACT_BACK, 2); button(R.id.w_fwd, ACT_FWD, 3)
        return rv
    }

    /** Recadre au carré et arrondit les coins (rayon = fraction du côté ; 0,5 = cercle). */
    private fun shaped(src: Bitmap, radius: Float): Bitmap {
        val side = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), side * radius, side * radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, -((src.width - side) / 2f), -((src.height - side) / 2f), paint)
        return out
    }

    fun handle(ctx: Context, action: String?) {
        val p = PlaybackService.instance?.player()
        if (p == null) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        when (action) {
            ACT_PLAY -> if (p.isPlaying || p.playWhenReady) p.pause() else p.play()
            ACT_BACK -> p.seekBack()
            ACT_FWD -> p.seekForward()
        }
    }
}

open class WidgetBase : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refresh(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action?.startsWith("app.podial.widget.") == true) Widgets.handle(context, intent.action)
    }
}

class SmallWidget : WidgetBase()
class VinylWidget : WidgetBase()
