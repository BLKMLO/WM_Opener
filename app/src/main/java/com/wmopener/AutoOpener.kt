package com.wmopener

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Pilote une WebView sur la page des paquets et y exécute assets/opener.js. */
object AutoOpener {

    /** Une seule ouverture à la fois (arrière-plan et manuel confondus). */
    val busy = AtomicBoolean(false)

    /** WorkManager arrête un passage au bout de 10 min ; 5 cartes à faire défiler par paquet. */
    private const val TIMEOUT_MS = 9 * 60 * 1000L

    fun buildScript(context: Context, prefs: Prefs, mode: String): String {
        val cfg = JSONObject()
            .put("open", prefs.openRegex)
            .put("follow", prefs.followRegex)
            .put("exclude", prefs.excludeRegex)
            .put("maxPacks", prefs.maxPacks)
            .put("mode", mode)
        val js = context.assets.open("opener.js").bufferedReader().use { it.readText() }
        return js.replace("__WM_CFG__", cfg.toString())
    }

    fun isSiteUrl(url: String?): Boolean =
        url != null && Uri.parse(url).let { it.scheme == "https" && it.host in Prefs.SITE_HOSTS }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * Client qui garde la WebView sur le site : tout autre domaine s'ouvre dans le
     * navigateur du téléphone (le pont JavaScript n'est jamais exposé à un site tiers).
     */
    open class SiteClient(private val ctx: Context, private val external: Boolean) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.scheme == "https" && u.host in Prefs.NAV_HOSTS) return false
            if (external && request.isForMainFrame && (u.scheme == "https" || u.scheme == "http" || u.scheme == "mailto")) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) { }
            }
            return true
        }
    }

    /**
     * Pont JS → appli. Les messages ne sont acceptés que si la WebView est
     * actuellement sur wiki-masters.com (vérifié sur le thread principal).
     */
    class Bridge(
        private val webView: () -> WebView?,
        private val onProgress: (JSONObject) -> Unit = {},
        private val onReport: (JSONObject) -> Unit,
    ) {
        private val main = Handler(Looper.getMainLooper())

        private fun dispatch(json: String, cb: (JSONObject) -> Unit) {
            main.post {
                if (!isSiteUrl(webView()?.url)) return@post
                val obj = try { JSONObject(json) } catch (_: Exception) { return@post }
                cb(obj)
            }
        }

        @JavascriptInterface fun report(json: String) = dispatch(json, onReport)
        @JavascriptInterface fun progress(json: String) = dispatch(json, onProgress)
    }

    /** Ouverture en arrière-plan. À appeler sur le thread principal. */
    suspend fun runHeadless(context: Context, onProgress: (Int) -> Unit = {}): JSONObject {
        val prefs = Prefs(context)
        val webView = WebView(context)
        configure(webView)
        val done = CompletableDeferred<JSONObject>()
        var last: JSONObject? = null
        webView.addJavascriptInterface(
            Bridge({ webView }, { last = it; onProgress(it.optInt("opened")) }) { done.complete(it) }, "WMBridge"
        )

        // WebView sans fenêtre : on lui donne une taille d'écran de téléphone pour la mise en page.
        // (La superposition, essayée en 1.0/1.1, est bloquée par Android pour les applis
        // installées hors Play Store, et l'ouverture fonctionne sans elle.)
        run {
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, 1080, 2340)
        }

        val script = buildScript(context, prefs, "open")
        var injected = false
        webView.webViewClient = object : SiteClient(context, external = false) {
            override fun onPageFinished(view: WebView, url: String) {
                if (injected) return
                if (!isSiteUrl(url)) return
                injected = true
                view.evaluateJavascript(script, null)
            }
        }
        webView.loadUrl(prefs.pullsUrl)

        val result = withTimeoutOrNull(TIMEOUT_MS) { done.await() }
            ?: last?.takeIf { it.optInt("opened") > 0 }?.put("ok", true)?.put("partial", true)
            ?: JSONObject().put("ok", false).put("reason", "timeout")

        CookieManager.getInstance().flush()
        webView.stopLoading()
        webView.removeJavascriptInterface("WMBridge")
        webView.destroy()
        return result
    }

    /** Enregistre le résultat (historique, journal, état) et renvoie un résumé lisible. */
    fun record(context: Context, r: JSONObject, source: String): String {
        val prefs = Prefs(context)
        val summary = summarize(r)
        prefs.addLog("[$source] $summary" + if (r.has("raf")) " (animations ${if (r.optBoolean("raf")) "actives" else "suspendues"})" else "")
        // Déroulé détaillé (le journal est affiché du plus récent au plus ancien).
        r.optJSONArray("trace")?.let { t ->
            val lines = (0 until t.length()).map { "    · " + t.optString(it) }
            if (lines.isNotEmpty()) prefs.addLog("[$source] déroulé :\n" + lines.joinToString("\n"))
        }
        if (source == "diagnostic" || r.optString("mode") == "diagnose") return summary
        prefs.lastRunAt = System.currentTimeMillis()
        prefs.lastRunSummary = summary
        if (r.optBoolean("ok")) prefs.sessionAlertSent = false
        val n = r.optInt("opened")
        if (n > 0) History.add(context, Entry(System.currentTimeMillis(), source, n, History.parseCards(r)))
        return summary
    }

    fun summarize(r: JSONObject): String = when {
        !r.optBoolean("ok") -> when (r.optString("reason")) {
            "login" -> "Session expirée, reconnectez-vous."
            "timeout" -> "La page n'a pas répondu à temps."
            "bad_regex" -> "Expression de détection invalide : ${r.optString("error")}"
            else -> "Échec : ${r.optString("error").ifEmpty { r.optString("reason") }}"
        }
        r.optString("mode") == "diagnose" -> "Diagnostic : ${r.optJSONArray("buttons")?.length() ?: 0} bouton(s) visibles."
        r.optInt("opened") == 0 && r.optInt("noEffect") > 0 ->
            "Clic sur « Ouvrir » sans effet." + if (!r.optBoolean("raf", true))
                " Animations suspendues en arrière-plan." else " Voir le journal."
        r.optInt("opened") == 0 -> "Rien à ouvrir."
        else -> {
            val n = r.optInt("opened"); val c = r.optJSONArray("cards")?.length() ?: 0
            "$n paquet${if (n > 1) "s" else ""} ouvert${if (n > 1) "s" else ""}" +
                (if (c > 0) ", $c carte${if (c > 1) "s" else ""} détectée${if (c > 1) "s" else ""}" else "") +
                if (r.optBoolean("partial")) " (passage interrompu : délai dépassé)." else "."
        }
    }
}
