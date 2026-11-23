package com.wmopener

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Réglages et journal, stockés dans les SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wm_opener", Context.MODE_PRIVATE)

    var autoEnabled: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** Intervalle entre deux tentatives. WorkManager impose un minimum de 15 min. */
    var intervalMinutes: Long
        get() = sp.getLong("interval", 30L)
        set(v) = sp.edit().putLong("interval", v.coerceAtLeast(15L)).apply()

    var pullsUrl: String
        get() = sp.getString("url", DEFAULT_URL)!!
        set(v) = sp.edit().putString("url", v).apply()

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

    var log: String
        get() = sp.getString("log", "")!!
        private set(v) = sp.edit().putString("log", v).apply()

    fun addLog(line: String) {
        val ts = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
        log = ("$ts  $line\n" + log).take(20_000)
    }

    fun clearLog() { log = "" }

    fun resetPatterns() {
        openRegex = DEFAULT_OPEN; followRegex = DEFAULT_FOLLOW; excludeRegex = DEFAULT_EXCLUDE
    }

    companion object {
        const val DEFAULT_URL = "https://www.wiki-masters.com/pulls"
        // Valeurs par défaut "à l'aveugle" : les libellés exacts des boutons de la page
        // /pulls ne sont pas connus (page réservée aux comptes connectés). Utilisez
        // le bouton Diagnostic pour les lire puis ajustez ces expressions.
        const val DEFAULT_OPEN = "^(ouvrir|ouvre|open|tirer|tirage|pull)\\b"
        const val DEFAULT_FOLLOW = "^(tout révéler|révéler|reveal|continuer|suivant|passer|skip|ok|terminer)\\b"
        // Sécurité : ne jamais cliquer sur ce qui ressemble à un achat ou une pub.
        const val DEFAULT_EXCLUDE = "achet|acheter|buy|pay|payer|€|\\$|gemme|diamant|boutique|shop|premium|pub|vidéo|video|annonce|regarder|watch"
    }
}
