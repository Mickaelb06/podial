package app.podial

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Carte image d'une citation (1080×1350), à partager sur les réseaux : dégradé aux couleurs du podcast, phrase, pochette, titre. */
object QuoteCard {
    const val W = 1080
    const val H = 1350
    private const val MARGIN = 96f
    private const val TEXT_TOP = 330
    private const val TEXT_BOTTOM = 1010

    /** Teinte stable pour un nom : le même podcast a toujours les mêmes couleurs (même calcul que les pochettes de l'appli). */
    fun hue(name: String): Float = (name.hashCode().toUInt() % 360u).toFloat()

    fun render(q: Quote, cover: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = hue(q.podcast.ifBlank { q.episodeTitle })
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.50f)),
                Color.HSVToColor(floatArrayOf((hue + 50) % 360, 0.70f, 0.28f)), Shader.TileMode.CLAMP)
        })

        // grand guillemet décoratif
        c.drawText("“", MARGIN - 10, 460f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; alpha = 55; textSize = 520f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        })

        // phrase : la plus grande taille qui tient dans la zone ; sinon on coupe avec « … »
        val body = q.text.ifBlank { q.note }.trim()
        val width = (W - 2 * MARGIN).toInt()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        var layout: StaticLayout? = null
        var size = 76
        while (size >= 34 && layout == null) {
            paint.textSize = size.toFloat()
            val l = build(body, paint, width, Int.MAX_VALUE)
            if (l.height <= TEXT_BOTTOM - TEXT_TOP) layout = l else size -= 4
        }
        if (layout == null) {
            paint.textSize = 34f
            val lineHeight = build("Ag", paint, width, 1).height.coerceAtLeast(1)
            layout = build(body, paint, width, ((TEXT_BOTTOM - TEXT_TOP) / lineHeight).coerceAtLeast(1))
        }
        c.save(); c.translate(MARGIN, TEXT_TOP + ((TEXT_BOTTOM - TEXT_TOP) - layout.height).coerceAtLeast(0) / 2f); layout.draw(c); c.restore()

        // pied : pochette, podcast, épisode
        var x = MARGIN
        if (cover != null) {
            val box = RectF(MARGIN, 1070f, MARGIN + 150f, 1220f)
            val clip = Path().apply { addRoundRect(box, 28f, 28f, Path.Direction.CW) }
            c.save(); c.clipPath(clip); c.drawBitmap(cover, null, box, Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
            x = box.right + 32f
        }
        val textWidth = (W - MARGIN - x).toInt()
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 200; textSize = 34f }
        val at = if (q.startMs >= 0) " · " + Transcripts.clock(q.startMs) else ""
        val nameLayout = build(q.podcast, name, textWidth, 1)
        val subLayout = build(q.episodeTitle + at, sub, textWidth, 2)
        val blockHeight = nameLayout.height + 6 + subLayout.height
        c.save(); c.translate(x, 1145f - blockHeight / 2f); nameLayout.draw(c); c.translate(0f, nameLayout.height + 6f); subLayout.draw(c); c.restore()

        // signature
        val sig = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 170; textSize = 34f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
        c.drawCircle(W / 2f - 110f, H - 74f, 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 190 })
        c.drawCircle(W / 2f - 110f, H - 74f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.35f)) })
        c.drawText("Podial Player", W / 2f + 20f, H - 62f, sig)
        return bmp
    }

    private fun build(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.12f)
            .setEllipsize(TextUtils.TruncateAt.END).setMaxLines(maxLines).build()

    /** Fabrique l'image dans le cache de l'appli (vidé par « Effacer le cache ») et ouvre la feuille de partage. */
    suspend fun share(ctx: Context, q: Quote) {
        val file = withContext(Dispatchers.IO) {
            val cover = if (q.art.isBlank()) null else withTimeoutOrNull(4000) {
                runCatching {
                    val req = ImageRequest.Builder(ctx).data(q.art).allowHardware(false).size(300, 300).build()
                    (ImageLoader(ctx).execute(req) as? SuccessResult)?.drawable?.toBitmap(300, 300)
                }.getOrNull()
            }
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            File(dir, "podial-citation-${q.id}.png").also { f -> f.outputStream().use { render(q, cover).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, Quote.shareText(q)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)   // permet à la feuille de partage d'afficher l'aperçu de l'image
        ctx.startActivity(Intent.createChooser(send, null))
    }
}
