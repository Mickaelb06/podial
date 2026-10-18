package app.podial

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await

private val Violet = Color(0xFF8B5CF6)
private val Pink = Color(0xFFEC4899)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        CheckWorker.schedule(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme())
                darkColorScheme(primary = Violet) else lightColorScheme(primary = Violet)) {
                Surface(Modifier.fillMaxSize()) { PodialApp() }
            }
        }
    }
}

/** État du lecteur, lu depuis le MediaController (le service de lecture fait le travail). */
class PlayerState {
    var controller by mutableStateOf<MediaController?>(null)
    var isPlaying by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var art by mutableStateOf<String?>(null)
    var mediaId by mutableStateOf("")
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var live by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var station by mutableStateOf("")      // radio : nom de la station
    var nowPlaying by mutableStateOf("")   // radio : titre en cours, quand la station l'envoie

    fun refresh() {
        val c = controller ?: return
        isPlaying = c.isPlaying
        title = c.mediaMetadata.title?.toString() ?: ""
        artist = c.mediaMetadata.artist?.toString() ?: ""
        art = c.mediaMetadata.artworkUri?.toString()
        mediaId = c.currentMediaItem?.mediaId ?: ""
        live = c.isCurrentMediaItemLive || mediaId.startsWith("radio|")
        val itemTitle = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
        station = if (live) itemTitle else ""
        nowPlaying = if (live) title.takeIf { it.isNotBlank() && it != itemTitle } ?: "" else ""
        if (live && itemTitle.isNotBlank()) title = itemTitle
        duration = c.duration.coerceAtLeast(0)
        position = c.currentPosition
        speed = c.playbackParameters.speed
        if (!live && mediaId.startsWith("ep|") && Updates.isFinished(position, duration)) Store.setPlayed(mediaId.removePrefix("ep|"), true)
    }

    fun play(item: MediaItem) {
        val c = controller ?: return
        c.setMediaItem(item); c.prepare(); c.play()
    }
}

@Composable
fun PodialApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val player = remember { PlayerState() }
    LaunchedEffect(Unit) {
        val c = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync().await()
        c.addListener(object : Player.Listener { override fun onEvents(p: Player, e: Player.Events) = player.refresh() })
        player.controller = c
        player.refresh()
        while (true) { delay(500); player.refresh() }
    }
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Item?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    BackHandler(enabled = detail != null) { detail = null }

    Scaffold(
        bottomBar = {
            Column {
                if (player.mediaId.isNotEmpty()) MiniPlayer(player) { sheet = true }
                NavigationBar {
                    val newTotal = Store.newCounts.collectAsState().value.values.sum()
                    listOf(Icons.Default.Podcasts to R.string.tab_podcasts, Icons.Default.Radio to R.string.tab_radios,
                        Icons.Default.Star to R.string.tab_library, Icons.Default.Download to R.string.tab_downloads)
                        .forEachIndexed { i, (icon, label) ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i; detail = null },
                                icon = {
                                    if (i == 2 && newTotal > 0) BadgedBox(badge = { Badge { Text("$newTotal") } }) { Icon(icon, null) }
                                    else Icon(icon, null)
                                }, label = { Text(stringResource(label)) })
                        }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val d = detail
            if (d != null) DetailScreen(d, player) { detail = null }
            else when (tab) {
                0 -> BrowseScreen("podcast") { detail = it }
                1 -> BrowseScreen("radio") { detail = it }
                2 -> LibraryScreen(onAbout = { about = true }) { detail = it }
                else -> DownloadsScreen(player)
            }
        }
    }
    if (sheet) PlayerSheet(player) { sheet = false }
    if (about) AboutDialog(ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0") { about = false }
}

// ---------- éléments communs ----------
@Composable
fun Cover(url: String?, name: String, size: Int, modifier: Modifier = Modifier) {
    val hue = (name.hashCode().toUInt() % 360u).toFloat()
    Box(modifier.size(size.dp).clip(RoundedCornerShape(12.dp))
        .background(Brush.linearGradient(listOf(Color.hsv(hue, 0.6f, 0.9f), Color.hsv((hue + 50) % 360, 0.7f, 0.8f)))),
        contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.headlineMedium)
        if (!url.isNullOrEmpty()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun ItemCard(item: Item, rank: Int?, onClick: () -> Unit) {
    val newCount = if (item.kind == "podcast") Store.newCounts.collectAsState().value[item.feed] ?: 0 else 0
    Column(Modifier.clickable(onClick = onClick).padding(6.dp)) {
        Box {
            Cover(item.art, item.name, 150, Modifier.fillMaxWidth().aspectRatio(1f))
            if (rank != null) Text("$rank", color = Color.White, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(6.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Violet, Pink)))
                    .padding(horizontal = 9.dp, vertical = 2.dp))
            if (newCount > 0) Text(stringResource(R.string.new_badge, newCount), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 7.dp, vertical = 1.dp))
            if (item.kind == "radio") Text(stringResource(R.string.live), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 7.dp, vertical = 1.dp))
        }
        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Grid(items: List<Item>, header: @Composable (() -> Unit)? = null, onOpen: (Item) -> Unit, ranked: Boolean = false) {
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(items.size) { i -> ItemCard(items[i], if (ranked && i < 10) i + 1 else null) { onOpen(items[i]) } }
    }
}

@Composable
fun Message(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------- parcourir ----------
@Composable
fun BrowseScreen(kind: String, onOpen: (Item) -> Unit) {
    var query by remember(kind) { mutableStateOf("") }
    var genre by remember { mutableStateOf<Int?>(null) }
    var items by remember(kind) { mutableStateOf<List<Item>?>(null) }
    var failed by remember(kind) { mutableStateOf(false) }
    LaunchedEffect(kind, query, genre) {
        if (query.isNotBlank()) delay(500)   // on attend la fin de la frappe
        items = null; failed = false
        items = try {
            if (kind == "podcast") { if (query.isBlank()) Api.topPodcasts(genre) else Api.searchPodcasts(query) }
            else Api.radios(Api.country(), query.ifBlank { null })
        } catch (e: Exception) { failed = true; emptyList() }
    }
    val header: @Composable () -> Unit = {
        Column {
            OutlinedTextField(query, { query = it }, singleLine = true, shape = RoundedCornerShape(24.dp),
                placeholder = { Text(stringResource(if (kind == "podcast") R.string.search_podcasts else R.string.search_radios)) },
                leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(4.dp))
            if (kind == "podcast" && query.isBlank()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                item { FilterChip(genre == null, { genre = null }, { Text(stringResource(R.string.all)) }) }
                items(Api.APPLE_GENRES.entries.toList()) { g -> FilterChip(genre == g.key, { genre = g.key }, { Text(g.value) }) }
            }
            if (query.isBlank() && items?.isNotEmpty() == true) Text(stringResource(R.string.top10), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(8.dp))
        }
    }
    val list = items
    when {
        list == null -> Column { header(); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        failed -> Column { header(); Message(stringResource(R.string.load_failed)) }
        list.isEmpty() -> Column { header(); Message(stringResource(R.string.no_results)) }
        else -> Grid(list, header, onOpen, ranked = query.isBlank())
    }
}

@Composable
fun LibraryScreen(onAbout: () -> Unit, onOpen: (Item) -> Unit) {
    val pods by Store.podcasts.collectAsState()
    val radios by Store.radios.collectAsState()
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onAbout) { Icon(Icons.Default.Info, stringResource(R.string.about)) }
            }
        }
        if (pods.isEmpty() && radios.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Message(stringResource(R.string.library_empty)) }
        if (pods.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.followed_podcasts), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(8.dp)) }
            items(pods.size) { ItemCard(pods[it], null) { onOpen(pods[it]) } }
        }
        if (radios.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.favorite_radios), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(8.dp)) }
            items(radios.size) { ItemCard(radios[it], null) { onOpen(radios[it]) } }
        }
    }
}

// ---------- détail d'un podcast / d'une radio ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(item: Item, player: PlayerState, onBack: () -> Unit) {
    if (item.kind == "radio") { RadioDetail(item, player, onBack); return }
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
                // flux injoignable : on propose les épisodes téléchargés
                val mine = Store.downloads.value.filter { it.podcast.key == item.key }.map { it.episode }
                if (mine.isEmpty()) { failed = true; null } else "" to mine
            }
        }
    }
    // à l'ouverture d'un podcast suivi : on repère ce qui est nouveau puis on le marque comme vu
    LaunchedEffect(feed, saved) {
        val eps = feed?.second ?: return@LaunchedEffect
        if (!saved || item.kind != "podcast") return@LaunchedEffect
        val known = Store.knownFor(item.feed)
        if (known != null && fresh.isEmpty()) fresh = eps.map { it.url }.toSet() - known
        Store.markSeen(item.feed, eps.map { it.url })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Cover(item.art, item.name, 120)
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(item.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.kind == "radio") Button(onClick = { player.play(PlaybackService.radioItem(item)) }) {
                            Icon(Icons.Default.PlayArrow, null); Text(stringResource(R.string.live))
                        }
                        OutlinedButton(onClick = { Store.toggle(item) }) {
                            Icon(if (saved) Icons.Default.Star else Icons.Outlined.StarOutline, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (item.kind == "radio") (if (saved) R.string.remove_favorite else R.string.add_favorite)
                                                else (if (saved) R.string.subscribed else R.string.subscribe)))
                        }
                    }
                }
            }
            feed?.first?.takeIf { it.isNotEmpty() }?.let {
                Text(it, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (item.kind == "podcast" && feed == null && !failed) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            if (failed) Message(stringResource(R.string.load_failed))
        }
        feed?.second?.let { eps ->
            items(eps, key = { it.url }) { ep ->
                val dl = downloads.any { it.episode.url == ep.url }
                val progress = active[ep.url]
                val done = ep.url in played
                Row(Modifier.fillMaxWidth().clickable { player.play(PlaybackService.episodeItem(item, ep)) }.padding(vertical = 6.dp)
                    .alpha(if (done) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (player.mediaId == "ep|" + ep.url) Icons.Default.GraphicEq else Icons.Default.PlayArrow, null,
                        tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ep.url in fresh && !done) Text(stringResource(R.string.new_chip), color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                            Text(ep.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(listOfNotNull(if (dl) "✓ " + stringResource(R.string.downloaded) else null, ep.date, ep.duration)
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { Store.setPlayed(ep.url, !done) }) {
                        Icon(if (done) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            stringResource(if (done) R.string.mark_unplayed else R.string.mark_played))
                    }
                    when {
                        progress != null -> TextButton(onClick = { Store.cancel(ep.url) }) {
                            Text("${(progress * 100).toInt()} %"); Icon(Icons.Default.Close, stringResource(R.string.cancel_download))
                        }
                        dl -> IconButton(onClick = { Store.removeDownload(ep.url) }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.remove_download)) }
                        else -> IconButton(onClick = { Store.download(item, ep) }) { Icon(Icons.Default.Download, stringResource(R.string.download)) }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun DownloadsScreen(player: PlayerState) {
    val downloads by Store.downloads.collectAsState()
    if (downloads.isEmpty()) { Message(stringResource(R.string.no_downloads)); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(downloads, key = { it.episode.url }) { d ->
            Row(Modifier.fillMaxWidth().clickable { player.play(PlaybackService.episodeItem(d.podcast, d.episode)) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Cover(d.podcast.art, d.podcast.name, 48)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(d.episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${d.podcast.name} · ${d.size / 1048576} MB", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { Store.removeDownload(d.episode.url) }) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.remove_download)) }
            }
            HorizontalDivider()
        }
    }
}

// ---------- lecteur ----------
@Composable
fun MiniPlayer(p: PlayerState, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (p.live) RadioLogo(null, p.art, p.title, 44) else Cover(p.art, p.title, 44)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(p.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { p.controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
            Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (p.isPlaying) R.string.pause else R.string.play))
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(p: PlayerState, onClose: () -> Unit) {
    val remaining by SleepTimer.remaining.collectAsState()
    val eop by SleepTimer.endOfEpisode.collectAsState()
    var timerMenu by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    ModalBottomSheet(onDismissRequest = onClose) {
        if (p.live) { RadioSheet(p); return@ModalBottomSheet }
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Cover(p.art, p.title, 220)
            Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                modifier = Modifier.padding(top = 16.dp))
            Text(p.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!p.live && p.duration > 0) {
                Slider(seeking ?: (p.position.toFloat() / p.duration), { seeking = it },
                    onValueChangeFinished = { seeking?.let { v -> p.controller?.seekTo((v * p.duration).toLong()) }; seeking = null })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clock(p.position), style = MaterialTheme.typography.bodySmall)
                    Text(clock(p.duration), style = MaterialTheme.typography.bodySmall)
                }
            } else Text(stringResource(R.string.live), color = Color(0xFFE5484D), fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton({ p.controller?.seekBack() }, enabled = !p.live) { Icon(Icons.Default.Replay10, null) }
                FilledIconButton({ p.controller?.let { if (it.isPlaying) it.pause() else it.play() } }, Modifier.size(64.dp)) {
                    Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (p.isPlaying) R.string.pause else R.string.play), Modifier.size(36.dp))
                }
                IconButton({ p.controller?.seekForward() }, enabled = !p.live) { Icon(Icons.Default.Forward30, null) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (!p.live) TextButton({
                    val next = listOf(1f, 1.25f, 1.5f, 2f, 0.75f).let { it[(it.indexOf(p.speed).let { i -> if (i < 0) 0 else i } + 1) % it.size] }
                    p.controller?.setPlaybackSpeed(next)
                }) { Text("${p.speed}×") }
                Box {
                    TextButton({ timerMenu = true }) {
                        Icon(Icons.Default.Alarm, stringResource(R.string.sleep_timer))
                        Spacer(Modifier.width(6.dp))
                        Text(remaining?.let { clock(it * 1000L) } ?: if (eop) stringResource(R.string.end_of_episode) else stringResource(R.string.sleep_timer))
                    }
                    DropdownMenu(timerMenu, { timerMenu = false }) {
                        for (m in listOf(15, 30, 45)) DropdownMenuItem({ Text(stringResource(R.string.minutes_fmt, m)) }, { SleepTimer.startMinutes(m); timerMenu = false })
                        DropdownMenuItem({ Text(stringResource(R.string.one_hour)) }, { SleepTimer.startMinutes(60); timerMenu = false })
                        DropdownMenuItem({ Text(stringResource(R.string.two_hours)) }, { SleepTimer.startMinutes(120); timerMenu = false })
                        if (!p.live) DropdownMenuItem({ Text(stringResource(R.string.end_of_episode)) }, { SleepTimer.startEndOfEpisode(); timerMenu = false })
                        if (remaining != null || eop) DropdownMenuItem({ Text(stringResource(R.string.cancel_timer), color = MaterialTheme.colorScheme.error) }, { SleepTimer.cancel(); timerMenu = false })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AboutDialog(version: String, onClose: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var status by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val upToDate = stringResource(R.string.up_to_date, version)
    val failedMsg = stringResource(R.string.update_failed)
    val newVersionMsg = stringResource(R.string.update_available)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Podial") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.version_fmt, version), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.about_text))
                Text(stringResource(R.string.about_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("MIT License · © 2026 Podial contributors", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ uri.openUri(Release.SITE) }) { Text("GitHub") }
                    TextButton({ uri.openUri(Release.SITE + "/issues") }) { Text(stringResource(R.string.report_bug)) }
                    TextButton({ uri.openUri(Release.SUPPORT) }) { Text("☕") }
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                link?.let { TextButton({ uri.openUri(it) }) { Text(stringResource(R.string.download)) } }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; status = null; link = null
                scope.launch {
                    try {
                        val r = Release.check(version)
                        if (r == null) status = upToDate else { status = newVersionMsg.format(r.first); link = r.second }
                    } catch (e: Exception) { status = failedMsg }
                    busy = false
                }
            }) { Text(stringResource(R.string.check_updates)) }
        },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.close)) } },
    )
}

// ---------- radios ----------
/** Logo de station : affiché en entier sur fond clair (les logos de radio sont rarement carrés ni recadrables). */
@Composable
fun RadioLogo(item: Item?, art: String?, name: String, size: Int) {
    val hue = (name.hashCode().toUInt() % 360u).toFloat()
    Box(Modifier.size(size.dp).shadow(18.dp, RoundedCornerShape(28.dp), spotColor = Violet).clip(RoundedCornerShape(28.dp))
        .background(if (art.isNullOrEmpty()) Brush.linearGradient(listOf(Color.hsv(hue, 0.6f, 0.9f), Color.hsv((hue + 50) % 360, 0.7f, 0.8f)))
                    else Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF1EEFA)))),
        contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.displayMedium)
        if (!art.isNullOrEmpty()) AsyncImage(art, null, Modifier.fillMaxSize().padding((size / 9).dp), contentScale = ContentScale.Fit)
    }
}

@Composable
fun LivePill() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "dot")
    Row(Modifier.clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.live), color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun NowPlaying(text: String) {
    if (text.isBlank()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)) {
        Text(stringResource(R.string.on_air), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioDetail(item: Item, player: PlayerState, onBack: () -> Unit) {
    val radios by Store.radios.collectAsState()
    val saved = radios.any { it.key == item.key }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val here = player.mediaId == "radio|" + item.stream
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } }
        RadioLogo(item, item.art, item.name, 210)
        Spacer(Modifier.height(20.dp))
        LivePill()
        Spacer(Modifier.height(10.dp))
        Text(item.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(item.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val chips = (item.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(4) + listOf(item.quality)).filter { it.isNotEmpty() }
        if (chips.isNotEmpty()) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            chips.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
        }
        Spacer(Modifier.height(20.dp))
        FilledIconButton({ if (here) player.controller?.let { if (it.isPlaying) it.pause() else it.play() } else player.play(PlaybackService.radioItem(item)) },
            Modifier.size(76.dp)) {
            Icon(if (here && player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                stringResource(if (here && player.isPlaying) R.string.pause else R.string.play), Modifier.size(42.dp))
        }
        Spacer(Modifier.height(16.dp))
        if (here) NowPlaying(player.nowPlaying)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { Store.toggle(item) }) {
                Icon(if (saved) Icons.Default.Star else Icons.Outlined.StarOutline, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (saved) R.string.remove_favorite else R.string.add_favorite))
            }
            if (item.homepage.startsWith("http")) OutlinedButton(onClick = { uri.openUri(item.homepage) }) {
                Icon(Icons.Default.Language, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.website))
            }
        }
        ProgramToday(item)
        Spacer(Modifier.height(24.dp))
    }
}

/** Grille du jour (quand la station en publie une) : l'émission en cours est mise en avant. */
@Composable
fun ProgramToday(item: Item) {
    if (!Programs.supports(item)) return
    var shows by remember(item.key) { mutableStateOf<List<Show>?>(null) }
    var failed by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(item.key) { try { shows = Programs.today(item) } catch (e: Exception) { failed = true } }
    val list = shows ?: run {
        if (!failed) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        return
    }
    if (list.isEmpty()) return
    val now = Programs.current(list)
    Spacer(Modifier.height(20.dp))
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.program_today), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 8.dp))
        list.forEach { sh ->
            val live = sh == now
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                .background(if (live) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${sh.start.replace('h', ':')}", fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp),
                    color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(sh.title, fontWeight = if (live) FontWeight.ExtraBold else FontWeight.SemiBold)
                    if (sh.host.isNotEmpty()) Text(sh.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (live) Text(stringResource(R.string.now_label), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        Text(stringResource(R.string.program_source, Programs.sourceName(item)), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Lecteur plein écran d'une radio : logo, « en direct », titre en cours, un seul gros bouton, minuteur. */
@Composable
fun RadioSheet(p: PlayerState) {
    val remaining by SleepTimer.remaining.collectAsState()
    var timerMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RadioLogo(null, p.art, p.station.ifEmpty { p.title }, 190)
        Spacer(Modifier.height(18.dp))
        LivePill()
        Spacer(Modifier.height(10.dp))
        Text(p.station.ifEmpty { p.title }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(p.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        NowPlaying(p.nowPlaying)
        Spacer(Modifier.height(20.dp))
        FilledIconButton({ p.controller?.let { if (it.isPlaying) it.pause() else it.play() } }, Modifier.size(76.dp)) {
            Icon(if (p.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                stringResource(if (p.isPlaying) R.string.pause else R.string.play), Modifier.size(42.dp))
        }
        Box(Modifier.padding(top = 10.dp)) {
            TextButton({ timerMenu = true }) {
                Icon(Icons.Default.Alarm, stringResource(R.string.sleep_timer)); Spacer(Modifier.width(6.dp))
                Text(remaining?.let { clock(it * 1000L) } ?: stringResource(R.string.sleep_timer))
            }
            DropdownMenu(timerMenu, { timerMenu = false }) {
                for (m in listOf(15, 30, 45)) DropdownMenuItem({ Text(stringResource(R.string.minutes_fmt, m)) }, { SleepTimer.startMinutes(m); timerMenu = false })
                DropdownMenuItem({ Text(stringResource(R.string.one_hour)) }, { SleepTimer.startMinutes(60); timerMenu = false })
                DropdownMenuItem({ Text(stringResource(R.string.two_hours)) }, { SleepTimer.startMinutes(120); timerMenu = false })
                if (remaining != null) DropdownMenuItem({ Text(stringResource(R.string.cancel_timer), color = MaterialTheme.colorScheme.error) }, { SleepTimer.cancel(); timerMenu = false })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
