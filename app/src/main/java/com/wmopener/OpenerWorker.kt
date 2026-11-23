package com.wmopener

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class OpenerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val report = withContext(Dispatchers.Main) { AutoOpener.runHeadless(applicationContext) }
        val summary = AutoOpener.summarize(report)
        prefs.addLog("[auto] $summary")

        val opened = report.optInt("opened")
        if (opened > 0 || report.optString("reason") == "login") {
            notify(applicationContext, if (opened > 0) "$opened paquet(s) ouvert(s)" else "WM Opener", summary)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "wm_auto_open"
        private const val CHANNEL = "wm_opener"

        fun schedule(context: Context) {
            val prefs = Prefs(context)
            val wm = WorkManager.getInstance(context)
            if (!prefs.autoEnabled) { wm.cancelUniqueWork(UNIQUE); return }
            val req = PeriodicWorkRequestBuilder<OpenerWorker>(prefs.intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<OpenerWorker>().build())
        }

        fun notify(context: Context, title: String, text: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
            ) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ouverture des paquets", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 100000).toInt(), n)
        }
    }
}
