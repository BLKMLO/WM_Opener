package com.wmopener

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class OpenerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private fun fg(opened: Int) = if (Build.VERSION.SDK_INT >= 29)
        ForegroundInfo(Notifs.ID_RUN, Notifs.running(applicationContext, opened), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else ForegroundInfo(Notifs.ID_RUN, Notifs.running(applicationContext, opened))

    override suspend fun getForegroundInfo(): ForegroundInfo = fg(0)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (!AutoOpener.busy.compareAndSet(false, true)) {
            prefs.addLog("[auto] Ignoré : une ouverture est déjà en cours.")
            return Result.success()
        }
        try {
            // Notification "en cours" non effaçable pendant tout le passage. Android peut
            // refuser le premier plan depuis l'arrière-plan (sans exemption batterie) :
            // l'ouverture continue alors sans cette notification.
            // Désactivable : sans service de premier plan, le passage tourne comme une tâche
            // WorkManager ordinaire (Android peut alors l'interrompre plus facilement).
            val foreground = prefs.notifRun && try { setForeground(fg(0)); true } catch (_: Exception) { false }
            if (foreground) Notifs.cancelStatus(ctx)

            val report = withContext(Dispatchers.Main) {
                AutoOpener.runHeadless(ctx) { n ->
                    // Mise à jour de la notification du service (même identifiant).
                    if (foreground) runCatching {
                        androidx.core.app.NotificationManagerCompat.from(ctx).notify(Notifs.ID_RUN, Notifs.running(ctx, n))
                    }
                }
            }
            val summary = AutoOpener.record(ctx, report, "auto")

            val opened = report.optInt("opened")
            when {
                opened > 0 -> Notifs.result(ctx, summary.substringBefore(','), summary)
                report.optString("reason") == "login" && !prefs.sessionAlertSent -> {
                    prefs.sessionAlertSent = true
                    Notifs.result(ctx, "Session expirée", "Ouvrez WM Opener et reconnectez-vous pour reprendre l'ouverture automatique.")
                }
            }
        } finally {
            AutoOpener.busy.set(false)
            Notifs.showStatus(ctx)
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "wm_auto_open"
        private const val ONCE = "wm_open_once"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context, replace: Boolean = false) {
            val prefs = Prefs(context)
            val wm = WorkManager.getInstance(context)
            if (!prefs.autoEnabled) {
                wm.cancelUniqueWork(PERIODIC); Notifs.cancelStatus(context); return
            }
            val req = PeriodicWorkRequestBuilder<OpenerWorker>(prefs.intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
            Notifs.showStatus(context)
        }

        /** Passage immédiat ; un second appui pendant l'exécution est ignoré. */
        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OpenerWorker>().setConstraints(constraints).build()
            )
        }

        fun periodicName() = PERIODIC
        fun onceName() = ONCE
    }
}
