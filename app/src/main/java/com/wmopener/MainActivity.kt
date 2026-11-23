package com.wmopener

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_GAME = "game"
        const val TAB_AUTO = "auto"
        const val TAB_HISTORY = "history"
        private val INTERVALS = listOf(10L to "10 min", 15L to "15 min", 30L to "30 min", 60L to "1 h", 120L to "2 h", 240L to "4 h")
    }

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private var tab = TAB_GAME
    private var pendingMode: String? = null
    private var running = false
    private var nextRunAt = 0L
    private val history = mutableListOf<Entry>()

    private val rarityColor by lazy {
        mapOf(
            "C" to getColor(R.color.r_c), "PC" to getColor(R.color.r_pc), "R" to getColor(R.color.r_r),
            "SR" to getColor(R.color.r_sr), "UR" to getColor(R.color.r_ur), "L" to getColor(R.color.r_l),
        )
    }

    private lateinit var periodicInfo: LiveData<List<WorkInfo>>
    private lateinit var onceInfo: LiveData<List<WorkInfo>>
    private lateinit var chainInfo: LiveData<List<WorkInfo>>
    private val workObserver = Observer<List<WorkInfo>> { onWorkChanged() }

    // ───────────────────────────── Cycle de vie ─────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        applyInsets()
        Notifs.channels(this)

        setupWeb()
        setupTabs()
        setupAuto()
        setupHistory()

        val wm = WorkManager.getInstance(this)
        periodicInfo = wm.getWorkInfosForUniqueWorkLiveData(OpenerWorker.periodicName())
        onceInfo = wm.getWorkInfosForUniqueWorkLiveData(OpenerWorker.onceName())
        periodicInfo.observeForever(workObserver)
        onceInfo.observeForever(workObserver)
        chainInfo = wm.getWorkInfosForUniqueWorkLiveData(OpenerWorker.chainName())
        chainInfo.observeForever(workObserver)

        if (savedInstanceState == null) {
            web.loadUrl(prefs.pullsUrl)
            if (Build.VERSION.SDK_INT >= 33 && !notifGranted())
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            web.restoreState(savedInstanceState)
        }
        showTab(intent.getStringExtra(EXTRA_TAB) ?: savedInstanceState?.getString(EXTRA_TAB) ?: TAB_GAME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let(::showTab)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        // Si l'auto est actif, on remet la notification permanente (elle peut avoir été
        // balayée sur Android 14+, qui autorise à effacer les notifications "ongoing").
        if (prefs.autoEnabled && !running) Notifs.showStatus(this)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_TAB, tab)
        web.saveState(outState)
    }

    override fun onDestroy() {
        periodicInfo.removeObserver(workObserver)
        onceInfo.removeObserver(workObserver)
        chainInfo.removeObserver(workObserver)
        web.removeJavascriptInterface("WMBridge")
        web.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAll()
        if (prefs.autoEnabled) Notifs.showStatus(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            tab == TAB_GAME && web.canGoBack() -> web.goBack()
            tab != TAB_GAME -> showTab(TAB_GAME)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /**
     * Android 15 (targetSdk 35) impose l'affichage bord à bord : sans ceci, l'en-tête passe
     * sous la barre d'état et les onglets sous la barre de navigation. On l'active pour
     * toutes les versions et on réserve les marges : haut = barre d'état (fond de l'en-tête),
     * bas = barre de navigation (fond des onglets), clavier = remonte tout l'écran.
     */
    private fun applyInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val root = findViewById<View>(R.id.root)
        val tabs = findViewById<View>(R.id.tabs)
        val tabsHeight = dp(56)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, (ime.bottom - bars.bottom).coerceAtLeast(0))
            tabs.setPadding(0, 0, 0, bars.bottom)
            tabs.layoutParams = tabs.layoutParams.apply { height = tabsHeight + bars.bottom }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    // ───────────────────────────── Jeu (WebView) ─────────────────────────────

    private fun setupWeb() {
        web = findViewById(R.id.web)
        web.setBackgroundColor(getColor(R.color.bg))
        AutoOpener.configure(web)
        val bar = findViewById<ProgressBar>(R.id.webProgress)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                bar.progress = p
                bar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
        }
        web.webViewClient = object : AutoOpener.SiteClient(this, external = true) {
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                val mode = pendingMode
                if (mode != null && AutoOpener.isSiteUrl(url)) {
                    pendingMode = null
                    view.evaluateJavascript(AutoOpener.buildScript(this@MainActivity, prefs, mode), null)
                }
            }
        }
        web.addJavascriptInterface(AutoOpener.Bridge({ web }) { onVisibleReport(it) }, "WMBridge")
    }

    /** Lance le script dans la WebView visible : "diagnose" (lecture seule) ou "open" (test visible). */
    private fun runVisible(mode: String) {
        if (mode == "open") {
            if (running || !AutoOpener.busy.compareAndSet(false, true)) { toast("Une ouverture est déjà en cours"); return }
            // Garde-fou : libère le verrou si la page ne répond jamais.
            web.postDelayed({ AutoOpener.busy.set(false) }, 4 * 60 * 1000L)
        }
        showTab(TAB_GAME)
        toast(if (mode == "open") "Ouverture sous vos yeux…" else "Lecture de la page…")
        val target = Uri.parse(prefs.pullsUrl)
        val cur = web.url?.let(Uri::parse)
        if (cur?.host == target.host && cur?.path == target.path) {
            web.evaluateJavascript(AutoOpener.buildScript(this, prefs, mode), null)
        } else {
            pendingMode = mode
            web.loadUrl(prefs.pullsUrl)
        }
    }

    private fun diagnose() = runVisible("diagnose")

    private fun onVisibleReport(r: JSONObject) {
        val isOpen = r.optString("mode") != "diagnose" && (r.has("opened") || r.has("trace"))
        if (isOpen) AutoOpener.busy.set(false)
        AutoOpener.record(this, r, if (isOpen) "manuel" else "diagnostic")
        if (isOpen) loadHistory()

        val text = StringBuilder()
        text.append(AutoOpener.summarize(r)).append("\n")
        r.optJSONObject("available")?.let { text.append("Paquets disponibles : ${it.optInt("n")} / ${it.optInt("max")}\n") }
            ?: text.append("Compteur « paquets disponibles » : non trouvé\n")
        r.optJSONObject("reveal")?.let { text.append("Écran de révélation : carte ${it.optInt("i")} / ${it.optInt("n")}\n") }
        if (r.has("raf")) text.append("Animations : ${if (r.optBoolean("raf")) "actives" else "SUSPENDUES"}\n")

        r.optJSONArray("badges")?.takeIf { it.length() > 0 }?.let { a ->
            text.append("\nBadges de rareté :\n"); for (i in 0 until a.length()) text.append("  ").append(a.optString(i)).append('\n')
        }
        r.optJSONArray("headings")?.takeIf { it.length() > 0 }?.let { a ->
            text.append("\nTitres :\n"); for (i in 0 until a.length()) text.append("  ").append(a.optString(i)).append('\n')
        }
        r.optJSONArray("trace")?.takeIf { it.length() > 0 }?.let { t ->
            text.append("\nDéroulé :\n")
            for (i in 0 until t.length()) text.append("· ").append(t.optString(i)).append('\n')
        }
        r.optJSONArray("buttons")?.takeIf { it.length() > 0 }?.let { b ->
            text.append("\nBoutons visibles :\n")
            for (i in 0 until b.length()) {
                val label = b.optString(i)
                val mark = when {
                    matches(prefs.excludeRegex, label) -> "✕"
                    matches(prefs.openRegex, label) -> "OUVRIR"
                    matches(prefs.followRegex, label) -> "SUITE"
                    else -> "·"
                }
                text.append(mark.padEnd(7)).append(label).append('\n')
            }
        }
        r.optJSONArray("cards")?.let { c ->
            text.append("\nCartes repérées : ").append(c.length())
            for (i in 0 until minOf(c.length(), 10)) c.optJSONObject(i)?.let {
                text.append("\n  ").append(it.optString("rarity")).append(' ').append(it.optString("name"))
            }
            text.append('\n')
        }
        text.append("\nPage : ").append(r.optString("url"))

        val body = text.toString()
        val b = AlertDialog.Builder(this)
            .setTitle(if (isOpen) "Test d'ouverture" else "Diagnostic")
            .setView(ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    this.text = body; setTextIsSelectable(true); textSize = 12f; typeface = Typeface.MONOSPACE
                    setTextColor(getColor(R.color.fg)); setPadding(dp(22), dp(8), dp(22), dp(8))
                })
            })
            .setPositiveButton("OK", null)
            .setNeutralButton("Copier") { _, _ -> copy(body) }
        if (!isOpen) b.setNegativeButton("Ouvrir ici") { _, _ -> runVisible("open") }
        b.show()
    }

    private fun matches(re: String, s: String) =
        try { re.isNotBlank() && Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(s) } catch (_: Exception) { false }

    // ───────────────────────────── Onglets ─────────────────────────────

    private fun setupTabs() {
        findViewById<View>(R.id.tabGame).setOnClickListener { showTab(TAB_GAME) }
        findViewById<View>(R.id.tabAuto).setOnClickListener { showTab(TAB_AUTO) }
        findViewById<View>(R.id.tabHistory).setOnClickListener { showTab(TAB_HISTORY) }
        findViewById<View>(R.id.statusPill).setOnClickListener { showTab(TAB_AUTO) }
    }

    private fun showTab(t: String) {
        tab = t
        findViewById<View>(R.id.pageGame).visibility = if (t == TAB_GAME) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pageAuto).visibility = if (t == TAB_AUTO) View.VISIBLE else View.GONE
        findViewById<View>(R.id.pageHistory).visibility = if (t == TAB_HISTORY) View.VISIBLE else View.GONE
        listOf(R.id.tabGame to TAB_GAME, R.id.tabAuto to TAB_AUTO, R.id.tabHistory to TAB_HISTORY).forEach { (id, name) ->
            findViewById<TextView>(id).setTextColor(getColor(if (name == t) R.color.fg else R.color.faint))
        }
        if (t != TAB_GAME) refreshAll()
    }

    // ───────────────────────────── Auto ─────────────────────────────

    private fun setupAuto() {
        val sw = findViewById<Switch>(R.id.autoSwitch)
        sw.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(getColor(R.color.accent), getColor(R.color.line))
        )
        sw.setOnCheckedChangeListener { _, on ->
            if (on == prefs.autoEnabled) return@setOnCheckedChangeListener
            prefs.autoEnabled = on
            prefs.addLog(if (on) "Auto activé (${prefs.intervalMinutes} min)" else "Auto désactivé")
            OpenerWorker.schedule(this)
            refreshAll()
        }
        findViewById<View>(R.id.rowAuto).setOnClickListener { sw.toggle() }

        val chips = findViewById<LinearLayout>(R.id.intervalChips)
        INTERVALS.forEachIndexed { i, (min, label) ->
            chips.addView(TextView(this).apply {
                text = label
                tag = min
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                typeface = resources.getFont(R.font.outfit_semibold)
                setBackgroundResource(R.drawable.chip)
                setOnClickListener {
                    prefs.intervalMinutes = min
                    OpenerWorker.schedule(this@MainActivity, replace = true)
                    refreshAll()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }

        findViewById<View>(R.id.btnOpen).setOnClickListener {
            if (running || AutoOpener.busy.get()) return@setOnClickListener
            OpenerWorker.runOnce(this)
            toast("Passage lancé")
        }
        findViewById<View>(R.id.btnDiagnose).setOnClickListener { diagnose() }

        findViewById<View>(R.id.permNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && !notifGranted()) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) || !askedNotif()) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                } else openAppSettings()
            } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        findViewById<View>(R.id.permBattery).setOnClickListener { requestBattery() }

        // Notifications : trois interrupteurs indépendants.
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(getColor(R.color.accent), getColor(R.color.line))
        )
        listOf(
            Triple(R.id.swNotifStatus, R.id.rowNotifStatus, "status"),
            Triple(R.id.swNotifRun, R.id.rowNotifRun, "run"),
            Triple(R.id.swNotifResult, R.id.rowNotifResult, "result"),
        ).forEach { (swId, rowId, key) ->
            val s = findViewById<Switch>(swId)
            s.trackTintList = track
            s.isChecked = when (key) { "status" -> prefs.notifStatus; "run" -> prefs.notifRun; else -> prefs.notifResult }
            s.setOnCheckedChangeListener { _, on ->
                when (key) { "status" -> prefs.notifStatus = on; "run" -> prefs.notifRun = on; else -> prefs.notifResult = on }
                if (key == "status") Notifs.showStatus(this)
                refreshAll()
            }
            findViewById<View>(rowId).setOnClickListener { s.toggle() }
        }

        findViewById<View>(R.id.rowDetect).setOnClickListener { showDetection() }
        findViewById<View>(R.id.rowMax).setOnClickListener { showMax() }
        findViewById<View>(R.id.rowLog).setOnClickListener { showLog() }

        val v = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version).text = "v$v · ${Uri.parse(prefs.pullsUrl).let { it.host + it.path }}"
    }

    private fun onWorkChanged() {
        val all = (periodicInfo.value.orEmpty() + onceInfo.value.orEmpty() + chainInfo.value.orEmpty())
        val wasRunning = running
        running = all.any { it.state == WorkInfo.State.RUNNING }
        nextRunAt = (if (prefs.intervalMinutes < 15) chainInfo.value.orEmpty() else periodicInfo.value.orEmpty())
            .firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis ?: 0L
        if (wasRunning && !running) {
            loadHistory()
            // La page du jeu n'a pas vu le passage en arrière-plan : on la recharge pour que
            // le compteur « paquets disponibles » soit à jour.
            if (AutoOpener.isSiteUrl(web.url) && pendingMode == null && !AutoOpener.busy.get()) web.reload()
        }
        refreshAll()
    }

    private fun refreshAll() {
        if (!::web.isInitialized) return
        val auto = prefs.autoEnabled

        // Pastille d'état
        val (label, colorRes) = when {
            running -> "EN COURS" to R.color.r_l
            auto -> "AUTO" to R.color.accent
            else -> "PAUSE" to R.color.faint
        }
        findViewById<TextView>(R.id.statusText).apply { text = label; setTextColor(getColor(colorRes)) }
        (findViewById<View>(R.id.statusDot).background.mutate() as GradientDrawable).setColor(getColor(colorRes))

        // Mode auto
        findViewById<Switch>(R.id.autoSwitch).isChecked = auto
        findViewById<TextView>(R.id.autoSub).text = when {
            running -> "Ouverture en cours…"
            auto -> "Toutes les ${label(prefs.intervalMinutes)}" + if (prefs.notifStatus) ", notification permanente" else ""
            else -> "Désactivé"
        }
        findViewById<TextView>(R.id.lastRun).text =
            if (prefs.lastRunAt == 0L) "—" else "${when (val d = dayLabel(prefs.lastRunAt)) { "Aujourd'hui" -> ""; else -> "$d " }}${hhmm(prefs.lastRunAt)}"
        findViewById<TextView>(R.id.nextRun).text = when {
            running -> "maintenant"
            !auto -> "—"
            nextRunAt > System.currentTimeMillis() -> "≈ ${hhmm(nextRunAt)}"
            else -> "dès que possible"
        }
        findViewById<View>(R.id.btnOpen).isEnabled = !running
        (findViewById<TextView>(R.id.btnOpen)).text = if (running) "En cours…" else "Ouvrir maintenant"

        val chips = findViewById<LinearLayout>(R.id.intervalChips)
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i) as TextView
            val sel = c.tag == prefs.intervalMinutes
            c.isSelected = sel
            c.setTextColor(getColor(if (sel) R.color.accent else R.color.muted))
        }

        // Autorisations
        val pm = getSystemService(PowerManager::class.java)
        permState(R.id.permNotifState, notifGranted())
        permState(R.id.permBatteryState, pm.isIgnoringBatteryOptimizations(packageName))
        findViewById<TextView>(R.id.maxValue).text = prefs.maxPacks.toString()

        refreshCounters()
        refreshHistory()
    }

    private fun permState(id: Int, ok: Boolean) = findViewById<TextView>(id).apply {
        text = if (ok) "OK" else "Activer ›"
        setTextColor(getColor(if (ok) R.color.accent else R.color.r_ur))
    }

    private fun refreshCounters() {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = history.filter { it.time >= startOfDay }.sumOf { it.opened }
        val total = history.sumOf { it.opened }
        findViewById<TextView>(R.id.todayCount).text = today.toString()
        findViewById<TextView>(R.id.totalCount).text = "total $total"

        val counts = History.RARITY_ORDER.associateWith { r -> history.sumOf { e -> e.cards.count { it.rarity == r } } }
        val bar = findViewById<LinearLayout>(R.id.rarityBar)
        val legend = findViewById<LinearLayout>(R.id.rarityLegend)
        bar.removeAllViews(); legend.removeAllViews()
        val sum = counts.values.sum()
        History.RARITY_ORDER.forEach { r ->
            val n = counts[r] ?: 0
            if (sum > 0 && n > 0) bar.addView(View(this).apply { setBackgroundColor(rarityColor[r]!!) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, n.toFloat()))
            legend.addView(TextView(this).apply {
                text = SpannableStringBuilder().apply {
                    append(r, ForegroundColorSpan(rarityColor[r]!!), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(" ")
                    append(n.toString(), ForegroundColorSpan(getColor(if (n > 0) R.color.muted else R.color.faint)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                textSize = 12f
                typeface = Typeface.MONOSPACE
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ───────────────────────────── Historique ─────────────────────────────

    private val adapter = object : BaseAdapter() {
        override fun getCount() = history.size
        override fun getItem(p: Int) = history[p]
        override fun getItemId(p: Int) = history[p].time
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            val e = history[p]
            val day = dayLabel(e.time)
            v.findViewById<TextView>(R.id.day).apply {
                text = day
                visibility = if (p == 0 || dayLabel(history[p - 1].time) != day) View.VISIBLE else View.GONE
            }
            v.findViewById<TextView>(R.id.time).text = hhmm(e.time)
            v.findViewById<TextView>(R.id.count).text = SpannableStringBuilder().apply {
                append("${e.opened} paquet${if (e.opened > 1) "s" else ""}")
                append("  ${e.source}", ForegroundColorSpan(getColor(R.color.faint)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            v.findViewById<TextView>(R.id.cards).text = cardsText(e.cards, e.note)
            val best = e.cards.maxByOrNull { History.RARITY_ORDER.indexOf(it.rarity) }?.rarity
            v.findViewById<View>(R.id.edge).background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat(); setColor(rarityColor[best] ?: getColor(R.color.line))
            }
            return v
        }
    }

    private fun cardsText(cards: List<Card>, note: String = ""): CharSequence {
        if (cards.isEmpty()) return SpannableStringBuilder().apply {
            append("Cartes non repérées" + if (note.isNotBlank()) " · $note" else "",
                ForegroundColorSpan(getColor(R.color.faint)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val sb = SpannableStringBuilder()
        cards.sortedByDescending { History.RARITY_ORDER.indexOf(it.rarity) }.forEachIndexed { i, c ->
            if (i > 0) sb.append("     ")
            val s = sb.length
            sb.append(c.rarity)
            sb.setSpan(ForegroundColorSpan(rarityColor[c.rarity] ?: getColor(R.color.muted)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(TypefaceSpan("monospace"), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ").append(c.name.replace(' ', ' '))
        }
        return sb
    }

    private fun setupHistory() {
        val list = findViewById<ListView>(R.id.historyList)
        list.adapter = adapter
        // Toucher une entrée : déroulé du passage (pour comprendre une carte non repérée).
        list.setOnItemClickListener { _, _, p, _ ->
            val e = history[p]
            val body = buildString {
                append("${dayLabel(e.time)} à ${hhmm(e.time)} · ${e.opened} paquet(s) · ${e.source}\n")
                if (e.note.isNotBlank()) append(e.note).append('\n')
                if (e.cards.isNotEmpty()) append("\nCartes : ").append(e.cards.joinToString(", ") { "${it.rarity} ${it.name}" }).append('\n')
                append("\nDéroulé :\n")
                if (e.trace.isEmpty()) append("(non enregistré : passage antérieur à la version 1.3.2)\n")
                e.trace.forEach { append("· ").append(it).append('\n') }
            }
            dialog("Passage de ${hhmm(e.time)}", body, mono = true)
        }
        list.setOnItemLongClickListener { _, _, p, _ ->
            val e = history[p]
            AlertDialog.Builder(this)
                .setTitle("Supprimer cette entrée ?")
                .setMessage("${dayLabel(e.time)} à ${hhmm(e.time)}, ${e.opened} paquet(s).")
                .setPositiveButton("Supprimer") { _, _ -> History.remove(this, e.time); loadHistory() }
                .setNegativeButton("Annuler", null).show()
            true
        }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            if (history.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Effacer l'historique ?")
                .setMessage("Les ${history.size} entrées seront supprimées du téléphone. Vos cartes sur le site ne sont pas concernées.")
                .setPositiveButton("Effacer") { _, _ -> History.clear(this); loadHistory() }
                .setNegativeButton("Annuler", null).show()
        }
        loadHistory()
    }

    private fun loadHistory() {
        history.clear(); history.addAll(History.all(this))
        refreshAll()
    }

    private fun refreshHistory() {
        adapter.notifyDataSetChanged()
        val empty = history.isEmpty()
        findViewById<View>(R.id.historyList).visibility = if (empty) View.GONE else View.VISIBLE
        findViewById<View>(R.id.historyEmpty).visibility = if (empty) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnClear).visibility = if (empty) View.INVISIBLE else View.VISIBLE
        val packs = history.sumOf { it.opened }
        findViewById<TextView>(R.id.historyTitle).text =
            if (empty) "Historique" else "$packs paquet${if (packs > 1) "s" else ""} · ${history.size} passage${if (history.size > 1) "s" else ""}"
    }

    // ───────────────────────────── Dialogues ─────────────────────────────

    private fun showDetection() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(4), dp(22), 0) }
        fun field(label: String, hint: String, value: String): EditText {
            box.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(getColor(R.color.fg))
                typeface = resources.getFont(R.font.outfit_semibold); setPadding(0, dp(14), 0, dp(2))
            })
            box.addView(TextView(this).apply { text = hint; textSize = 12f; setTextColor(getColor(R.color.faint)) })
            return EditText(this).apply {
                setText(value); textSize = 13f; typeface = Typeface.MONOSPACE
                setTextColor(getColor(R.color.fg)); setBackgroundResource(R.drawable.field)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                box.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }
        val open = field("Bouton d'ouverture", "Expression régulière, insensible à la casse", prefs.openRegex)
        val follow = field("Boutons de suite", "Révéler, continuer… cliqués après l'ouverture", prefs.followRegex)
        val excl = field("Jamais cliqués", "Achats, monnaie premium, publicités", prefs.excludeRegex)
        val url = field("Page des paquets", "Doit rester sur wiki-masters.com", prefs.pullsUrl)

        val d = AlertDialog.Builder(this)
            .setTitle("Détection des boutons")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Enregistrer", null)
            .setNeutralButton("Par défaut") { _, _ -> prefs.resetPatterns(); toast("Valeurs par défaut rétablies") }
            .setNegativeButton("Annuler", null)
            .show()
        // Validation avant fermeture : regex valides et URL sur le site.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val bad = listOf(open, follow, excl).firstOrNull { f ->
                try { Regex(f.text.toString()); false } catch (_: Exception) { true }
            }
            when {
                bad != null -> bad.error = "Expression invalide"
                open.text.isBlank() -> open.error = "Obligatoire"
                !Prefs.isAllowedUrl(url.text.toString()) -> url.error = "Adresse https sur wiki-masters.com uniquement"
                else -> {
                    prefs.openRegex = open.text.toString().trim()
                    prefs.followRegex = follow.text.toString().trim()
                    prefs.excludeRegex = excl.text.toString().trim()
                    prefs.pullsUrl = url.text.toString().trim()
                    d.dismiss(); refreshAll()
                }
            }
        }
    }

    private fun showMax() {
        val values = arrayOf(1, 3, 5, 10, 20, 50)
        AlertDialog.Builder(this)
            .setTitle("Paquets max par passage")
            .setSingleChoiceItems(values.map { it.toString() }.toTypedArray(), values.indexOf(prefs.maxPacks)) { di, i ->
                prefs.maxPacks = values[i]; di.dismiss(); refreshAll()
            }
            .setNegativeButton("Annuler", null).show()
    }

    private fun showLog() {
        AlertDialog.Builder(this)
            .setTitle("Journal technique")
            .setView(ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = prefs.log.ifEmpty { "(vide)" }; setTextIsSelectable(true)
                    typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(getColor(R.color.muted))
                    setPadding(dp(22), dp(8), dp(22), dp(8))
                })
            })
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Vider") { _, _ -> prefs.clearLog() }
            .setNegativeButton("Copier") { _, _ -> copy(prefs.log) }
            .show()
    }

    private fun dialog(title: String, body: String, mono: Boolean = false) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = body; setTextIsSelectable(true); textSize = 12f
                    if (mono) typeface = Typeface.MONOSPACE
                    setTextColor(getColor(R.color.fg)); setPadding(dp(22), dp(8), dp(22), dp(8))
                })
            })
            .setPositiveButton("OK", null)
            .setNeutralButton("Copier") { _, _ -> copy(body) }
            .show()
    }

    // ───────────────────────────── Autorisations ─────────────────────────────

    private fun notifGranted() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun askedNotif(): Boolean {
        val sp = getSharedPreferences("wm_opener", MODE_PRIVATE)
        val asked = sp.getBoolean("asked_notif", false)
        sp.edit().putBoolean("asked_notif", true).apply()
        return asked
    }

    @SuppressLint("BatteryLife")
    private fun requestBattery() {
        val pm = getSystemService(PowerManager::class.java)
        val i = if (pm.isIgnoringBatteryOptimizations(packageName)) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try { startActivity(i) } catch (_: Exception) { openAppSettings() }
    }

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    // ───────────────────────────── Utilitaires ─────────────────────────────

    private fun label(min: Long) = INTERVALS.firstOrNull { it.first == min }?.second ?: "$min min"
    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))
    private fun dayLabel(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val now = Calendar.getInstance()
        val diff = (now.get(Calendar.YEAR) * 400 + now.get(Calendar.DAY_OF_YEAR)) - (c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR))
        return when (diff) {
            0 -> "Aujourd'hui"
            1 -> "Hier"
            else -> SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(Date(t))
        }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("WM Opener", text))
        toast("Copié")
    }
}
