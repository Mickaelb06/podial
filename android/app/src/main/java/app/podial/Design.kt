package app.podial

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Couleurs et formes communes avec le site (docs/index.html) : violet → rose, coins très arrondis, fond sombre profond. */
object Brand {
    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFEC4899)
    val Cyan = Color(0xFF22D3EE)
    val Live = Color(0xFFE5484D)
    val grad = Brush.linearGradient(listOf(Violet, Pink))
}

private val DarkColors = darkColorScheme(
    primary = Brand.Violet, onPrimary = Color.White, secondary = Brand.Pink, tertiary = Brand.Cyan,
    background = Color(0xFF0B0914), onBackground = Color(0xFFF4F2FB),
    surface = Color(0xFF0B0914), onSurface = Color(0xFFF4F2FB),
    surfaceVariant = Color(0xFF191631), onSurfaceVariant = Color(0xFFA9A4C4),
    surfaceContainer = Color(0xFF191631), surfaceContainerHigh = Color(0xFF221E3D), surfaceContainerHighest = Color(0xFF2A2548),
    outline = Color(0xFF3A3560), outlineVariant = Color(0x22FFFFFF),
)
private val LightColors = lightColorScheme(
    primary = Brand.Violet, onPrimary = Color.White, secondary = Brand.Pink, tertiary = Color(0xFF0891B2),
    background = Color(0xFFFBFAFF), onBackground = Color(0xFF1B1829),
    surface = Color(0xFFFBFAFF), onSurface = Color(0xFF1B1829),
    surfaceVariant = Color(0xFFF1EEFC), onSurfaceVariant = Color(0xFF5D5877),
    surfaceContainer = Color(0xFFF1EEFC), surfaceContainerHigh = Color(0xFFE9E4FA), surfaceContainerHighest = Color(0xFFE0DAF6),
    outline = Color(0xFFC9C2E6), outlineVariant = Color(0x1A140A3C),
)

@Composable
fun PodialTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val base = Typography()
    val type = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors, typography = type,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(34.dp)),
        content = content,
    )
}

/** Lueurs violette et rose qui dérivent lentement derrière l'écran (le fond animé du site). */
@Composable
fun Aurora(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.red < 0.5f
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(21000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val k = if (dark) 1f else 0.55f
    Canvas(modifier.fillMaxWidth().fillMaxHeight(0.55f)) {
        drawRect(Brush.radialGradient(listOf(Brand.Violet.copy(alpha = .34f * k), Color.Transparent),
            center = Offset(size.width * (0.05f + 0.3f * a), size.height * (0.0f + 0.15f * b)), radius = size.width * 0.95f))
        drawRect(Brush.radialGradient(listOf(Brand.Pink.copy(alpha = .26f * k), Color.Transparent),
            center = Offset(size.width * (0.98f - 0.32f * b), size.height * (0.02f + 0.18f * a)), radius = size.width * 0.85f))
        drawRect(Brush.radialGradient(listOf(Brand.Cyan.copy(alpha = .10f * k), Color.Transparent),
            center = Offset(size.width * (0.5f + 0.2f * a), size.height * (0.55f - 0.1f * b)), radius = size.width * 0.7f))
    }
}

/** Barres d'égaliseur animées : la musique qui joue, comme dans la barre du haut sous GNOME. */
@Composable
fun EqBars(active: Boolean, modifier: Modifier = Modifier, height: Int = 18) {
    val t = rememberInfiniteTransition(label = "eq")
    Row(modifier.height(height.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 4) {
            val h by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520 + i * 170, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(3.5.dp).fillMaxHeight(if (active) h else 0.3f).clip(RoundedCornerShape(2.dp)).background(Brand.grad))
        }
    }
}

/** Pastille de filtre : fond dégradé quand elle est choisie, icône facultative. Remplace FilterChip de Material (même ordre de paramètres). */
@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, leadingIcon: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    val on by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "chip")
    val ink = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Box(modifier.then(if (selected) Modifier.shadow(10.dp, CircleShape, ambientColor = Brand.Violet, spotColor = Brand.Violet) else Modifier)
        .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (selected) 0f else 1f), CircleShape).clickable(onClick = onClick)) {
        Box(Modifier.matchParentSize().alpha(on).background(Brand.grad))
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides ink, LocalTextStyle provides MaterialTheme.typography.labelLarge) {
                if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(6.dp)) }
                label()
            }
        }
    }
}

@Composable
fun ChipIcon(icon: ImageVector) = Icon(icon, null, Modifier.size(17.dp))

/** Bouton plein, dégradé violet → rose avec halo. */
@Composable
fun GradientButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.shadow(12.dp, CircleShape, ambientColor = Brand.Violet, spotColor = Brand.Pink).clip(CircleShape).background(Brand.grad)
        .pressable(onClick).padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompositionLocalProvider(LocalContentColor provides Color.White, LocalTextStyle provides MaterialTheme.typography.labelLarge) { content() }
    }
}

/** Bouton lecture / pause rond, dégradé. */
@Composable
fun PlayCircle(playing: Boolean, size: Int, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    Box(modifier.size(size.dp).shadow(14.dp, CircleShape, ambientColor = Brand.Violet, spotColor = Brand.Pink).clip(CircleShape).background(Brand.grad).pressable(onClick),
        contentAlignment = Alignment.Center) {
        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, description, Modifier.size((size * 0.55f).dp), tint = Color.White)
    }
}

/** Léger rebond au toucher. */
fun Modifier.pressable(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    this.graphicsLayer { scaleX = s; scaleY = s }.clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** Apparition en fondu et glissement, décalée selon la position dans la grille. */
fun Modifier.entrance(index: Int = 0): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay((index % 8) * 40L); a.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 40f; val s = 0.96f + 0.04f * a.value; scaleX = s; scaleY = s }
}

/** Reflet qui traverse les blocs en attente de chargement. */
fun Modifier.shimmer(): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-400f, 1200f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    val base = MaterialTheme.colorScheme.surfaceVariant
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    this.background(Brush.linearGradient(listOf(base, hi, base), start = Offset(x, 0f), end = Offset(x + 400f, 400f)))
}

/** Grille de cartes grisées pendant le chargement. */
@Composable
fun SkeletonGrid(header: @Composable () -> Unit = {}) {
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize(), userScrollEnabled = false) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(12) {
            Column(Modifier.padding(6.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).shimmer())
                Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.8f).height(12.dp).clip(CircleShape).shimmer())
                Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.5f).height(10.dp).clip(CircleShape).shimmer())
            }
        }
    }
}

/** Barre de navigation flottante : l'onglet choisi s'allonge et prend le dégradé. */
@Composable
fun FloatingNav(tab: Int, onTab: (Int) -> Unit, newTotal: Int) {
    val items = listOf(Icons.Default.Podcasts to R.string.tab_podcasts, Icons.Default.Radio to R.string.tab_radios,
        Icons.Default.Star to R.string.tab_library, Icons.Default.Download to R.string.tab_downloads)
    Surface(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()
        .shadow(20.dp, CircleShape, ambientColor = Brand.Violet, spotColor = Brand.Violet),
        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { i, (icon, label) ->
                val sel = tab == i
                val on by animateFloatAsState(if (sel) 1f else 0f, tween(280), label = "nav$i")
                Box(Modifier.weight(if (sel) 2.1f else 1f).height(46.dp).clip(CircleShape).clickable { onTab(i) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().alpha(on).background(Brand.grad))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val ink = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        BadgedBox(badge = { if (i == 2 && newTotal > 0) Badge(containerColor = Brand.Live) { Text("$newTotal") } }) {
                            Icon(icon, null, tint = ink, modifier = Modifier.size(22.dp))
                        }
                        AnimatedVisibility(sel, enter = fadeIn(tween(200)) + expandHorizontally(), exit = fadeOut(tween(120)) + shrinkHorizontally()) {
                            Text(androidx.compose.ui.res.stringResource(label), color = Color.White, style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, modifier = Modifier.padding(start = 7.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Petite pastille « EN DIRECT » dont le point pulse. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "livebadge")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    Row(modifier.clip(CircleShape).background(Brand.Live).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).alpha(a).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(4.dp))
        Text(androidx.compose.ui.res.stringResource(R.string.live), color = Color.White, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp))
    }
}
