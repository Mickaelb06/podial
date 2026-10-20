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
        runCatching { Logos.loadTable(assets.open("radio_logos.json").bufferedReader().use { it.readText() }) }
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
    var feed by mutableStateOf("")
    var transcriptUrl by mutableStateOf("")
    var transcriptType by mutableStateOf("")

    fun refresh() {
        val c = controller ?: return
        isPlaying = c.isPlaying
        title = c.mediaMetadata.title?.toString() ?: ""
        artist = c.mediaMetadata.artist?.toString() ?: ""
        art = c.mediaMetadata.artworkUri?.toString()
        mediaId = c.currentMediaItem?.mediaId ?: ""
        val ex = c.mediaMetadata.extras
        feed = ex?.getString("feed") ?: ""
        transcriptUrl = ex?.getString("transcript") ?: ""
        transcriptType = ex?.getString("transcriptType") ?: ""
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

    fun play(item: MediaItem, startMs: Long = 0L) {
        val c = controller ?: return
        c.setMediaItem(item, startMs); c.prepare(); c.play()
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
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var quotes by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf(false) }
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
                1 -> BrowseScreen("radio", onSurprise = { player.play(PlaybackService.radioItem(it)); detail = it }) { detail = it }
                2 -> LibraryScreen(onMenu = { menu = true }) { detail = it }
                else -> DownloadsScreen(player)
            }
        }
    }
    if (sheet) PlayerSheet(player, onTranscript = { sheet = false; transcript = true }) { sheet = false }
    if (transcript) TranscriptScreen(player) { transcript = false }
    if (quotes) QuotesScreen(player) { quotes = false }
    if (menu) MenuSheet(ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0",
        onQuotes = { menu = false; quotes = true }, onAbout = { menu = false; about = true }) { menu = false }
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
fun BrowseScreen(kind: String, onSurprise: (Item) -> Unit = {}, onOpen: (Item) -> Unit) {
    var query by remember(kind) { mutableStateOf("") }
    var genre by remember { mutableStateOf<Int?>(null) }
    var panel by remember { mutableStateOf<String?>(null) }      // null, "radios" (podcasts par radio) ou "local" (flashs d'info locaux)
    var source by remember { mutableStateOf<Source?>(null) }     // radio ou éditeur ouvert dans « radios »
    var items by remember(kind) { mutableStateOf<List<Item>?>(null) }
    var failed by remember(kind) { mutableStateOf(false) }
    var country by remember { mutableStateOf(Store.radioCountry) }
    var map by remember { mutableStateOf(false) }
    var surprising by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val countries = remember { runCatching { World.parse(ctx.assets.open("world.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList()) }
    val catalog = remember { runCatching { SourceCatalog.parse(ctx.assets.open("podcast_sources.json").bufferedReader().use { it.readText() }) }.getOrNull() }
    BackHandler(enabled = kind == "podcast" && (source != null || panel != null)) { if (source != null) source = null else panel = null }
    LaunchedEffect(kind, query, genre, country, panel, source) {
        val src = source
        if (kind == "podcast" && query.isBlank() && panel == "radios" && src == null) { items = emptyList(); failed = false; return@LaunchedEffect }   // liste fixe : rien à charger
        if (query.isNotBlank()) delay(500)   // on attend la fin de la frappe
        items = null; failed = false
        items = try {
            if (kind == "podcast") when {
                query.isNotBlank() -> Api.searchPodcasts(query)
                src != null -> Api.sourcePodcasts(src)
                panel == "local" && catalog != null -> Api.localFlashes(catalog.sources, catalog.local)
                else -> Api.topPodcasts(genre)
            }
            else Api.radios(country, query.ifBlank { null })
        } catch (e: Exception) { failed = true; emptyList() }
    }
    // la barre de recherche reste hors du `when` : sinon elle est recréée à chaque changement d'état
    // (chargement → liste) et perd le focus, ce qui referme le clavier
    val searchBar: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it; if (it.isNotBlank()) { panel = null; source = null } }, singleLine = true, shape = RoundedCornerShape(24.dp),
                placeholder = { Text(stringResource(if (kind == "podcast") R.string.search_podcasts else R.string.search_radios)) },
                leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.weight(1f).padding(4.dp))
            if (kind == "radio" && countries.isNotEmpty()) {
                FilledTonalIconButton({
                    if (surprising) return@FilledTonalIconButton
                    surprising = true
                    scope.launch {
                        val r = Surprise.radio(countries.map { it.code })
                        surprising = false
                        if (r == null) android.widget.Toast.makeText(ctx, R.string.surprise_none, android.widget.Toast.LENGTH_SHORT).show()
                        else {
                            android.widget.Toast.makeText(ctx, "🎲 ${r.second.name} · ${World.flag(r.first)} ${World.name(r.first)}", android.widget.Toast.LENGTH_LONG).show()
                            onSurprise(r.second)
                        }
                    }
                }) { if (surprising) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Casino, stringResource(R.string.surprise)) }
                FilledTonalIconButton({ map = true }) { Icon(Icons.Default.Public, stringResource(R.string.world_map)) }
            }
        }
    }
    val header: @Composable () -> Unit = {
        Column {
            if (kind == "podcast" && query.isBlank() && source != null) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ source = null }) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
                Text(source!!.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            }
            else if (kind == "podcast" && query.isBlank() && catalog != null) {
                val parent = genre?.let { g -> catalog.genre(g)?.let { if (it.parent == 0) it.id else it.parent } }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    item { FilterChip(genre == null && panel == null, { genre = null; panel = null }, { Text(stringResource(R.string.all)) }) }
                    item { FilterChip(panel == "radios", { panel = "radios"; genre = null }, { Text("📻 " + stringResource(R.string.panel_radios)) }) }
                    item { FilterChip(panel == "local", { panel = "local"; genre = null }, { Text("🗞 " + stringResource(R.string.panel_local)) }) }
                    items(catalog.topLevel()) { g -> FilterChip(panel == null && parent == g.id, { genre = g.id; panel = null }, { Text(g.name()) }) }
                }
                val children = if (panel == null && parent != null) catalog.children(parent) else emptyList()
                if (children.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    item { FilterChip(genre == parent, { genre = parent }, { Text(catalog.genre(parent!!)?.name() ?: "") }) }
                    items(children) { g -> FilterChip(genre == g.id, { genre = g.id }, { Text(g.name()) }) }
                }
            }
            else if (kind == "podcast" && query.isBlank()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                item { FilterChip(genre == null, { genre = null }, { Text(stringResource(R.string.all)) }) }
                items(Api.APPLE_GENRES.entries.toList()) { g -> FilterChip(genre == g.key, { genre = g.key }, { Text(g.value) }) }
            }
            if (query.isBlank() && panel == null && items?.isNotEmpty() == true) Text(if (kind == "radio") World.flag(country) + " " + World.name(country) + " · " + stringResource(R.string.top10) else stringResource(R.string.top10), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(8.dp))
        }
    }
    if (map) WorldMapDialog(countries, country, stringResource(R.string.world_map), stringResource(R.string.show_radios), stringResource(R.string.close),
        onConfirm = { country = it; Store.radioCountry = it; map = false }, onClose = { map = false })
    val list = items
    Column {
        searchBar()
        when {
            kind == "podcast" && query.isBlank() && panel == "radios" && source == null && catalog != null -> SourcesList(catalog, header) { source = it }
            list == null -> Column { header(); Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            failed -> Column { header(); Message(stringResource(R.string.load_failed)) }
            list.isEmpty() -> Column { header(); Message(stringResource(R.string.no_results)) }
            else -> Grid(list, header, onOpen, ranked = query.isBlank() && panel == null)
        }
    }
}

/** Radios et éditeurs dont on liste tous les podcasts, pays de l'utilisateur d'abord. */
@Composable
fun SourcesList(catalog: SourceCatalog, header: @Composable () -> Unit, onPick: (Source) -> Unit) {
    val groups = remember(catalog) { catalog.byCountry(Api.country()) }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        for ((cc, list) in groups) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(World.flag(cc) + " " + World.name(cc), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(8.dp))
            }
            items(list.size) { i ->
                val src = list[i]
                Column(Modifier.clickable { onPick(src) }.padding(6.dp)) {
                    Cover(null, src.name, 150, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(src.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(onMenu: () -> Unit, onOpen: (Item) -> Unit) {
    val pods by Store.podcasts.collectAsState()
    val radios by Store.radios.collectAsState()
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, stringResource(R.string.menu)) }
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
    var sort by remember(item.key) { mutableStateOf(EpSort.NEWEST) }
    var epFilter by remember(item.key) { mutableStateOf(EpFilter.ALL) }
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
        feed?.second?.let { allEps ->
            item {
                // tri par date et filtres : tous, téléchargés, pas encore terminés
                Column(Modifier.padding(top = 4.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(sort == EpSort.NEWEST, { sort = EpSort.NEWEST }, { Text(stringResource(R.string.ep_newest)) }) }
                        item { FilterChip(sort == EpSort.OLDEST, { sort = EpSort.OLDEST }, { Text(stringResource(R.string.ep_oldest)) }) }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        item { FilterChip(epFilter == EpFilter.ALL, { epFilter = EpFilter.ALL }, { Text(stringResource(R.string.ep_all)) }) }
                        item { FilterChip(epFilter == EpFilter.DOWNLOADED, { epFilter = EpFilter.DOWNLOADED }, { Text(stringResource(R.string.ep_downloaded)) }) }
                        item { FilterChip(epFilter == EpFilter.UNFINISHED, { epFilter = EpFilter.UNFINISHED }, { Text(stringResource(R.string.ep_unfinished)) }) }
                    }
                }
            }
            val eps = EpisodeList.apply(allEps, sort, epFilter, downloads.mapTo(HashSet()) { it.episode.url }, played)
            if (eps.isEmpty() && allEps.isNotEmpty()) item { Message(stringResource(R.string.ep_none_filtered)) }
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
fun PlayerSheet(p: PlayerState, onTranscript: () -> Unit, onClose: () -> Unit) {
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
            if (!p.live) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val saved = stringResource(R.string.quote_saved)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton({
                        saveQuote(p, "", p.position)
                        android.widget.Toast.makeText(ctx, saved.format(clock(p.position)), android.widget.Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.FormatQuote, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.quote_here)) }
                    FilledTonalButton(onTranscript) { Icon(Icons.Default.Subject, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.transcript)) }
                }
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

// ---------- menu ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(version: String, onQuotes: () -> Unit, onAbout: () -> Unit, onClose: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var cache by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val upToDate = stringResource(R.string.up_to_date, version)
    val failedMsg = stringResource(R.string.update_failed)
    val newVersionMsg = stringResource(R.string.update_available)
    fun refreshCache() { scope.launch(kotlinx.coroutines.Dispatchers.IO) { cache = Store.cacheSize() } }
    LaunchedEffect(Unit) { refreshCache() }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.menu), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
            MenuRow(Icons.Default.FormatQuote, stringResource(R.string.my_quotes),
                stringResource(R.string.quotes_count, Store.quotes.collectAsState().value.size), onClick = onQuotes)
            MenuRow(Icons.Default.SystemUpdate, stringResource(R.string.check_updates),
                if (checking) stringResource(R.string.checking) else update ?: stringResource(R.string.version_fmt, version),
                trailing = link?.let { l -> { TextButton({ uri.openUri(l) }) { Text(stringResource(R.string.download)) } } },
                onClick = {
                    if (checking) return@MenuRow
                    checking = true; update = null; link = null
                    scope.launch {
                        try {
                            val r = Release.check(version)
                            if (r == null) update = upToDate else { update = newVersionMsg.format(r.first); link = r.second }
                        } catch (e: Exception) { update = failedMsg }
                        checking = false
                    }
                })
            MenuRow(Icons.Default.CleaningServices, stringResource(R.string.clear_cache),
                cache?.let { stringResource(R.string.cache_size, android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, it)) } ?: "…",
                onClick = { confirm = true })
            MenuRow(Icons.Default.Info, stringResource(R.string.about), stringResource(R.string.about_short), onClick = onAbout)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.clear_cache)) },
        text = { Text(stringResource(R.string.clear_cache_info)) },
        confirmButton = { TextButton({ confirm = false; scope.launch(kotlinx.coroutines.Dispatchers.IO) { Store.clearCache(); cache = Store.cacheSize() } }) { Text(stringResource(R.string.clear)) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String,
                    trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
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


// ---------- citations et transcriptions ----------
/** Enregistre une citation de l'épisode en cours (texte vide = simple moment marqué). */
fun saveQuote(p: PlayerState, text: String, startMs: Long) {
    val url = p.mediaId.removePrefix("ep|")
    Store.addQuote(Quote(Quote.idFor(url, startMs, text), text, startMs, url, p.title, p.artist, p.feed, p.art ?: ""))
}

@Composable
fun TranscriptScreen(p: PlayerState, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val url = p.mediaId.removePrefix("ep|")
    var cues by remember { mutableStateOf<List<Cue>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (p.transcriptUrl.isEmpty()) { failed = true; return@LaunchedEffect }
        val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Store.loadTranscript(url, p.transcriptUrl, p.transcriptType) } }
        cues = r.getOrNull()?.takeIf { it.isNotEmpty() }
        failed = cues == null
    }
    val saved by Store.quotes.collectAsState()
    val list = cues
    val timed = list != null && list.firstOrNull()?.startMs?.let { it >= 0 } == true
    val current = if (timed) Transcripts.indexAt(list!!, p.position) else -1
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(current, follow) { if (follow && current >= 0) state.animateScrollToItem(maxOf(0, current - 1)) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.transcript), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (timed) IconButton({ follow = !follow }) {
                        Icon(Icons.Default.MyLocation, stringResource(R.string.follow), tint = if (follow) Violet else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    failed -> Message(stringResource(R.string.no_transcript))
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> {
                        Text(stringResource(R.string.transcript_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(list.size) { i ->
                                val c = list[i]
                                val id = Quote.idFor(url, c.startMs, c.text)
                                val on = saved.any { it.id == id }
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(when { on -> Violet.copy(alpha = 0.28f); i == current -> Violet.copy(alpha = 0.10f); else -> Color.Transparent })
                                    .clickable { if (c.startMs >= 0) { p.controller?.seekTo(c.startMs); follow = true } }.padding(8.dp),
                                    verticalAlignment = Alignment.Top) {
                                    if (c.startMs >= 0) Text(clock(c.startMs), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp).padding(top = 3.dp))
                                    Text(c.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                                        fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal)
                                    IconButton({ if (on) Store.removeQuote(id) else saveQuote(p, c.text, c.startMs) }, Modifier.size(32.dp)) {
                                        Icon(if (on) Icons.Default.FormatQuote else Icons.Outlined.FormatQuote, stringResource(R.string.quote_here), tint = if (on) Violet else MaterialTheme.colorScheme.onSurfaceVariant)
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
fun QuotesScreen(p: PlayerState, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val quotes by Store.quotes.collectAsState()
    var editing by remember { mutableStateOf<Quote?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.my_quotes), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                }
                if (quotes.isEmpty()) Message(stringResource(R.string.no_quotes))
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(quotes.size) { i ->
                        val q = quotes[i]
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                if (q.text.isNotBlank()) Text("“${q.text}”", style = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content), fontWeight = FontWeight.Medium)
                                if (q.note.isNotBlank()) Text(q.note, color = Violet, modifier = Modifier.padding(top = 6.dp))
                                Text(buildString { append(q.podcast); append(" · "); append(q.episodeTitle); if (q.startMs >= 0) append(" · ${clock(q.startMs)}") },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                                Row {
                                    IconButton({
                                        val item = Item("podcast", q.podcast, "", q.art, feed = q.feed)
                                        p.play(PlaybackService.episodeItem(item, Episode(q.episodeTitle, q.episodeUrl, "", "", q.art)), maxOf(0, q.startMs))
                                        onClose()
                                    }) { Icon(Icons.Default.PlayArrow, stringResource(R.string.play_from_here)) }
                                    IconButton({ editing = q }) { Icon(Icons.Default.Edit, stringResource(R.string.note)) }
                                    IconButton({
                                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, Quote.shareText(q)), null))
                                    }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                                    IconButton({
                                        scope.launch {
                                            runCatching { QuoteCard.share(ctx, q) }.onFailure {
                                                android.widget.Toast.makeText(ctx, R.string.share_image_failed, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) { Icon(Icons.Default.Image, stringResource(R.string.share_image)) }
                                    Spacer(Modifier.weight(1f))
                                    IconButton({ Store.removeQuote(q.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
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
            title = { Text(stringResource(R.string.note)) },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ Store.setNote(q.id, text.trim()); editing = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
