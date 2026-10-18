package app.podial

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Vérifie toutes les heures s'il y a de nouveaux épisodes dans les podcasts suivis, et prévient par une notification. */
class CheckWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(ctx)
        val before = Store.newCounts.value.values.sum()
        val names = mutableListOf<String>()
        for (p in Store.podcasts.value) {
            val eps = try { Api.fetchFeed(p.feed).second } catch (e: Exception) { continue }
            if (Store.recordCheck(p.feed, eps) > 0) names += p.name
        }
        val total = Store.newCounts.value.values.sum()
        if (total > before) notify(total, names)
        PlaybackService.libraryChanged()
        return Result.success()
    }

    private fun notify(total: Int, names: List<String>) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.new_episodes_title), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ctx.getString(R.string.new_episodes_title))
            .setContentText(ctx.getString(R.string.new_episodes_fmt, total, names.take(3).joinToString(", ")))
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(1, n)
    }

    companion object {
        private const val CHANNEL = "new_episodes"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("check-new-episodes", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
