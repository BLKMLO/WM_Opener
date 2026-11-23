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

    /** Sous 15 min (minimum des tâches périodiques), les passages sont enchaînés un à un. */
    var intervalMinutes: Long
        get() = sp.getLong("interval", 30L)
        set(v) = sp.edit().putLong("interval", v.coerceIn(10L, 24 * 60L)).apply()

    var pullsUrl: String
        get() = sp.getString("url", DEFAULT_URL)!!.takeIf(::isAllowedUrl) ?: DEFAULT_URL
        set(v) { if (isAllowedUrl(v)) sp.edit().putString("url", v).apply() }

    var openRegex: String
        get() = sp.getString("open", DEFAULT_OPEN)!!
        set(v) = sp.edit().putString("open", v).apply()

    var followRegex: String
        get() = sp.getString("follow", DEFAULT_FOLLOW)!!.let { if (it == OLD_FOLLOW_1_0) DEFAULT_FOLLOW else it }
        set(v) = sp.edit().putString("follow", v).apply()

    var excludeRegex: String
        get() = sp.getString("exclude", DEFAULT_EXCLUDE)!!.let { if (it == OLD_EXCLUDE_1_0) DEFAULT_EXCLUDE else it }
        set(v) = sp.edit().putString("exclude", v).apply()

    var maxPacks: Int
        get() = sp.getInt("max", 10)
        set(v) = sp.edit().putInt("max", v.coerceIn(1, 50)).apply()

    /** Notifications, réglables séparément ; le mode auto tourne même si tout est coupé. */
    var notifStatus: Boolean
        get() = sp.getBoolean("n_status", true)
        set(v) = sp.edit().putBoolean("n_status", v).apply()
    var notifRun: Boolean
        get() = sp.getBoolean("n_run", true)
        set(v) = sp.edit().putBoolean("n_run", v).apply()
    var notifResult: Boolean
        get() = sp.getBoolean("n_result", true)
        set(v) = sp.edit().putBoolean("n_result", v).apply()

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
        // Les boutons ronds de l'écran « Carte i / n » n'ont pas de texte : le script les nomme
        // d'après leur icône Lucide, ex. « [icône chevron-right] ».
        const val DEFAULT_FOLLOW = "^(tout révéler|révéler|reveal|continuer|suivant|passer|skip|ok|terminer)\\b" +
            "|^\\[icône (chevron-right|chevrons-right|arrow-right|move-right|skip-forward|step-forward|fast-forward|check)\\]"
        /** Valeur par défaut de la 1.0.x, migrée automatiquement si elle a été enregistrée telle quelle. */
        private const val OLD_FOLLOW_1_0 = "^(tout révéler|révéler|reveal|continuer|suivant|passer|skip|ok|terminer)\\b"
        // Sécurité : jamais d'achat, de monnaie premium ni de publicité.
        // « favori » : verrouille la carte (ni défausse, ni vente, ni échange).
        const val DEFAULT_EXCLUDE = "achet|acheter|buy|pay|payer|€|\\$|gemme|diamant|boutique|shop|premium|pub|vidéo|video|annonce|regarder|watch|favori|défausse|vendre|vente"
        private const val OLD_EXCLUDE_1_0 = "achet|acheter|buy|pay|payer|€|\\$|gemme|diamant|boutique|shop|premium|pub|vidéo|video|annonce|regarder|watch"
    }
}
