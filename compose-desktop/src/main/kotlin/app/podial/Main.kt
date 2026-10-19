package app.podial

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val Violet = Color(0xFF8B5CF6)
private val Pink = Color(0xFFEC4899)

/** Aide au développement : PODIAL_SCREENSHOT=/chemin.png enregistre une capture de l'écran après quelques secondes. */
private fun devScreenshot() {
    val path = System.getenv("PODIAL_SCREENSHOT") ?: return
    val wait = (System.getenv("PODIAL_SCREENSHOT_DELAY") ?: "20").toLong()
    Thread {
        Thread.sleep(wait * 1000)
        val r = java.awt.Robot()
        val img = r.createScreenCapture(java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().screenSize))
        javax.imageio.ImageIO.write(img, "png", java.io.File(path))
    }.apply { isDaemon = true }.start()
}

/** Aide au développement : PODIAL_TEST_PLAY=<url> joue une radio (volume 0) et affiche l'état du lecteur. */
private fun devTestPlay() {
    val url = System.getenv("PODIAL_TEST_PLAY") ?: return
    Thread {
        Player.volume = 0f
        Player.play(Item("radio", "Test", "", "", stream = url), null)
        repeat(8) { Thread.sleep(2000); Player.poll(); System.err.println("TEST playing=${Player.isPlaying} pos=${Player.positionMs} error=${Player.error}") }
    }.apply { isDaemon = true }.start()
}

fun main(args: Array<String>) {
    devScreenshot()
    devTestPlay()
    runApp(minimized = "--minimized" in args)
}

private fun runApp(minimized: Boolean) = application {
    LaunchedEffect(Unit) {   // vérifie les nouveaux épisodes au démarrage puis toutes les heures
        while (true) { runCatching { Store.checkAll() }; delay(3_600_000) }
    }
    Window(onCloseRequest = ::exitApplication, title = "Podial Player", state = rememberWindowState(width = 1100.dp, height = 760.dp, isMinimized = minimized)) {
        MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme())
            darkColorScheme(primary = Violet) else lightColorScheme(primary = Violet)) {
            Surface(Modifier.fillMaxSize()) { PodialApp() }
        }
    }
}

@Composable
fun PodialApp() {
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Item?>(null) }
    var about by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var quotes by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            Player.poll()
            val k = Player.key
            if (!Player.live && k.startsWith("ep|") && Updates.isFinished(Player.positionMs, Player.durationMs)) Store.setPlayed(k.removePrefix("ep|"), true)
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            NavigationRail {
                Spacer(Modifier.height(8.dp))
                val newTotal = Store.newCounts.collectAsState().value.values.sum()
                listOf(Icons.Default.Podcasts to "podcasts", Icons.Default.Radio to "radios",
                    Icons.Default.Star to "library", Icons.Default.Download to "downloads").forEachIndexed { i, (icon, label) ->
                    NavigationRailItem(selected = tab == i, onClick = { tab = i; detail = null },
                        icon = {
                            if (i == 2 && newTotal > 0) BadgedBox(badge = { Badge { Text("$newTotal") } }) { Icon(icon, null) }
                            else Icon(icon, null)
                        }, label = { Text(T[label]) })
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { quotes = true }) { Icon(Icons.Default.FormatQuote, T["my_quotes"]) }
                IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, T["settings"]) }
                IconButton(onClick = { about = true }, modifier = Modifier.padding(bottom = 8.dp)) { Icon(Icons.Default.Info, T["about"]) }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val d = detail
                if (d != null) DetailScreen(d) { detail = null }
                else when (tab) {
                    0 -> BrowseScreen("podcast") { detail = it }
                    1 -> BrowseScreen("radio", onSurprise = { Player.play(it, null); detail = it }) { detail = it }
                    2 -> LibraryScreen { detail = it }
                    else -> DownloadsScreen()
                }
            }
        }
        if (Player.key.isNotEmpty()) PlayerBar(onTranscript = { transcript = true })
        if (transcript) TranscriptDialog { transcript = false }
        if (quotes) QuotesDialog { quotes = false }
        if (about) AboutDialog { about = false }
        if (settings) SettingsDialog { settings = false }
    }
}

// ---------- pochettes ----------
private val imageCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun RemoteImage(url: String?, modifier: Modifier) {
    var bmp by remember(url) { mutableStateOf(url?.let { imageCache[it] }) }
    LaunchedEffect(url) {
        if (url.isNullOrEmpty() || bmp != null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection().apply { connectTimeout = 10000; readTimeout = 10000; setRequestProperty("User-Agent", "Podial-Desktop/1.0") }
                org.jetbrains.skia.Image.makeFromEncoded(c.getInputStream().use { it.readBytes() }).toComposeImageBitmap()
            }.getOrNull()?.also { imageCache[url] = it }
        }
    }
    bmp?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) }
}

@Composable
fun Cover(url: String?, name: String, size: Int, modifier: Modifier = Modifier) {
    val hue = (name.hashCode().toUInt() % 360u).toFloat()
    Box(modifier.size(size.dp).clip(RoundedCornerShape(12.dp))
        .background(Brush.linearGradient(listOf(Color.hsv(hue, 0.6f, 0.9f), Color.hsv((hue + 50) % 360, 0.7f, 0.8f)))),
        contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.headlineMedium)
        RemoteImage(url, Modifier.fillMaxSize())
    }
}

@Composable
fun ItemCard(item: Item, rank: Int?, onClick: () -> Unit) {
    val newCount = if (item.kind == "podcast") Store.newCounts.collectAsState().value[item.feed] ?: 0 else 0
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp)) {
        Box {
            Cover(item.art, item.name, 150, Modifier.fillMaxWidth().aspectRatio(1f))
            if (rank != null) Text("$rank", color = Color.White, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(6.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Violet, Pink)))
                    .padding(horizontal = 9.dp, vertical = 2.dp))
            if (newCount > 0) Text(T["new_badge"].format(newCount), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 7.dp, vertical = 1.dp))
            if (item.kind == "radio") Text(T["live"], color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 7.dp, vertical = 1.dp))
        }
        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Message(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(8.dp))

// ---------- parcourir ----------
@Composable
fun BrowseScreen(kind: String, onSurprise: (Item) -> Unit = {}, onOpen: (Item) -> Unit) {
    var query by remember(kind) { mutableStateOf("") }
    var genre by remember { mutableStateOf<Int?>(null) }
    var items by remember(kind) { mutableStateOf<List<Item>?>(null) }
    var failed by remember(kind) { mutableStateOf(false) }
    var country by remember { mutableStateOf(Store.radioCountry) }
    var map by remember { mutableStateOf(false) }
    var surprising by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val countries = remember { runCatching { World.parse(object {}.javaClass.getResourceAsStream("/world.json")!!.bufferedReader().use { it.readText() }) }.getOrDefault(emptyList()) }
    LaunchedEffect(kind, query, genre, country) {
        if (query.isNotBlank()) delay(500)
        items = null; failed = false
        items = try {
            if (kind == "podcast") { if (query.isBlank()) Api.topPodcasts(genre) else Api.searchPodcasts(query) }
            else Api.radios(country, query.ifBlank { null })
        } catch (e: Exception) { failed = true; emptyList() }
    }
    if (map) WorldMapDialog(countries, country, T["world_map"], T["show_radios"], T["close"],
        onConfirm = { country = it; Store.radioCountry = it; map = false }, onClose = { map = false })
    val list = items
    LazyVerticalGrid(GridCells.Adaptive(170.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, singleLine = true, shape = RoundedCornerShape(24.dp),
                        placeholder = { Text(T[if (kind == "podcast") "search_podcasts" else "search_radios"]) },
                        leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.weight(1f).padding(4.dp))
                    if (kind == "radio" && countries.isNotEmpty()) {
                        FilledTonalIconButton({
                            if (surprising) return@FilledTonalIconButton
                            surprising = true
                            scope.launch { Surprise.radio(countries.map { it.code }).also { surprising = false }?.let { onSurprise(it.second) } }
                        }) { if (surprising) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Casino, T["surprise"]) }
                        FilledTonalIconButton({ map = true }) { Icon(Icons.Default.Public, T["world_map"]) }
                    }
                }
                if (kind == "podcast" && query.isBlank()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    item { FilterChip(genre == null, { genre = null }, { Text(T["all"]) }) }
                    items(Api.APPLE_GENRES.entries.toList()) { g -> FilterChip(genre == g.key, { genre = g.key }, { Text(g.value) }) }
                }
                if (query.isBlank() && list?.isNotEmpty() == true) SectionTitle(if (kind == "radio") World.name(country) + " · " + T["top10"] else T["top10"])
            }
        }
        when {
            list == null -> item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            failed -> item(span = { GridItemSpan(maxLineSpan) }) { Message(T["load_failed"]) }
            list.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Message(T["no_results"]) }
            else -> items(list.size) { i -> ItemCard(list[i], if (query.isBlank() && i < 10) i + 1 else null) { onOpen(list[i]) } }
        }
    }
}

@Composable
fun LibraryScreen(onOpen: (Item) -> Unit) {
    val pods by Store.podcasts.collectAsState()
    val radios by Store.radios.collectAsState()
    if (pods.isEmpty() && radios.isEmpty()) { Message(T["library_empty"]); return }
    LazyVerticalGrid(GridCells.Adaptive(170.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
        if (pods.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(T["followed"]) }
            items(pods.size) { ItemCard(pods[it], null) { onOpen(pods[it]) } }
        }
        if (radios.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(T["favorite_radios"]) }
            items(radios.size) { ItemCard(radios[it], null) { onOpen(radios[it]) } }
        }
    }
}

// ---------- détail ----------
@Composable
fun DetailScreen(item: Item, onBack: () -> Unit) {
    val pods by Store.podcasts.collectAsState()
    val radios by Store.radios.collectAsState()
    val downloads by Store.downloads.collectAsState()
    val active by Store.active.collectAsState()
    val saved = (pods + radios).any { it.key == item.key }
    val played by Store.played.collectAsState()
    var fresh by remember(item.key) { mutableStateOf<Set<String>>(emptySet()) }
    var feed by remember(item.key) { mutableStateOf<Pair<String, List<Episode>>?>(null) }
    var failed by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key) {
        if (item.kind == "podcast") {
            feed = try { Api.fetchFeed(item.feed) } catch (e: Exception) {
                val mine = Store.downloads.value.filter { it.podcast.key == item.key }.map { it.episode }
                if (mine.isEmpty()) { failed = true; null } else "" to mine
            }
        }
    }
    LaunchedEffect(feed, saved) {
        val eps = feed?.second ?: return@LaunchedEffect
        if (!saved || item.kind != "podcast") return@LaunchedEffect
        val known = Store.knownFor(item.feed)
        if (known != null && fresh.isEmpty()) fresh = eps.map { it.url }.toSet() - known
        Store.markSeen(item.feed, eps.map { it.url })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, T["back"]) }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Cover(item.art, item.name, 160)
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Text(item.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.kind == "radio") Button(onClick = { Player.play(item, null) }) { Icon(Icons.Default.PlayArrow, null); Text(T["live"]) }
                        OutlinedButton(onClick = { Store.toggle(item) }) {
                            Icon(if (saved) Icons.Default.Star else Icons.Outlined.StarOutline, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (item.kind == "radio") T[if (saved) "remove_favorite" else "add_favorite"] else T[if (saved) "subscribed" else "subscribe"])
                        }
                    }
                }
            }
            feed?.first?.takeIf { it.isNotEmpty() }?.let { Text(it, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
            if (item.kind == "podcast" && feed == null && !failed) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            if (failed) Message(T["load_failed"])
        }
        feed?.second?.let { eps ->
            items(eps, key = { it.url }) { ep ->
                val dl = downloads.any { it.episode.url == ep.url }
                val progress = active[ep.url]
                val done = ep.url in played
                Row(Modifier.fillMaxWidth().clickable { Player.play(item, ep) }.padding(vertical = 6.dp).alpha(if (done) 0.55f else 1f),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (Player.key == "ep|" + ep.url) Icons.Default.GraphicEq else Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ep.url in fresh && !done) Text(T["new_chip"], color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                            Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(listOfNotNull(if (dl) "✓ " + T["downloaded"] else null, ep.date, ep.duration).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { Store.setPlayed(ep.url, !done) }) {
                        Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, T[if (done) "mark_unplayed" else "mark_played"])
                    }
                    when {
                        progress != null -> TextButton(onClick = { Store.cancel(ep.url) }) { Text("${(progress * 100).toInt()} %"); Icon(Icons.Default.Close, T["cancel_download"]) }
                        dl -> IconButton(onClick = { Store.removeDownload(ep.url) }) { Icon(Icons.Default.DeleteOutline, T["remove_download"]) }
                        else -> IconButton(onClick = { Store.download(item, ep) }) { Icon(Icons.Default.Download, T["download"]) }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun DownloadsScreen() {
    val downloads by Store.downloads.collectAsState()
    if (downloads.isEmpty()) { Message(T["no_downloads"]); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        items(downloads, key = { it.episode.url }) { d ->
            Row(Modifier.fillMaxWidth().clickable { Player.play(d.podcast, d.episode) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(d.podcast.art, d.podcast.name, 48)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(d.episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${d.podcast.name} · ${d.size / 1048576} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { Store.removeDownload(d.episode.url) }) { Icon(Icons.Default.DeleteOutline, T["remove_download"]) }
            }
            HorizontalDivider()
        }
    }
}

// ---------- barre du lecteur ----------
private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun PlayerBar(onTranscript: () -> Unit) {
    var timerMenu by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    val remaining = Sleep.remainingSec
    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(Player.art, Player.title, 56)
            Column(Modifier.width(220.dp)) {
                Text(Player.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(Player.error ?: Player.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = if (Player.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ Player.skip(-15_000) }, enabled = !Player.live) { Icon(Icons.Default.Replay10, null) }
            FilledIconButton({ Player.toggle() }, Modifier.size(48.dp)) {
                Icon(if (Player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, T[if (Player.isPlaying) "pause" else "play"])
            }
            IconButton({ Player.skip(30_000) }, enabled = !Player.live) { Icon(Icons.Default.Forward30, null) }
            if (Player.live) Text(T["live"], color = Color(0xFFE5484D), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            else {
                Text(clock(Player.positionMs), style = MaterialTheme.typography.bodySmall)
                Slider(seeking ?: (if (Player.durationMs > 0) Player.positionMs.toFloat() / Player.durationMs else 0f),
                    { seeking = it }, Modifier.weight(1f),
                    onValueChangeFinished = { seeking?.let { Player.seekTo((it * Player.durationMs).toLong()) }; seeking = null })
                Text(clock(Player.durationMs), style = MaterialTheme.typography.bodySmall)
            }
            if (!Player.live) {
                IconButton({ saveQuote("", Player.positionMs) }) { Icon(Icons.Default.FormatQuote, T["quote"]) }
                IconButton(onTranscript) { Icon(Icons.Default.Subject, T["transcript"]) }
            }
            if (!Player.live) TextButton({
                val l = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                Player.changeSpeed(l[(l.indexOf(Player.speed).coerceAtLeast(0) + 1) % l.size])
            }) { Text("${Player.speed}×") }
            Box {
                TextButton({ timerMenu = true }) {
                    Icon(Icons.Default.Alarm, T["sleep"])
                    if (remaining != null) Text(" " + clock(remaining * 1000L)) else if (Sleep.endOfEpisode) Text(" " + T["end_episode"])
                }
                DropdownMenu(timerMenu, { timerMenu = false }) {
                    for (m in listOf(15, 30, 45)) DropdownMenuItem({ Text(T["minutes"].format(m)) }, { Sleep.startMinutes(m); timerMenu = false })
                    DropdownMenuItem({ Text(T["one_hour"]) }, { Sleep.startMinutes(60); timerMenu = false })
                    DropdownMenuItem({ Text(T["two_hours"]) }, { Sleep.startMinutes(120); timerMenu = false })
                    if (!Player.live) DropdownMenuItem({ Text(T["end_episode"]) }, { Sleep.startEndOfEpisode(); timerMenu = false })
                    if (remaining != null || Sleep.endOfEpisode) DropdownMenuItem({ Text(T["cancel_timer"], color = MaterialTheme.colorScheme.error) }, { Sleep.cancel(); timerMenu = false })
                }
            }
            Icon(Icons.Default.VolumeUp, null)
            Slider(Player.volume, { Player.changeVolume(it) }, Modifier.width(110.dp))
        }
    }
}

const val APP_VERSION = "0.5.6"

@Composable
fun AboutDialog(onClose: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var status by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Podial") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(T["version"].format(APP_VERSION), fontWeight = FontWeight.SemiBold)
                Text(T["about_text"])
                Text(T["about_sources"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("MIT License · © 2026 Podial contributors", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ uri.openUri(Release.SITE) }) { Text("GitHub") }
                    TextButton({ uri.openUri(Release.SITE + "/issues") }) { Text(T["report_bug"]) }
                    TextButton({ uri.openUri(Release.SUPPORT) }) { Text("☕") }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                link?.let { TextButton({ uri.openUri(it) }) { Text(T["download"]) } }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; status = null; link = null
                scope.launch {
                    try {
                        val r = Release.check(APP_VERSION)
                        if (r == null) status = T["up_to_date"].format(APP_VERSION) else { status = T["update_available"].format(r.first); link = r.second }
                    } catch (e: Exception) { status = T["update_failed"] }
                    busy = false
                }
            }) { Text(T["check_updates"]) }
        },
        dismissButton = { TextButton(onClose) { Text(T["close"]) } },
    )
}

@Composable
fun SettingsDialog(onClose: () -> Unit) {
    var launch by remember { mutableStateOf(Autostart.isEnabled()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(T["settings"]) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(T["launch_login"], fontWeight = FontWeight.SemiBold)
                    Text(T[if (Autostart.available()) "launch_login_desc" else "launch_login_unavailable"],
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(launch, { launch = it; Autostart.set(it) }, enabled = Autostart.available())
            }
        },
        confirmButton = { TextButton(onClose) { Text(T["close"]) } },
    )
}


// ---------- citations et transcriptions ----------
fun saveQuote(text: String, startMs: Long) {
    val url = Player.key.removePrefix("ep|")
    Store.addQuote(Quote(Quote.idFor(url, startMs, text), text, startMs, url, Player.title, Player.artist, Player.feed, Player.art ?: ""))
}

@Composable
fun TranscriptDialog(onClose: () -> Unit) {
    val url = Player.key.removePrefix("ep|")
    var cues by remember { mutableStateOf<List<Cue>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (Player.transcriptUrl.isEmpty()) { failed = true; return@LaunchedEffect }
        val r = runCatching { withContext(Dispatchers.IO) { Store.loadTranscript(url, Player.transcriptUrl, Player.transcriptType) } }
        cues = r.getOrNull()?.takeIf { it.isNotEmpty() }
        failed = cues == null
    }
    val saved by Store.quotes.collectAsState()
    val list = cues
    val timed = list != null && list.firstOrNull()?.startMs?.let { it >= 0 } == true
    val current = if (timed) Transcripts.indexAt(list!!, Player.positionMs) else -1
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(current, follow) { if (follow && current >= 0) state.animateScrollToItem(maxOf(0, current - 1)) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(760.dp).fillMaxHeight(0.9f), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(T["transcript"], fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        Text(Player.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (timed) IconButton({ follow = !follow }) {
                        Icon(Icons.Default.MyLocation, T["follow"], tint = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClose) { Icon(Icons.Default.Close, T["close"]) }
                }
                when {
                    failed -> Message(T["no_transcript"])
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> {
                        Text(T["transcript_hint"], style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(list.size) { i ->
                                val c = list[i]
                                val id = Quote.idFor(url, c.startMs, c.text)
                                val on = saved.any { it.id == id }
                                val primary = MaterialTheme.colorScheme.primary
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(when { on -> primary.copy(alpha = 0.28f); i == current -> primary.copy(alpha = 0.10f); else -> Color.Transparent })
                                    .clickable { if (c.startMs >= 0) { Player.seekTo(c.startMs); follow = true } }.padding(8.dp),
                                    verticalAlignment = Alignment.Top) {
                                    if (c.startMs >= 0) Text(clock(c.startMs), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp).padding(top = 3.dp))
                                    Text(c.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                                        fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal)
                                    IconButton({ if (on) Store.removeQuote(id) else saveQuote(c.text, c.startMs) }, Modifier.size(32.dp)) {
                                        Icon(Icons.Default.FormatQuote, T["quote"], tint = if (on) primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuotesDialog(onClose: () -> Unit) {
    val quotes by Store.quotes.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var editing by remember { mutableStateOf<Quote?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(760.dp).fillMaxHeight(0.9f), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(T["my_quotes"], fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    IconButton(onClose) { Icon(Icons.Default.Close, T["close"]) }
                }
                if (quotes.isEmpty()) Message(T["no_quotes"])
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(quotes.size) { i ->
                        val q = quotes[i]
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                if (q.text.isNotBlank()) Text("“${q.text}”", style = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content), fontWeight = FontWeight.Medium)
                                if (q.note.isNotBlank()) Text(q.note, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                                Text(buildString { append(q.podcast); append(" · "); append(q.episodeTitle); if (q.startMs >= 0) append(" · ${clock(q.startMs)}") },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                                Row {
                                    IconButton({
                                        Player.play(Item("podcast", q.podcast, "", q.art, feed = q.feed), Episode(q.episodeTitle, q.episodeUrl, "", "", q.art), maxOf(0, q.startMs))
                                        onClose()
                                    }) { Icon(Icons.Default.PlayArrow, T["play_here"]) }
                                    IconButton({ editing = q }) { Icon(Icons.Default.Edit, T["note"]) }
                                    IconButton({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(Quote.shareText(q))) }) { Icon(Icons.Default.ContentCopy, T["copy"]) }
                                    Spacer(Modifier.weight(1f))
                                    IconButton({ Store.removeQuote(q.id) }) { Icon(Icons.Default.Delete, T["delete"], tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { q ->
        var text by remember(q.id) { mutableStateOf(q.note) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(T["note"]) },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ Store.setNote(q.id, text.trim()); editing = null }) { Text(T["save"]) } },
            dismissButton = { TextButton({ editing = null }) { Text(T["cancel"]) } })
    }
}
