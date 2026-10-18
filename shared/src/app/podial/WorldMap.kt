package app.podial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Carte du monde : toucher un pays le sélectionne ; pincer, glisser ou les boutons +/− pour zoomer. */
@Composable
fun WorldMap(countries: List<World.Country>, selected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val paths = remember(countries) {
        countries.associate { c ->
            c.code to Path().apply {
                fillType = PathFillType.EvenOdd
                for (r in c.rings) {
                    for (i in 0 until r.size / 2) {
                        val x = r[2 * i] + 180f; val y = World.LAT_TOP - r[2 * i + 1]
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
            }
        }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // la carte garde ses couleurs quel que soit le thème : mer sombre, pays dans les teintes de Podial, pays choisi en rose
    val sea = Color(0xFF111A3A)
    val edge = Color(0xFF111A3A)
    val accent = Color(0xFFEC4899)
    val tones = remember { listOf(0xFF7C5CE0, 0xFF5B7CE8, 0xFF3FA7B8, 0xFF9B6BD6, 0xFF6A8FD9, 0xFF8A63D2).map { Color(it) } }
    fun tone(code: String) = tones[(code.hashCode() and 0x7fffffff) % tones.size]

    // origine de la carte ajustée et centrée dans la zone, échelle de base : toute la carte tient
    fun base() = minOf(size.width / World.LON_SPAN, size.height / World.LAT_SPAN).coerceAtLeast(0.01f)
    fun origin() = Offset((size.width - World.LON_SPAN * base()) / 2f, (size.height - World.LAT_SPAN * base()) / 2f)
    fun zoomAround(centroid: Offset, factor: Float) {
        val z = (zoom * factor).coerceIn(1f, 14f)
        val b = base()
        val m = (centroid - origin() - pan) / (b * zoom)
        pan = centroid - origin() - m * (b * z)
        zoom = z
        if (zoom == 1f) pan = Offset.Zero
    }

    // à l'ouverture, on zoome sur le pays sélectionné (son plus grand morceau, sans les territoires lointains)
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(size) {
        if (focused || size == IntSize.Zero) return@LaunchedEffect
        focused = true
        val c = countries.firstOrNull { it.code == selected } ?: return@LaunchedEffect
        val ring = c.rings.maxByOrNull { r -> val xs = (0 until r.size step 2).map { r[it] }; val ys = (1 until r.size step 2).map { r[it] }
            (xs.max() - xs.min()) * (ys.max() - ys.min()) } ?: return@LaunchedEffect
        val xs = (0 until ring.size step 2).map { ring[it] }; val ys = (1 until ring.size step 2).map { ring[it] }
        val w = (xs.max() - xs.min()).coerceAtLeast(1f); val h = (ys.max() - ys.min()).coerceAtLeast(1f)
        val b = base()
        val z = minOf(size.width * 0.45f / (w * b), size.height * 0.45f / (h * b)).coerceIn(1f, 9f)
        val mx = (xs.max() + xs.min()) / 2f + 180f; val my = World.LAT_TOP - (ys.max() + ys.min()) / 2f
        zoom = z
        pan = Offset(size.width / 2f, size.height / 2f) - origin() - Offset(mx, my) * (b * z)
    }

    Box(modifier.clip(RoundedCornerShape(12.dp)).background(sea)) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(countries) { detectTransformGestures { centroid, p, z, _ -> zoomAround(centroid, z); if (zoom > 1f) pan += p } }
            .pointerInput(countries) {
                detectTapGestures { pos ->
                    val m = (pos - origin() - pan) / (base() * zoom)
                    World.hit(countries, m.x - 180f, World.LAT_TOP - m.y)?.let(onPick)
                }
            }) {
            val s = base() * zoom
            val o = origin() + pan
            withTransform({ translate(o.x, o.y); scale(s, s, Offset.Zero) }) {
                for ((code, path) in paths) if (code != selected) {
                    drawPath(path, tone(code))
                    drawPath(path, edge, style = Stroke(0.35f / zoom.coerceAtLeast(1f)))
                }
                paths[selected]?.let { drawPath(it, accent); drawPath(it, Color.White, style = Stroke(0.7f / zoom.coerceAtLeast(1f))) }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton({ zoomAround(Offset(size.width / 2f, size.height / 2f), 1.6f) }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF26325F), contentColor = Color.White)) { Icon(Icons.Default.Add, "+") }
            FilledTonalIconButton({ zoomAround(Offset(size.width / 2f, size.height / 2f), 1f / 1.6f) }, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF26325F), contentColor = Color.White)) { Icon(Icons.Default.Remove, "−") }
        }
    }
}

/** Fenêtre de la carte : on touche un pays, son nom s'affiche, puis on confirme. */
@Composable
fun WorldMapDialog(countries: List<World.Country>, current: String?, title: String, confirm: String, close: String,
                   onConfirm: (String) -> Unit, onClose: () -> Unit) {
    var picked by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 980.dp).fillMaxWidth(0.96f).fillMaxHeight(0.82f), shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClose) { Icon(Icons.Default.Close, close) }
                }
                WorldMap(countries, picked, { picked = it }, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp))
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val p = picked
                    Text(if (p != null) World.name(p) else "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button({ picked?.let(onConfirm) }, enabled = picked != null) { Text(confirm) }
                }
            }
        }
    }
}
