package com.wmopener

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Charge la page des paquets dans une WebView (invisible si lancée en arrière-plan)
 * et y exécute assets/opener.js. Doit être appelée sur le thread principal.
 */
object AutoOpener {

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

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    /** Exécution en arrière-plan : WebView créée pour l'occasion puis détruite. */
    suspend fun runHeadless(context: Context, mode: String = "open"): JSONObject {
        val prefs = Prefs(context)
        val bridge = Bridge()
        val webView = WebView(context)
        configure(webView)
        webView.addJavascriptInterface(bridge, "WMBridge")

        // Sans fenêtre, Chromium peut suspendre les animations (requestAnimationFrame).
        // Si l'autorisation "superposition" est accordée, on attache la WebView à une
        // fenêtre transparente et non tactile pour qu'elle soit rendue normalement.
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var attached = false
        if (Settings.canDrawOverlays(context)) {
            try {
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.alpha = 0.0f
                wm.addView(webView, lp)
                attached = true
            } catch (_: Exception) { }
        }
        if (!attached) {
            val w = 1080; val h = 2340
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, w, h)
        }

        val script = buildScript(context, prefs, mode)
        var injected = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!injected) {
                    injected = true
                    view.evaluateJavascript(script, null)
                }
            }
        }
        webView.loadUrl(prefs.pullsUrl)

        val result = withTimeoutOrNull(4 * 60 * 1000L) { bridge.result.await() }
            ?: JSONObject().put("ok", false).put("reason", "timeout")

        CookieManager.getInstance().flush()
        if (attached) try { wm.removeView(webView) } catch (_: Exception) { }
        webView.stopLoading()
        webView.destroy()
        return result.put("overlay", attached)
    }

    class Bridge(private val onReport: ((JSONObject) -> Unit)? = null) {
        val result = CompletableDeferred<JSONObject>()
        private val main = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun report(json: String) {
            val obj = try { JSONObject(json) } catch (e: Exception) {
                JSONObject().put("ok", false).put("reason", "bad_json")
            }
            result.complete(obj)
            onReport?.let { cb -> main.post { cb(obj) } }
        }
    }

    /** Résumé lisible d'un rapport, pour le journal et les notifications. */
    fun summarize(r: JSONObject): String = when {
        !r.optBoolean("ok") && r.optString("reason") == "login" -> "Session expirée : reconnectez-vous dans l'appli."
        !r.optBoolean("ok") -> "Échec (${r.optString("reason")}) ${r.optString("error")}".trim()
        r.optString("mode") == "diagnose" -> "Diagnostic : ${r.optJSONArray("buttons")?.length() ?: 0} bouton(s) visibles."
        else -> {
            val n = r.optInt("opened")
            if (n == 0) "Aucun paquet disponible (aucun bouton d'ouverture trouvé)."
            else "$n paquet(s) ouvert(s). Clics : ${r.optJSONArray("clicks")}"
        }
    }
}
