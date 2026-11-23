package com.wmopener

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var autoBtn: Button
    private var pendingAfterLoad: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0C0D0C.toInt())
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 13f
            setOnClickListener { onClick() }
            bar.addView(this, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        autoBtn = btn("") { toggleAuto() }
        btn("Ouvrir maintenant") { runVisible("open") }
        btn("Diagnostic") { runVisible("diagnose") }
        btn("Test arrière-plan") { OpenerWorker.runOnce(this); toast("Lancé, résultat dans le Journal.") }
        btn("Journal") { showLog() }
        btn("Réglages") { showSettings() }
        btn("Autorisations") { askPermissions(force = true) }
        root.addView(HorizontalScrollView(this).apply { addView(bar) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        web = WebView(this)
        AutoOpener.configure(web)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                pendingAfterLoad?.let { mode -> pendingAfterLoad = null; inject(mode) }
            }
        }
        web.addJavascriptInterface(AutoOpener.Bridge { onVisibleReport(it) }, "WMBridge")
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        refreshAutoBtn()
        web.loadUrl(prefs.pullsUrl)
        askPermissions(force = false)
    }

    private fun inject(mode: String) {
        web.evaluateJavascript(AutoOpener.buildScript(this, prefs, mode), null)
        toast(if (mode == "diagnose") "Lecture des boutons…" else "Ouverture en cours…")
    }

    private fun runVisible(mode: String) {
        val target = Uri.parse(prefs.pullsUrl)
        val current = web.url?.let { Uri.parse(it) }
        if (current?.host == target.host && current?.path == target.path) inject(mode)
        else { pendingAfterLoad = mode; web.loadUrl(prefs.pullsUrl) }
    }

    private fun onVisibleReport(r: JSONObject) {
        val summary = AutoOpener.summarize(r)
        prefs.addLog("[manuel] $summary")
        val buttons = r.optJSONArray("buttons")
        val body = StringBuilder(summary).append("\n\nPage : ").append(r.optString("url"))
        if (buttons != null && buttons.length() > 0) {
            body.append("\n\nBoutons visibles :\n")
            for (i in 0 until buttons.length()) body.append("• ").append(buttons.optString(i)).append('\n')
        }
        AlertDialog.Builder(this)
            .setTitle(if (r.optString("mode") == "diagnose") "Diagnostic" else "Résultat")
            .setMessage(body.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Copier") { _, _ -> copy(body.toString()) }
            .show()
    }

    private fun toggleAuto() {
        prefs.autoEnabled = !prefs.autoEnabled
        OpenerWorker.schedule(this)
        prefs.addLog(if (prefs.autoEnabled) "Auto activé (toutes les ${prefs.intervalMinutes} min)" else "Auto désactivé")
        refreshAutoBtn()
    }

    private fun refreshAutoBtn() {
        autoBtn.text = if (prefs.autoEnabled) "Auto : ON (${prefs.intervalMinutes} min)" else "Auto : OFF"
    }

    private fun showLog() {
        val tv = TextView(this).apply {
            text = prefs.log.ifEmpty { "(vide)" }; setTextIsSelectable(true); setPadding(40, 20, 40, 20); textSize = 12f
        }
        AlertDialog.Builder(this).setTitle("Journal")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null)
            .setNeutralButton("Effacer") { _, _ -> prefs.clearLog() }
            .setNegativeButton("Copier") { _, _ -> copy(prefs.log) }
            .show()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 10) }
        fun field(label: String, value: String, numeric: Boolean = false): EditText {
            box.addView(TextView(this).apply { text = label; setPadding(0, 20, 0, 0) })
            return EditText(this).apply {
                setText(value)
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                box.addView(this)
            }
        }
        val interval = field("Intervalle en minutes (minimum 15, imposé par Android)", prefs.intervalMinutes.toString(), true)
        val max = field("Nombre max de paquets par passage", prefs.maxPacks.toString(), true)
        val url = field("Page des paquets", prefs.pullsUrl)
        val open = field("Regex bouton d'ouverture", prefs.openRegex)
        val follow = field("Regex boutons de suite (révéler, continuer…)", prefs.followRegex)
        val excl = field("Regex à ne JAMAIS cliquer (achats, pubs…)", prefs.excludeRegex)

        AlertDialog.Builder(this).setTitle("Réglages")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Enregistrer") { _, _ ->
                prefs.intervalMinutes = interval.text.toString().toLongOrNull() ?: 30L
                prefs.maxPacks = max.text.toString().toIntOrNull() ?: 10
                prefs.pullsUrl = url.text.toString().trim().ifEmpty { Prefs.DEFAULT_URL }
                prefs.openRegex = open.text.toString()
                prefs.followRegex = follow.text.toString()
                prefs.excludeRegex = excl.text.toString()
                OpenerWorker.schedule(this)
                refreshAutoBtn()
            }
            .setNeutralButton("Valeurs par défaut") { _, _ -> prefs.resetPatterns(); toast("Expressions réinitialisées") }
            .setNegativeButton("Annuler", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun askPermissions(force: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (!force) return

        val pm = getSystemService(PowerManager::class.java)
        val steps = mutableListOf<Pair<String, Intent>>()
        if (!pm.isIgnoringBatteryOptimizations(packageName)) steps += "Ignorer l'optimisation de batterie (sinon Android retarde fortement les passages)" to
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (!Settings.canDrawOverlays(this)) steps += "Superposition (recommandé : permet à la page invisible de s'animer normalement en arrière-plan)" to
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (steps.isEmpty()) { toast("Toutes les autorisations sont accordées."); return }
        AlertDialog.Builder(this).setTitle("Autorisations")
            .setItems(steps.map { it.first }.toTypedArray()) { _, i -> startActivity(steps[i].second) }
            .setNegativeButton("Fermer", null).show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("WM Opener", text))
        toast("Copié")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
