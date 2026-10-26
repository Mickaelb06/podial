package app.podial

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Météo du moment : température et pictogramme (code WMO d'Open-Meteo). */
data class Weather(val temp: Int, val emoji: String)

object WeatherNow {
    fun emoji(code: Int) = when (code) {
        0 -> "☀️"; 1, 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "❄️"; in 95..99 -> "⛈️"; else -> "🌡️"
    }

    /** Dernière position connue de l'appareil (précision approximative, aucune position n'est suivie). */
    @Suppress("MissingPermission")
    fun lastKnown(ctx: android.content.Context): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        val l = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        return l.latitude to l.longitude
    }

    suspend fun fetch(lat: Double, lon: Double): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            // coordonnées arrondies à ~10 km : le service météo n'a pas besoin de mieux
            val u = "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f&current=temperature_2m,weather_code".format(Locale.US, lat, lon)
            val c = JSONObject(java.net.URL(u).readText()).getJSONObject("current")
            Weather(c.getDouble("temperature_2m").roundToInt(), emoji(c.getInt("weather_code")))
        }.getOrNull()
    }
}

/**
 * Mode « ambiance » : téléphone en paysage pendant une lecture. Un vinyle tourne avec le logo (ou la pochette)
 * de ce qui s'écoute, avec l'heure et, si la position approximative est autorisée, la météo.
 */
@Composable
fun AmbianceScreen(p: PlayerState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // écran allumé tant que l'ambiance est affichée (usage « horloge de bureau »)
    DisposableEffect(Unit) {
        val w = (ctx as? android.app.Activity)?.window
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    var weather by remember { mutableStateOf<Weather?>(null) }
    var granted by remember { mutableStateOf(WeatherNow.lastKnown(ctx) != null || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted && !Ambiance.asked) { Ambiance.asked = true; ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
    }
    LaunchedEffect(granted) {
        while (granted) {
            WeatherNow.lastKnown(ctx)?.let { (la, lo) -> WeatherNow.fetch(la, lo)?.let { weather = it } }
            delay(30 * 60_000L)
        }
    }

    // le disque tourne tant que ça joue, s'arrête là où il est quand on met en pause
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(p.isPlaying) {
        if (!p.isPlaying) return@LaunchedEffect
        var last = withFrameNanosCompat()
        while (true) {
            val t = withFrameNanosCompat()
            angle = (angle + (t - last) / 1_000_000_000f * 33.3f * 6f) % 360f   // 33 tours/min
            last = t
        }
    }
    val arm by animateFloatAsState(if (p.isPlaying) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "arm")

    val bg = Color(0xFF0B0A12)
    Box(Modifier.fillMaxSize().background(bg).clickable(onClick = { p.controller?.let { if (it.isPlaying) it.pause() else it.play() } })) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Brand.Violet.copy(alpha = 0.30f), Color.Transparent), Offset(size.width * 0.28f, size.height * 0.5f), size.height), radius = size.height, center = Offset(size.width * 0.28f, size.height * 0.5f))
            drawCircle(Brush.radialGradient(listOf(Brand.Pink.copy(alpha = 0.18f), Color.Transparent), Offset(size.width * 0.9f, size.height * 0.1f), size.height * 0.8f), radius = size.height * 0.8f, center = Offset(size.width * 0.9f, size.height * 0.1f))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val d = minOf(maxWidth, maxHeight) * 0.96f
                Vinyl(p, angle, arm, d)
            }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), color = Color.White, fontSize = 76.sp, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(java.text.SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)).replaceFirstChar { it.uppercase() },
                        color = Color.White.copy(alpha = 0.7f), fontSize = 17.sp)
                    weather?.let { Text("   ${it.emoji} ${it.temp}°", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(26.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EqBars(p.isPlaying, Modifier.padding(end = 10.dp))
                    if (p.live) Text(stringResource(R.string.live), color = Brand.Live, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(p.station.ifEmpty { p.title }, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = if (p.live) p.nowPlaying.ifEmpty { p.artist } else p.artist
                if (sub.isNotBlank()) Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClose, Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White.copy(alpha = 0.6f))
        }
    }
}

object Ambiance { var asked = false }

private suspend fun withFrameNanosCompat(): Long = androidx.compose.runtime.withFrameNanos { it }

@Composable
private fun Vinyl(p: PlayerState, angle: Float, arm: Float, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size)) {
        // le disque : noir, sillons, reflet qui reste fixe pendant que le disque tourne
        Canvas(Modifier.fillMaxSize().rotate(angle)) {
            val r = this.size.minDimension / 2
            val c = center
            drawCircle(Color(0xFF111015), r, c)
            var g = r * 0.42f
            while (g < r * 0.97f) { drawCircle(Color.White.copy(alpha = 0.045f), g, c, style = Stroke(1.2f)); g += r * 0.035f }
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), c), r, c)
            drawCircle(Color.White.copy(alpha = 0.12f), r, c, style = Stroke(2f))
        }
        // l'étiquette centrale : logo de la radio ou pochette
        val label = size * 0.40f
        Box(Modifier.size(label).align(Alignment.Center).rotate(angle).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            val art = p.art
            if (art.isNullOrEmpty()) Box(Modifier.fillMaxSize().background(Brand.grad), contentAlignment = Alignment.Center) {
                Text((p.station.ifEmpty { p.title }).firstOrNull()?.uppercase() ?: "♪", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
            } else AsyncImage(art, null, Modifier.fillMaxSize().then(if (p.live) Modifier.padding(label * 0.14f) else Modifier), contentScale = if (p.live) ContentScale.Fit else ContentScale.Crop)
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF0B0A12)))
        }
        // le bras de lecture se pose sur le disque quand ça joue
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(this.size.width * 0.95f, this.size.height * 0.08f)
            val len = this.size.minDimension * 0.62f
            rotate(-8f + 30f * arm, pivot) {
                drawLine(Color(0xFFCFCBDD), pivot, Offset(pivot.x, pivot.y + len), strokeWidth = 7f, cap = StrokeCap.Round)
                drawLine(Color(0xFF8B87A0), Offset(pivot.x, pivot.y + len), Offset(pivot.x - 14f, pivot.y + len + 26f), strokeWidth = 12f, cap = StrokeCap.Round)
            }
            drawCircle(Color(0xFF2A2735), 22f, pivot)
            drawCircle(Color(0xFFCFCBDD), 9f, pivot)
        }
    }
}
