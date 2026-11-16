package com.bargames.venuebox.android

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.bargames.venuebox.BuildConfig
import com.bargames.venuebox.client.BoxCheck
import com.bargames.venuebox.client.BoxClient
import com.bargames.venuebox.client.BoxClientDeps
import com.bargames.venuebox.client.ClientStatus
import com.bargames.venuebox.client.TokenStore
import com.bargames.venuebox.client.checkBox
import com.bargames.venuebox.client.defaultHttp
import com.bargames.venuebox.protocol.BoxAddress
import com.bargames.venuebox.protocol.BoxMsg
import com.bargames.venuebox.protocol.DeviceInfo
import com.bargames.venuebox.protocol.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val TAG = "Venuebox"

/** Android reports one screen; its id is stable across restarts. */
const val SCREEN_ID = "main"

class VenueboxApp : Application() {
    lateinit var controller: Controller
        private set

    override fun onCreate() {
        super.onCreate()
        controller = Controller(this)
    }
}

val Context.controller: Controller get() = (applicationContext as VenueboxApp).controller

/** What the screen UI implements (the Activity). Calls arrive on the main thread. */
interface ScreenUi {
    fun onStatus(s: ClientStatus)
    fun onBoxMessage(m: BoxMsg)
    fun onDiscovery()
}

/**
 * App-wide: the box connection, discovery and settings. Lives as long as the process, so a
 * recreated Activity picks up where the old one left off.
 */
class Controller(private val app: Application) {
    val config = Config(app)
    val token: TokenStore = KeystoreTokenStore(app)
    val http = defaultHttp()
    val discovery = Discovery(app, http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    var ui: ScreenUi? = null
        set(v) {
            field = v
            v?.onStatus(client.current)
        }

    /** The last show the box sent for our screen (re-applied when the Activity comes back). */
    var showing: BoxMsg.Show? = null
        private set

    /** Labels from welcome: the name the box shows for each screen. */
    var labels: Map<String, String> = emptyMap()
        private set

    var found: List<FoundBox> = emptyList()
        private set
    var searching = false
        private set
    private var findJob: Job? = null
    private var started = false

    val client = BoxClient(object : BoxClientDeps {
        override fun boxUrl() = config.box
        override val token get() = this@Controller.token
        override fun device() = deviceInfo()
        override fun screens() = listOf(screen())
        override fun onStatus(s: ClientStatus) = main.post {
            if (s !is ClientStatus.Connected) Log.i(TAG, "status: ${redact(s)}")
            if (s is ClientStatus.Connected) {
                labels = s.labels
                if (config.boxId == null) learnBoxId()
            }
            ui?.onStatus(s)
        }.let {}

        override fun onMessage(m: BoxMsg) = main.post { handle(m) }.let {}
        override fun log(msg: String) = Log.i(TAG, msg).let {}
        override suspend fun relocate(box: String): String? {
            val id = config.boxId ?: return null
            val b = discovery.relocate(id) ?: return null
            config.box = b.url
            config.boxName = b.name
            return b.url
        }
    }, scope)

    /** Starts the connection once (Activity start or boot). */
    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "starting ${BuildConfig.VERSION_NAME}")
        client.restart()
        if (BoxAddress.normalize(config.box) == null) autoFind()
    }

    private fun handle(m: BoxMsg) {
        when (m) {
            is BoxMsg.Welcome -> Log.i(TAG, "connected as ${m.deviceId}")
            is BoxMsg.Show -> if (m.screen == SCREEN_ID) showing = m
            is BoxMsg.Off -> if (m.screen == SCREEN_ID) showing = null
            is BoxMsg.Error -> Log.w(TAG, "box error ${m.code}: ${m.message}")
            is BoxMsg.Unknown -> Log.i(TAG, "ignoring box message '${m.type}'")
            else -> {}
        }
        ui?.onBoxMessage(m)
    }

    fun send(msg: String) = client.send(msg)

    // ---------------------------------------------------------------- discovery

    /** Browses the LAN; each box gets the first of its addresses that answers. */
    fun search(then: (List<FoundBox>) -> Unit = {}) {
        if (searching) return
        searching = true
        ui?.onDiscovery()
        scope.launch {
            found = try {
                discovery.browse()
            } catch (e: Exception) {
                Log.w(TAG, "discovery failed: ${e.message}")
                emptyList()
            }
            Log.i(TAG, "discovery: ${found.size} box(es)")
            searching = false
            ui?.onDiscovery()
            then(found)
        }
    }

    /** No box set: pick it when exactly one answers; otherwise keep looking (staff can choose or type it). */
    private fun autoFind() {
        findJob?.cancel()
        findJob = scope.launch {
            while (BoxAddress.normalize(config.box) == null) {
                var done = false
                search { live ->
                    val ok = live.filter { it.url != null }
                    if (ok.size == 1 && BoxAddress.normalize(config.box) == null) {
                        Log.i(TAG, "one box found: using it")
                        useFound(ok[0])
                    }
                    done = true
                }
                while (!done) delay(200)
                delay(20_000)
            }
        }
    }

    fun useFound(b: FoundBox): String? {
        val url = b.url ?: return "${b.name} didn't answer."
        // Same box at a new address: the pairing still works. Another box: pair again.
        if (config.boxId != b.id && BoxAddress.normalize(config.box) != url) token.clear()
        config.box = url
        config.boxId = b.id
        config.boxName = b.name
        client.restart()
        return null
    }

    /** "Type the box address": checks /health first. Returns an error message, or null when set. */
    fun setAddress(input: String, then: (String?) -> Unit) {
        val box = BoxAddress.normalize(input) ?: return then("That isn't an address (try 192.168.1.20 or 192.168.1.20:7210).")
        scope.launch {
            when (val r = checkBox(http, box)) {
                is BoxCheck.Failed -> then("$box: ${r.error}")
                is BoxCheck.Ok -> {
                    val changed = BoxAddress.normalize(config.box) != box
                    config.box = box
                    if (changed) {
                        // A different box: the old pairing means nothing there.
                        config.boxId = null
                        config.boxName = null
                        token.clear()
                    }
                    client.restart()
                    then(null)
                }
            }
        }
    }

    /** A box typed in by hand: learn its discovery id once connected, so it can be re-found. */
    private fun learnBoxId() {
        val current = BoxAddress.normalize(config.box) ?: return
        val host = BoxAddress.host(current) ?: return
        scope.launch {
            val b = try {
                discovery.browse()
            } catch (_: Exception) {
                emptyList()
            }.firstOrNull { it.addresses.contains(host) } ?: return@launch
            if (BoxAddress.normalize(config.box) == current) {
                config.boxId = b.id
                config.boxName = b.name
                Log.i(TAG, "learned the box's discovery id")
            }
        }
    }

    fun unpair() {
        Log.i(TAG, "unpaired from settings")
        token.clear()
        client.restart()
    }

    fun setName(name: String) {
        config.name = name.trim().take(60).ifEmpty { null }
    }

    // ---------------------------------------------------------------- device

    fun defaultName(): String {
        val n = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
        return (n?.takeIf { it.isNotBlank() } ?: Build.MODEL).take(60)
    }

    fun deviceInfo() = DeviceInfo(
        name = config.name ?: defaultName(),
        platform = if (isTv()) "androidtv" else "android",
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60),
        appVersion = BuildConfig.VERSION_NAME,
    )

    fun isTv() = app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    @Suppress("DEPRECATION")
    fun screen(): Screen {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = wm.defaultDisplay
        val m = DisplayMetrics().also { d.getRealMetrics(it) }
        val w = maxOf(1, m.widthPixels)
        val h = maxOf(1, m.heightPixels)
        val hz = Math.round(d.refreshRate).takeIf { it in 1..500 }
        return Screen(SCREEN_ID, defaultName(), w, h, hz)
    }

    private fun redact(s: ClientStatus) = if (s is ClientStatus.Pairing) s.copy(code = "******") else s
}
