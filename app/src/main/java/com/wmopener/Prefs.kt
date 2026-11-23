package com.wmopener

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Réglages et journal technique (SharedPreferences privées à l'appli). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("wm_opener", Context.MODE_PRIVATE)

    var autoEnabled: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** WorkManager impose 15 min minimum entre deux passages périodiques. */
    var intervalMinutes: Long
        get() = sp.getLong("interval", 30L)
        set(v) = sp.edit().putLong("interval", v.coerceIn(15L, 24 * 60L)).apply()

    var pullsUrl: String
        get() = sp.getString("url", DEFAULT_URL)!!.takeIf(::isAllowedUrl) ?: DEFAULT_URL
        set(v) { if (isAllowedUrl(v)) sp.edit().putString("url", v).apply() }

    var openRegex: String
        get() = sp.getString("open", DEFAULT_OPEN)!!
        set(v) = sp.edit().putString("open", v).apply()

    var followRegex: String
        get() = sp.getString("follow", DEFAULT_FOLLOW)!!
        set(v) = sp.edit().putString("follow", v).apply()

    var excludeRegex: String
        get() = sp.getString("exclude", DEFAULT_EXCLUDE)!!
        set(v) = sp.edit().putString("exclude", v).apply()

    var maxPacks: Int
        get() = sp.getInt("max", 10)
        set(v) = sp.edit().putInt("max", v.coerceIn(1, 50)).apply()

    var lastRunAt: Long
        get() = sp.getLong("last_run", 0L)
        set(v) = sp.edit().putLong("last_run", v).apply()

    var lastRunSummary: String
        get() = sp.getString("last_summary", "")!!
        set(v) = sp.edit().putString("last_summary", v).apply()

    /** Évite de notifier "session expirée" toutes les 15 minutes. */
    var sessionAlertSent: Boolean
        get() = sp.getBoolean("session_alert", false)
        set(v) = sp.edit().putBoolean("session_alert", v).apply()

    val log: String get() = sp.getString("log", "")!!

    @Synchronized
    fun addLog(line: String) {
        val ts = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
        sp.edit().putString("log", ("$ts  $line\n" + log).take(20_000)).apply()
    }

    fun clearLog() = sp.edit().remove("log").apply()

    fun resetPatterns() {
        openRegex = DEFAULT_OPEN; followRegex = DEFAULT_FOLLOW; excludeRegex = DEFAULT_EXCLUDE
    }

    companion object {
        const val DEFAULT_URL = "https://www.wiki-masters.com/pulls"
        val SITE_HOSTS = setOf("wiki-masters.com", "www.wiki-masters.com")
        /** Hôtes autorisés à s'afficher dans l'appli (Turnstile = captcha de connexion). */
        val NAV_HOSTS = SITE_HOSTS + "challenges.cloudflare.com"

        fun isAllowedUrl(url: String): Boolean = try {
            val u = Uri.parse(url.trim())
            u.scheme == "https" && u.host in SITE_HOSTS
        } catch (_: Exception) { false }

        // Libellés supposés : la page /pulls n'est visible qu'une fois connecté.
        // Le bouton Diagnostic permet de lire les vrais libellés.
        const val DEFAULT_OPEN = "^(ouvrir|ouvre|open|tirer|tirage|pull)\\b"
        const val DEFAULT_FOLLOW = "^(tout révéler|révéler|reveal|continuer|suivant|passer|skip|ok|terminer)\\b"
        // Sécurité : jamais d'achat, de monnaie premium ni de publicité.
        const val DEFAULT_EXCLUDE = "achet|acheter|buy|pay|payer|€|\\$|gemme|diamant|boutique|shop|premium|pub|vidéo|video|annonce|regarder|watch"
    }
}
