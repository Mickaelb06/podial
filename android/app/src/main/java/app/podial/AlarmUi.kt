package app.podial

import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Écran affiché par-dessus l'écran verrouillé quand le réveil sonne. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Store.init(this)
        setContent {
            PodialTheme(dark = true) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                // l'alarme a été arrêtée depuis la notification : on ferme l'écran
                LaunchedEffect(Unit) { while (true) { if (!Alarm.ringing) finish(); delay(500) } }
                val cfg = remember { Alarm.load(this) }
                Column(Modifier.fillMaxSize().background(Color(0xFF0B0A12)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), color = Color.White, fontSize = 84.sp, fontWeight = FontWeight.ExtraBold)
                    Text(cfg.source?.name ?: stringResource(R.string.alarm_ringtone), color = Color.White.copy(alpha = 0.75f), fontSize = 20.sp)
                    Spacer(Modifier.height(48.dp))
                    Button({ Alarm.stop(this@AlarmActivity); finish() }, Modifier.fillMaxWidth().height(64.dp)) { Text(stringResource(R.string.alarm_stop), fontSize = 20.sp) }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton({ Alarm.snooze(this@AlarmActivity); finish() }, Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.alarm_snooze, Alarm.SNOOZE_MIN), color = Color.White)
                    }
                }
            }
        }
    }
}

/** Page « Réveil » : l'heure en grand, les jours, ce qui réveille. Chaque changement est enregistré tout de suite. */
@Composable
fun AlarmPage() {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(Alarm.load(ctx)) }
    var picking by remember { mutableStateOf(false) }
    var enableAfterPick by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    fun update(n: AlarmConfig) { cfg = n; Alarm.save(ctx, n) }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, cfg.hour); set(Calendar.MINUTE, cfg.minute) }.time)
    val next = Alarm.nextTrigger(cfg, now)
    if (timeOpen) TimeDialog(cfg.hour, cfg.minute, { h, m -> update(cfg.copy(hour = h, minute = m)); timeOpen = false }) { timeOpen = false }
    if (picking) AlarmPicker({ update(cfg.copy(source = it, enabled = cfg.enabled || enableAfterPick)); enableAfterPick = false; picking = false }) { picking = false; enableAfterPick = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.alarm_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.Start))
        Text(time, fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, color = if (cfg.enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { timeOpen = true }.padding(horizontal = 16.dp))
        Text(next?.let { val min = ((it - now) / 60_000L).toInt() + 1
            if (min >= 60) stringResource(R.string.alarm_in_hm, min / 60, min % 60) else stringResource(R.string.alarm_in_m, min) } ?: stringResource(R.string.alarm_off),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DayOfWeek.values().forEachIndexed { i, d ->
                val on = cfg.days and (1 shl i) != 0
                FilterChip(on, { update(cfg.copy(days = cfg.days xor (1 shl i))) },
                    { Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.fillMaxWidth(), maxLines = 1) }, Modifier.weight(1f))
            }
        }
        Text(stringResource(if (cfg.days == 0) R.string.alarm_once else R.string.alarm_repeat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { picking = true }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val src = cfg.source
                if (src != null) { if (src.kind == "radio") RadioLogo(null, src.art, src.name, 48) else Cover(src.art, src.name, 48) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.alarm_radio), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(src?.name ?: stringResource(R.string.alarm_choose), fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text(stringResource(R.string.alarm_change), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.alarm_enabled), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(cfg.enabled, { on ->
                    // sans radio ni podcast, on demande d'abord quoi jouer
                    if (on && cfg.source == null) { enableAfterPick = true; picking = true } else update(cfg.copy(enabled = on))
                })
            }
        }
        Text(stringResource(R.string.alarm_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Choix de l'heure : cadran aux couleurs de l'appli, avec saisie au clavier en option. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(hour: Int, minute: Int, onDone: (Int, Int) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(hour, minute, android.text.format.DateFormat.is24HourFormat(ctx))
    var keyboard by remember { mutableStateOf(false) }
    val colors = TimePickerDefaults.colors(
        clockDialSelectedContentColor = Color.White, clockDialColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        selectorColor = Brand.Violet, timeSelectorSelectedContainerColor = Brand.Violet, timeSelectorSelectedContentColor = Color.White,
        periodSelectorSelectedContainerColor = Brand.Pink.copy(alpha = 0.25f), periodSelectorSelectedContentColor = MaterialTheme.colorScheme.onSurface)
    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(32.dp),
        title = { Text(stringResource(R.string.alarm_title), fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (keyboard) TimeInput(state, colors = colors) else TimePicker(state, colors = colors)
            }
        },
        confirmButton = { TextButton({ onDone(state.hour, state.minute) }) { Text(stringResource(R.string.save), fontWeight = FontWeight.Bold) } },
        dismissButton = {
            Row {
                IconButton({ keyboard = !keyboard }) { Icon(if (keyboard) Icons.Default.Schedule else Icons.Default.Keyboard, null) }
                TextButton(onClose) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Choix de ce qui réveille : sonnerie, radio ou podcast (favoris, ou recherche dans tout l'annuaire). */
@Composable
private fun AlarmPicker(onPick: (Item) -> Unit, onClose: () -> Unit) {
    var podcast by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val radios by Store.radios.collectAsState()
    val podcasts by Store.podcasts.collectAsState()
    var found by remember { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(podcast, query) {
        if (query.isBlank()) { found = null; return@LaunchedEffect }
        delay(500)
        found = runCatching { if (podcast) Api.searchPodcasts(query) else Api.radios("", query, limit = 30, world = true) }.getOrDefault(emptyList())
    }
    val list = if (query.isBlank()) (if (podcast) podcasts else radios) else found
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.alarm_radio)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!podcast, { podcast = false }, { Text(stringResource(R.string.alarm_pick_radio)) })
                    FilterChip(podcast, { podcast = true }, { Text(stringResource(R.string.alarm_pick_podcast)) })
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(if (podcast) R.string.alarm_search_podcast else R.string.alarm_search_radio)) })
                if (query.isBlank()) Text(stringResource(R.string.alarm_favorites), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    if (list == null) item { Text("…", Modifier.padding(12.dp)) }
                    else if (list.isEmpty()) item { Text(stringResource(R.string.alarm_none), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else items(list) { PickRow(it.name, it.artist) { onPick(it) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PickRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp)) {
        Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
