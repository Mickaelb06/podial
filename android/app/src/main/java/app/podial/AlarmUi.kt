package app.podial

import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** Réglage du réveil : activé, heure, jours, radio. */
@Composable
fun AlarmDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(Alarm.load(ctx)) }
    var picking by remember { mutableStateOf(false) }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, cfg.hour); set(Calendar.MINUTE, cfg.minute) }.time)
    if (picking) AlarmPicker({ cfg = cfg.copy(source = it); picking = false }) { picking = false }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.alarm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.alarm_enabled), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Switch(cfg.enabled, { cfg = cfg.copy(enabled = it) })
                }
                OutlinedButton({
                    TimePickerDialog(ctx, { _, h, m -> cfg = cfg.copy(hour = h, minute = m) }, cfg.hour, cfg.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
                }, Modifier.fillMaxWidth()) { Text(time, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    DayOfWeek.values().forEachIndexed { i, d ->
                        val on = cfg.days and (1 shl i) != 0
                        FilterChip(on, { cfg = cfg.copy(days = cfg.days xor (1 shl i)) },
                            { Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.fillMaxWidth(), maxLines = 1) },
                            Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors())
                    }
                }
                Text(stringResource(if (cfg.days == 0) R.string.alarm_once else R.string.alarm_repeat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.alarm_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.alarm_radio), fontWeight = FontWeight.SemiBold)
                OutlinedButton({ picking = true }, Modifier.fillMaxWidth()) {
                    Text(cfg.source?.let { (if (it.kind == "podcast") "🎙 " else "📻 ") + it.name } ?: stringResource(R.string.alarm_choose), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = { TextButton({ Alarm.save(ctx, cfg); onClose() }, enabled = !cfg.enabled || cfg.source != null) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.cancel)) } },
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
