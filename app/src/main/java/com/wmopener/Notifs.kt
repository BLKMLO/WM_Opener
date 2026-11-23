package com.wmopener

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifs {
    const val CH_STATUS = "status"   // permanente tant que l'auto est actif
    const val CH_RUN = "run"         // pendant une ouverture (service de premier plan)
    const val CH_RESULT = "result"   // paquets ouverts, session expirée

    const val ID_STATUS = 1
    const val ID_RUN = 2
    private const val ID_RESULT = 3

    private const val ACCENT = 0xFF34D399.toInt()

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_STATUS, "Mode automatique actif", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
            NotificationChannel(CH_RUN, "Ouverture en cours", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
            NotificationChannel(CH_RESULT, "Paquets ouverts", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, tab: String) = PendingIntent.getActivity(
        ctx, tab.hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))

    /** Notification permanente : affichée tant que le mode automatique est actif. */
    fun showStatus(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.autoEnabled || !prefs.notifStatus) { cancelStatus(ctx); return }
        if (!canPost(ctx)) return
        channels(ctx)
        val last = if (prefs.lastRunAt > 0) "Dernier passage ${hhmm(prefs.lastRunAt)} · ${prefs.lastRunSummary}" else "Premier passage à venir"
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(ACCENT)
            .setContentTitle("Ouverture auto · toutes les ${prefs.intervalMinutes} min")
            .setContentText(last)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp(ctx, MainActivity.TAB_AUTO))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_STATUS, n)
    }

    fun cancelStatus(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_STATUS)

    /** Notification du service de premier plan, non effaçable pendant l'ouverture. */
    fun running(ctx: Context, opened: Int): Notification {
        channels(ctx)
        return NotificationCompat.Builder(ctx, CH_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(ACCENT)
            .setContentTitle("Ouverture des paquets…")
            .setContentText(if (opened == 0) "Chargement de la page" else "$opened ouvert${if (opened > 1) "s" else ""} pour l'instant")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp(ctx, MainActivity.TAB_AUTO))
            .build()
    }

    fun result(ctx: Context, title: String, text: String) {
        if (!canPost(ctx) || !Prefs(ctx).notifResult) return
        channels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, MainActivity.TAB_HISTORY))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_RESULT, n)
    }
}
