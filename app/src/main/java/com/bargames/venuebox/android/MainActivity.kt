package com.bargames.venuebox.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.bargames.venuebox.BuildConfig
import com.bargames.venuebox.client.ClientStatus
import com.bargames.venuebox.protocol.BoxAddress
import com.bargames.venuebox.protocol.BoxMsg
import com.bargames.venuebox.protocol.ClientMsg
import com.bargames.venuebox.protocol.DataUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The one screen: a full-screen WebView showing what the box sends, a status page (pairing
 * code, "connecting…") when it shows nothing, and a D-pad settings panel (BACK or MENU).
 */
class MainActivity : Activity(), ScreenUi {
    private val ctl get() = controller
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private lateinit var root: FrameLayout
    private lateinit var webHolder: FrameLayout
    private var web: WebView? = null
    private lateinit var statusBox: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusCode: TextView
    private lateinit var statusDetail: TextView
    private lateinit var identifyView: TextView

    private lateinit var panel: ScrollView
    private lateinit var panelStatus: TextView
    private lateinit var foundList: LinearLayout
    private lateinit var searchButton: Button
    private lateinit var addressInput: EditText
    private lateinit var addressMsg: TextView
    private lateinit var nameInput: EditText
    private lateinit var autostartNote: TextView
    private lateinit var grantButton: Button
    private lateinit var firstButton: Button

    /** The URL the box asked for; null → off. */
    private var url: String? = null
    private var title: String? = null
    /** The page failed: the status page shows the error until the retry. */
    private var failed = false
    private var state = "off"
    private var detail: String? = null
    private val retry = Runnable { if (url != null) load() }
    private val hideIdentify = Runnable { identifyView.visibility = View.GONE }
    private var panelShownForNoBox = false
    /** The 1920-px viewport is injected at document start (else after load, see [viewportJs]). */
    private var docStartViewport = false
    /** The current page came from a data: URL (loaded with base about:blank). */
    private var dataPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        setContentView(root)
        immersive()
        ctl.start()
        ctl.ui = this
        ctl.showing?.let { show(it) }
        debugShow(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugShow(intent)
    }

    /** Debug builds only: adb shell am start -n <pkg>/.android.MainActivity --es debugShow <url> */
    private fun debugShow(i: Intent?) {
        if (!BuildConfig.DEBUG) return
        val u = i?.getStringExtra("debugShow") ?: return
        show(BoxMsg.Show(SCREEN_ID, u, "native-url", "debug"))
    }

    override fun onResume() {
        super.onResume()
        immersive()
        refreshPanel()
    }

    override fun onDestroy() {
        if (ctl.ui === this) ctl.ui = null
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        web?.destroy()
        web = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- box → screen

    override fun onStatus(s: ClientStatus) {
        renderStatus()
        refreshPanel()
        if (s is ClientStatus.Connected) {
            reportState(force = true)
            panelShownForNoBox = false
        }
    }

    override fun onDiscovery() {
        renderStatus()
        refreshPanel()
        // No box picked by itself (none or several found): let staff choose or type it, once.
        if (ctl.client.current is ClientStatus.NoBox && !ctl.searching && !panelShownForNoBox &&
            ctl.found.count { it.url != null } != 1
        ) {
            panelShownForNoBox = true
            showPanel(true)
        }
    }

    override fun onBoxMessage(m: BoxMsg) {
        when (m) {
            is BoxMsg.Show -> if (m.screen == SCREEN_ID) {
                // native-url; the box also labels idle pages kind "idle" (a URL to open all the same).
                if (m.kind == "hls") {
                    ctl.send(ClientMsg.state(SCREEN_ID, m.url, "error", "this app can't play ${m.kind}"))
                } else {
                    show(m)
                }
            }
            is BoxMsg.Off -> if (m.screen == SCREEN_ID) off()
            is BoxMsg.Reload -> if (m.screen == SCREEN_ID) reload()
            is BoxMsg.Identify -> if (m.screen == SCREEN_ID) identify(m.label)
            is BoxMsg.PreviewRequest -> if (m.screen == SCREEN_ID) preview(m.requestId)
            else -> {}
        }
    }

    private fun show(m: BoxMsg.Show) {
        val scheme = Uri.parse(m.url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "data") {
            setState("error", "the box sent a non-web URL")
            return
        }
        Log.i(TAG, "show ${m.title ?: ""}")
        url = m.url
        title = m.title
        load()
    }

    private fun off() {
        Log.i(TAG, "off")
        url = null
        title = null
        failed = false
        handler.removeCallbacks(retry)
        dataPage = false
        web?.loadUrl("about:blank")
        renderStatus()
        setState("off")
    }

    private fun reload() {
        if (url != null) load() else renderStatus()
    }

    private fun load() {
        val u = url ?: return
        handler.removeCallbacks(retry)
        failed = false
        val w = web ?: createWeb()
        renderStatus()
        setState("loading")
        val data = if (u.startsWith("data:", ignoreCase = true)) DataUrl.parse(u) else null
        dataPage = data != null
        if (data != null) {
            // The box's idle page is a data: URL; long ones are unreliable through loadUrl.
            w.loadDataWithBaseURL(null, data.text, data.mime, "utf-8", null)
        } else {
            w.loadUrl(u)
        }
    }

    /** about:blank is our own "off" page, unless a data: page was loaded (it has that URL too). */
    private fun ours(pageUrl: String?) = pageUrl == "about:blank" && !dataPage

    private fun failed(why: String, retryMs: Long = 10_000) {
        Log.w(TAG, "page failed: $why")
        failed = true
        setState("error", why)
        renderStatus()
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, retryMs)
    }

    /** Flashes the screen's name over whatever it shows (which TV is which). */
    private fun identify(label: String?) {
        identifyView.text = label ?: ctl.labels[SCREEN_ID] ?: ctl.defaultName()
        identifyView.visibility = View.VISIBLE
        identifyView.bringToFront()
        handler.removeCallbacks(hideIdentify)
        handler.postDelayed(hideIdentify, 6000)
    }

    // ---------------------------------------------------------------- state

    private fun setState(status: String, why: String? = null) {
        val changed = status != state || why != detail
        state = status
        detail = why
        if (changed) reportState()
    }

    private fun reportState(force: Boolean = false) {
        if (!force && !ctl.client.connected) return
        val u = url
        ctl.send(ClientMsg.state(SCREEN_ID, u ?: "", if (u == null) "off" else state, detail))
    }

    // ---------------------------------------------------------------- previews

    /** A JPEG of what the screen shows, base64, ≲200 KB. */
    private fun preview(requestId: String) {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) {
            ctl.send(ClientMsg.preview(requestId, SCREEN_ID, null, "nothing to capture yet"))
            return
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val loc = IntArray(2).also { root.getLocationInWindow(it) }
        val done = { ok: Boolean ->
            if (!ok) root.draw(Canvas(bmp)) // fallback: misses video/WebGL, but shows the page
            scope.launch {
                val jpeg = withContext(Dispatchers.Default) { encode(bmp) }
                bmp.recycle()
                ctl.send(
                    if (jpeg != null) ClientMsg.preview(requestId, SCREEN_ID, jpeg, null)
                    else ClientMsg.preview(requestId, SCREEN_ID, null, "preview too large"),
                )
            }
        }
        try {
            PixelCopy.request(window, Rect(loc[0], loc[1], loc[0] + w, loc[1] + h), bmp, { r -> done(r == PixelCopy.SUCCESS) }, handler)
        } catch (e: Exception) {
            done(false)
        }
    }

    private fun encode(src: Bitmap): String? {
        for ((width, quality) in listOf(960 to 70, 960 to 50, 640 to 50, 480 to 40)) {
            val scaled = if (src.width > width) Bitmap.createScaledBitmap(src, width, src.height * width / src.width, true) else src
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== src) scaled.recycle()
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= MAX_PREVIEW_B64) return b64
        }
        return null
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb(): WebView {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.setSupportZoom(false)
        // TV pages are laid out at 1920x1080 CSS px everywhere (desktop client, AirPlay/Roku
        // Chromium), then scaled to the panel: a 1080p xhdpi TV would otherwise give 960x540.
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, viewportJs(), setOf("*"))
            docStartViewport = true
        }
        w.setBackgroundColor(Color.BLACK)
        // Display only: the D-pad stays with the app (BACK/MENU open the settings panel).
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.isLongClickable = false
        w.setOnLongClickListener { true }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

            override fun onPageCommitVisible(view: WebView, pageUrl: String?) {
                if (!docStartViewport && !ours(pageUrl)) view.evaluateJavascript(viewportJs(), null)
            }

            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                if (url != null && !failed && !ours(pageUrl)) setState("loading")
            }

            override fun onPageFinished(view: WebView, pageUrl: String?) {
                if (ours(pageUrl) || url == null || failed) return
                if (!docStartViewport) view.evaluateJavascript(viewportJs(), null)
                view.evaluateJavascript(HIDE_CURSOR_JS, null)
                view.evaluateJavascript(METRICS_JS) { Log.i(TAG, "page metrics: $it") }
                setState("playing")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && url != null) failed("${error.description} (${error.errorCode})")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && url != null && response.statusCode >= 400) failed("the box answered ${response.statusCode}")
            }

            override fun onRenderProcessGone(view: WebView, d: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "page renderer gone (crash=${d.didCrash()})")
                webHolder.removeView(view)
                view.destroy()
                if (web === view) web = null
                if (url != null) {
                    createWeb()
                    failed("page crashed", 2000)
                }
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (BuildConfig.DEBUG) Log.d(TAG, "page: ${m.message()}")
                return true
            }

            // No grey "play" poster on videos before they start.
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        webHolder.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        return w
    }

    /**
     * Pins every page's viewport to 1920 CSS px wide, scaled to fit the panel: a 1080p TV at
     * density 2 gets scale 0.5 (1 CSS px = 1 pixel), a 4K TV at density 4 renders 1920 CSS px
     * at device scale 2. Also rewrites viewport tags the page adds later.
     */
    @Suppress("DEPRECATION")
    private fun viewportJs(): String {
        val m = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val landscapeWidthPx = maxOf(m.widthPixels, m.heightPixels)
        val scale = (landscapeWidthPx / m.density) / CSS_WIDTH
        val s = String.format(java.util.Locale.US, "%.4f", scale)
        val content = "width=$CSS_WIDTH, initial-scale=$s, minimum-scale=$s, maximum-scale=$s, user-scalable=no"
        return """(function(){var C='$content';function fix(){var r=document.documentElement;if(!r)return;""" +
            """var ms=document.querySelectorAll('meta[name=viewport]');if(!ms.length){var m=document.createElement('meta');m.name='viewport';""" +
            """(document.head||r).appendChild(m);ms=[m];}for(var i=0;i<ms.length;i++)if(ms[i].content!==C)ms[i].content=C;}""" +
            """fix();new MutationObserver(fix).observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});})();"""
    }

    // ---------------------------------------------------------------- status page

    private fun renderStatus() {
        val showingPage = url != null && !failed
        webHolder.visibility = if (showingPage) View.VISIBLE else View.INVISIBLE
        val view: Triple<String, String?, String?>? = when {
            showingPage -> null
            url != null && failed -> Triple(title ?: "Can't load this page", "${detail ?: "error"}. Retrying…", null)
            else -> statusView(ctl.client.current)
        }
        if (view == null) {
            statusBox.visibility = View.GONE
            return
        }
        statusBox.visibility = View.VISIBLE
        statusTitle.text = view.first
        statusDetail.text = view.second ?: ""
        statusDetail.visibility = if (view.second.isNullOrEmpty()) View.GONE else View.VISIBLE
        statusCode.text = view.third ?: ""
        statusCode.visibility = if (view.third.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun statusView(s: ClientStatus): Triple<String, String?, String?>? = when (s) {
        is ClientStatus.NoBox -> when {
            ctl.searching -> Triple("Looking for the box…", "Searching this network.", null)
            ctl.found.count { it.url != null } > 1 -> Triple("Several boxes found", "Press BACK and choose one.", null)
            else -> Triple("No box found yet", "Still looking. Or press BACK to type the box address.", null)
        }
        is ClientStatus.Unreachable -> Triple("Can't reach the box", "${s.box}: ${s.error}. Trying again…", null)
        is ClientStatus.Pairing -> Triple("Pair this screen", "On the box: TVs page → Venuebox apps → type this code.", s.code.chunked(3).joinToString(" "))
        is ClientStatus.Connecting -> Triple("Connecting to the box…", s.error?.let { "${s.box}: $it" } ?: s.box, null)
        is ClientStatus.Replaced -> Triple("Paired elsewhere", "Another copy of this app connected with this device's pairing. Trying again in a few minutes.", null)
        is ClientStatus.Connected -> null // black until the box shows something
    }

    // ---------------------------------------------------------------- settings panel

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_UP) showPanel(panel.visibility != View.VISIBLE)
            return true
        }
        // Panel closed: OK / centre also opens it (remotes without a menu key).
        if (panel.visibility != View.VISIBLE && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_UP) showPanel(true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showPanel(on: Boolean) {
        panel.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            panel.bringToFront()
            refreshPanel()
            if (ctl.found.isEmpty() && !ctl.searching) ctl.search()
            firstButton.requestFocus()
        } else {
            immersive()
        }
    }

    private fun refreshPanel() {
        if (!::panel.isInitialized) return
        val s = ctl.client.current
        panelStatus.text = when (s) {
            is ClientStatus.Connected -> "Connected to ${ctl.config.boxName ?: s.box} as “${s.name}”."
            is ClientStatus.Pairing -> "Waiting for pairing (code on screen)."
            is ClientStatus.Connecting -> "Connecting to ${s.box}${s.error?.let { ": $it" } ?: ""}"
            is ClientStatus.Unreachable -> "Can't reach ${s.box}: ${s.error}"
            is ClientStatus.Replaced -> "Paired elsewhere: retrying later."
            is ClientStatus.NoBox -> "No box set."
        }
        searchButton.text = if (ctl.searching) "Searching…" else "Search again"
        searchButton.isEnabled = !ctl.searching
        foundList.removeAllViews()
        if (ctl.found.isEmpty()) {
            foundList.addView(note(if (ctl.searching) "Searching…" else "None found. (The emulator and some venue networks can't see them: type the address below.)"))
        }
        for (b in ctl.found) {
            val current = b.id == ctl.config.boxId
            val label = "${b.name}${if (current) "  (current)" else ""}${if (b.url == null) "  (no answer)" else ""}"
            foundList.addView(button(label) {
                val err = ctl.useFound(b)
                addressMsg.text = err ?: "Using ${b.name}."
            }.apply { isEnabled = b.url != null })
        }
        if (addressInput.text.isEmpty()) ctl.config.box?.let { addressInput.setText(it.removePrefix("http://")) }
        nameInput.hint = ctl.defaultName()
        if (nameInput.text.isEmpty()) ctl.config.name?.let { nameInput.setText(it) }
        val canStart = Settings.canDrawOverlays(this)
        autostartNote.text = if (canStart) {
            "Start on the TV at boot: on."
        } else {
            "Start on the TV at boot: needs “Display over other apps” → Grant.\n" +
                "(Or from a computer: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow)"
        }
        autostartNote.setTextColor(if (canStart) 0xFFB8BCC6.toInt() else Color.WHITE)
        grantButton.visibility = if (canStart) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- UI building

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        webHolder = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(webHolder, match)

        statusTitle = text(34f, Typeface.BOLD)
        statusCode = text(96f, Typeface.BOLD).apply {
            setTextColor(AMBER)
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        statusDetail = text(20f).apply { setTextColor(0xFFB8BCC6.toInt()) }
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
            addView(statusTitle)
            addView(statusCode)
            addView(statusDetail)
        }
        root.addView(statusBox, match)

        identifyView = text(72f, Typeface.BOLD).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(AMBER)
            visibility = View.GONE
        }
        root.addView(identifyView, match)

        buildPanel()
        root.addView(panel, FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    }

    private fun buildPanel() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(28))
        }
        col.addView(text(28f, Typeface.BOLD, Gravity.START).apply { text = "Venuebox" })
        col.addView(note("Version ${BuildConfig.VERSION_NAME}. BACK closes this panel."))
        panelStatus = note("").apply { setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(8)) }
        col.addView(panelStatus)

        col.addView(heading("Boxes on this network"))
        foundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(foundList)
        searchButton = button("Search again") { ctl.search() }
        col.addView(searchButton)

        col.addView(heading("Type the box address"))
        addressInput = EditText(this).apply {
            hint = "192.168.1.20 or 192.168.1.20:7210"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7A808C.toInt())
        }
        col.addView(addressInput)
        addressMsg = note("")
        val connect = button("Connect") {
            addressMsg.text = "Checking…"
            ctl.setAddress(addressInput.text.toString()) { err ->
                addressMsg.text = err ?: "Connecting to ${BoxAddress.normalize(addressInput.text.toString())}…"
            }
        }
        addressInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) connect.performClick()
            id == EditorInfo.IME_ACTION_GO
        }
        col.addView(connect)
        col.addView(addressMsg)

        col.addView(heading("This device's name"))
        nameInput = EditText(this).apply {
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7A808C.toInt())
        }
        col.addView(nameInput)
        col.addView(note("Used when pairing. Staff can rename it on the box's TVs page."))
        col.addView(button("Save name") { ctl.setName(nameInput.text.toString()); addressMsg.text = "Name saved." })

        col.addView(heading("Start after a power cut"))
        autostartNote = note("")
        col.addView(autostartNote)
        grantButton = button("Grant") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                addressMsg.text = "This TV has no settings page for it: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
            }
        }
        col.addView(grantButton)

        col.addView(heading("Pairing"))
        col.addView(button("Unpair (show a new code)") { ctl.unpair(); addressMsg.text = "Unpaired." })
        firstButton = button("Close") { showPanel(false) }
        col.addView(firstButton)

        panel = ScrollView(this).apply {
            background = GradientDrawable().apply { setColor(0xF0111318.toInt()) }
            addView(col)
            visibility = View.GONE
            isFillViewport = true
        }
    }

    private fun text(sp: Float, style: Int = Typeface.NORMAL, gravity: Int = Gravity.CENTER) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.WHITE)
        setTypeface(typeface, style)
        this.gravity = gravity
    }

    private fun heading(s: String) = text(18f, Typeface.BOLD, Gravity.START).apply {
        text = s
        setTextColor(AMBER)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun note(s: String) = text(15f, gravity = Gravity.START).apply {
        text = s
        setTextColor(0xFFB8BCC6.toInt())
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private companion object {
        const val AMBER = 0xFFF5A82B.toInt()
        const val MAX_PREVIEW_B64 = 270_000 // ≈200 KB of JPEG
        const val CSS_WIDTH = 1920
        const val METRICS_JS =
            "JSON.stringify({innerWidth:innerWidth,innerHeight:innerHeight,dpr:devicePixelRatio," +
                "scale:window.visualViewport?visualViewport.scale:null," +
                "meta:[].map.call(document.querySelectorAll('meta[name=viewport]'),function(m){return m.content})})"
        const val HIDE_CURSOR_JS =
            "(function(){var s=document.createElement('style');s.textContent='*,*::before,*::after{cursor:none!important}';(document.head||document.documentElement).appendChild(s);})()"
    }
}
