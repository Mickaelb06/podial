package app.podial

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.util.Calendar

/** Réglage du réveil. [days] : un bit par jour, lundi = bit 0 … dimanche = bit 6 ; 0 = une seule fois. */
data class AlarmConfig(val enabled: Boolean = false, val hour: Int = 7, val minute: Int = 0, val days: Int = 0, val radio: Item? = null) {
    fun toJson() = JSONObject().put("enabled", enabled).put("hour", hour).put("minute", minute).put("days", days)
        .apply { radio?.let { put("radio", it.toJson()) } }

    companion object {
        fun fromJson(j: JSONObject) = AlarmConfig(j.optBoolean("enabled"), j.optInt("hour", 7), j.optInt("minute", 0), j.optInt("days", 0),
            j.optJSONObject("radio")?.let { Item.fromJson(it) })
    }
}

/** Réveil : une alarme exacte (AlarmManager.setAlarmClock, sans permission spéciale), qui lance la radio choisie en fondu. */
object Alarm {
    const val ACTION_FIRE = "app.podial.ALARM_FIRE"
    const val ACTION_STOP = "app.podial.ALARM_STOP"
    const val ACTION_SNOOZE = "app.podial.ALARM_SNOOZE"
    const val SNOOZE_MIN = 10
    private const val CHANNEL = "alarm"
    private const val NOTIF_ID = 4201

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("podial_alarm", Context.MODE_PRIVATE)

    fun load(ctx: Context): AlarmConfig = runCatching { AlarmConfig.fromJson(JSONObject(prefs(ctx).getString("cfg", "{}") ?: "{}")) }.getOrDefault(AlarmConfig())

    fun save(ctx: Context, cfg: AlarmConfig) {
        prefs(ctx).edit().putString("cfg", cfg.toJson().toString()).putLong("snooze", 0L).apply()
        schedule(ctx)
    }

    /** Prochain déclenchement strictement après [now] (millisecondes), ou null si le réveil est inactif. */
    fun nextTrigger(cfg: AlarmConfig, now: Long): Long? {
        if (!cfg.enabled) return null
        val c = Calendar.getInstance().apply { timeInMillis = now }
        for (i in 0..7) {
            val t = (c.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, i); set(Calendar.HOUR_OF_DAY, cfg.hour); set(Calendar.MINUTE, cfg.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val bit = (t.get(Calendar.DAY_OF_WEEK) + 5) % 7   // lundi = 0
            if (t.timeInMillis > now && (cfg.days == 0 || cfg.days and (1 shl bit) != 0)) return t.timeInMillis
        }
        return null
    }

    private fun pending(ctx: Context, action: String, flags: Int = 0) =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, AlarmReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or flags)

    /** (Re)programme la prochaine sonnerie ; à appeler à chaque changement, au démarrage de l'appareil et de l'appli. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fire = pending(ctx, ACTION_FIRE, PendingIntent.FLAG_UPDATE_CURRENT)
        val now = System.currentTimeMillis()
        val snooze = prefs(ctx).getLong("snooze", 0L).takeIf { it > now }
        val next = listOfNotNull(nextTrigger(load(ctx), now), snooze).minOrNull()
        if (next == null) { am.cancel(fire); return }
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(next, show), fire)
    }

    fun describe(ctx: Context, cfg: AlarmConfig): String {
        if (!cfg.enabled) return ctx.getString(R.string.alarm_off)
        val t = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, cfg.hour); set(Calendar.MINUTE, cfg.minute) }.time)
        return t + " · " + (cfg.radio?.name ?: ctx.getString(R.string.alarm_ringtone))
    }

    // ---- sonnerie ----

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ringtone: MediaPlayer? = null
    var ringing = false
        private set
    private var elapsed = 0

    private val ramp = object : Runnable {
        override fun run() {
            val c = controller ?: return
            elapsed++
            c.volume = (elapsed / 60f).coerceAtMost(1f)
            if (elapsed < 60) handler.postDelayed(this, 1000)
        }
    }
    private val giveUp = Runnable { ctxRef?.let { stop(it) } }
    private var ctxRef: Context? = null

    fun fire(ctx: Context) {
        val app = ctx.applicationContext
        ctxRef = app
        Store.init(app)
        val cfg = load(app)
        // une alarme « une seule fois » s'éteint ; les autres sont reprogrammées
        if (cfg.days == 0 && prefs(app).getLong("snooze", 0L) == 0L) save(app, cfg.copy(enabled = false)) else { prefs(app).edit().putLong("snooze", 0L).apply(); schedule(app) }
        stopSound(app)
        ringing = true
        notify(app, cfg)
        raiseMediaVolume(app)
        val radio = cfg.radio
        if (radio == null) { ring(app); handler.postDelayed(giveUp, 30 * 60_000L); return }
        val f = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null || !ringing) { if (ringing) ring(app); return@addListener }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { if (ringing && ringtone == null) ring(app) }
            })
            c.volume = 0f
            c.setMediaItem(PlaybackService.radioItem(radio)); c.prepare(); c.play()
            elapsed = 0
            handler.postDelayed(ramp, 1000)
            // la radio ne démarre pas (réseau coupé, flux mort) : la sonnerie du téléphone prend le relais
            handler.postDelayed({ if (ringing && controller?.isPlaying != true && ringtone == null) ring(app) }, 25_000L)
        }, ContextCompat.getMainExecutor(app))
        handler.postDelayed(giveUp, 45 * 60_000L)
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        ringing = false
        handler.removeCallbacks(ramp); handler.removeCallbacks(giveUp)
        stopSound(app)
        controller?.let { it.pause(); it.volume = 1f }
        future?.let { MediaController.releaseFuture(it) }
        future = null; controller = null
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
    }

    fun snooze(ctx: Context) {
        stop(ctx)
        prefs(ctx).edit().putLong("snooze", System.currentTimeMillis() + SNOOZE_MIN * 60_000L).apply()
        schedule(ctx)
    }

    private fun ring(ctx: Context) {
        if (ringtone != null) return
        controller?.pause()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri); isLooping = true; prepare(); start()
            }
        }.getOrNull()
    }

    private fun stopSound(ctx: Context) {
        ringtone?.runCatching { stop(); release() }
        ringtone = null
    }

    /** La radio joue sur le volume « média » : s'il est quasi muet, l'alarme ne servirait à rien. */
    private fun raiseMediaVolume(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < max * 0.4) am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.5).toInt(), 0)
    }

    private fun notify(ctx: Context, cfg: AlarmConfig) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.alarm_title), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val screen = PendingIntent.getActivity(ctx, 1, Intent(ctx, AlarmActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(ctx.getString(R.string.alarm_title))
            .setContentText(cfg.radio?.name ?: ctx.getString(R.string.alarm_ringtone))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setAutoCancel(false)
            .setContentIntent(screen).setFullScreenIntent(screen, true)
            .addAction(0, ctx.getString(R.string.alarm_snooze, SNOOZE_MIN), pending(ctx, ACTION_SNOOZE, PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, ctx.getString(R.string.alarm_stop), pending(ctx, ACTION_STOP, PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Alarm.ACTION_FIRE -> {
                Alarm.fire(ctx)
                // en plus de la notification plein écran : écran de réveil direct quand le système l'autorise
                runCatching { ctx.startActivity(Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            Alarm.ACTION_STOP -> Alarm.stop(ctx)
            Alarm.ACTION_SNOOZE -> Alarm.snooze(ctx)
        }
    }
}

/** Reprogramme l'alarme après un redémarrage, une mise à jour de l'appli ou un changement d'heure. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Alarm.schedule(ctx) }
}
