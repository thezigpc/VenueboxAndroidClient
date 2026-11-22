// Talks to the box: pairing over HTTP, then one WebSocket control channel, reconnecting
// forever. No Android imports here, so it runs (and is tested) on a plain JVM.
// Behaviour mirrors the desktop client (VenueboxDesktopClient/src/box-client.ts).

package com.bargames.venuebox.client

import com.bargames.venuebox.protocol.BoxAddress
import com.bargames.venuebox.protocol.BoxMsg
import com.bargames.venuebox.protocol.CLIENT_PROTOCOL
import com.bargames.venuebox.protocol.ClientMsg
import com.bargames.venuebox.protocol.Close
import com.bargames.venuebox.protocol.DeviceInfo
import com.bargames.venuebox.protocol.PairStart
import com.bargames.venuebox.protocol.PairStatus
import com.bargames.venuebox.protocol.Screen
import com.bargames.venuebox.protocol.VideoCaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.random.Random

sealed interface ClientStatus {
    data object NoBox : ClientStatus
    data class Unreachable(val box: String, val error: String) : ClientStatus
    data class Pairing(val box: String, val code: String, val expiresAt: String) : ClientStatus
    data class Connecting(val box: String, val attempt: Int, val error: String? = null) : ClientStatus
    data class Connected(val box: String, val deviceId: String, val name: String, val labels: Map<String, String>) : ClientStatus
    /** Another copy of this app connected with the same pairing (4409); retries later. */
    data class Replaced(val box: String) : ClientStatus
}

interface TokenStore {
    fun get(): String?
    fun set(token: String)
    fun clear()
}

interface BoxClientDeps {
    fun boxUrl(): String?
    val token: TokenStore
    fun device(): DeviceInfo
    fun screens(): List<Screen>
    /** What the video player plays (hello's `video`); null: no player, the box never sends `video`. */
    fun video(): VideoCaps? = null
    fun onStatus(s: ClientStatus)
    fun onMessage(m: BoxMsg)
    fun log(msg: String) {}
    /**
     * The box stopped answering at [box]: where is it now? (Discovery by box id; the token
     * still works there.) Returns the new box URL, or null to keep trying [box].
     */
    suspend fun relocate(box: String): String? = null
}

data class Timing(
    val backoffMinMs: Long = 1000,
    val backoffMaxMs: Long = 30_000,
    /** Client-side WebSocket pings; a missing pong closes the socket (the box pings every 30 s too). */
    val pingMs: Long = 25_000,
    /** After a 4409 (replaced by another connection with our token). */
    val replacedRetryMs: Long = 5 * 60_000,
    /** After a 4400 (protocol mismatch: the box needs a newer app). */
    val protocolRetryMs: Long = 10 * 60_000,
    val requestTimeoutMs: Long = 6000,
    /** At most one relocate (a ~3 s LAN browse) this often. */
    val relocateEveryMs: Long = 30_000,
    /** After a 429 from /pair/start. */
    val tooManyRetryMs: Long = 30_000,
)

/** Result of GET /health. */
sealed interface BoxCheck {
    data class Ok(val box: String, val protocol: Int) : BoxCheck
    data class Failed(val error: String) : BoxCheck
}

private val JSON = "application/json".toMediaType()

fun defaultHttp(timing: Timing = Timing()): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(timing.requestTimeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(timing.requestTimeoutMs, TimeUnit.MILLISECONDS)
    .callTimeout(timing.requestTimeoutMs, TimeUnit.MILLISECONDS)
    .build()

/** GET /health: is a Venuebox box speaking our protocol at [box]? */
suspend fun checkBox(http: OkHttpClient, box: String): BoxCheck = withContext(Dispatchers.IO) {
    try {
        http.newCall(Request.Builder().url("$box/health").build()).execute().use { res ->
            if (!res.isSuccessful) return@withContext BoxCheck.Failed("the box answered ${res.code}")
            val j = JSONObject(res.body?.string().orEmpty())
            if (!j.optBoolean("ok")) return@withContext BoxCheck.Failed("not a Venuebox box")
            val p = j.optInt("protocol", -1)
            if (p != CLIENT_PROTOCOL) return@withContext BoxCheck.Failed("the box speaks protocol $p; this app speaks $CLIENT_PROTOCOL")
            BoxCheck.Ok(j.optString("box", ""), p)
        }
    } catch (e: Exception) {
        BoxCheck.Failed(describe(e))
    }
}

internal fun describe(e: Throwable): String = when (e) {
    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "no answer (timed out)"
    is java.net.ConnectException -> "connection refused or no route"
    is java.net.UnknownHostException -> "unknown host"
    else -> e.message ?: e.javaClass.simpleName
}

class BoxClient(
    private val d: BoxClientDeps,
    private val scope: CoroutineScope,
    private val t: Timing = Timing(),
    private val http: OkHttpClient = defaultHttp(t),
) {
    private val wsHttp = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(t.pingMs, TimeUnit.MILLISECONDS)
        .build()

    private var job: Job? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var welcomed = false
    @Volatile var current: ClientStatus = ClientStatus.NoBox
        private set
    private var lastRelocate = 0L

    val connected: Boolean get() = welcomed && ws != null

    /** (Re)starts from scratch: use after the box address changed or the token was cleared. */
    fun restart() {
        stop()
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        welcomed = false
        ws?.cancel()
        ws = null
    }

    fun send(msg: String): Boolean {
        val s = ws ?: return false
        if (!welcomed) return false
        return s.send(msg)
    }

    private fun setStatus(s: ClientStatus) {
        current = s
        d.onStatus(s)
    }

    internal fun backoff(attempt: Int): Long {
        val base = min(t.backoffMaxMs.toDouble(), t.backoffMinMs * Math.pow(2.0, min(attempt, 16).toDouble()))
        return (base * (0.75 + Random.nextDouble() * 0.5)).toLong()
    }

    private suspend fun run() {
        var box = BoxAddress.normalize(d.boxUrl()) ?: return setStatus(ClientStatus.NoBox)
        lastRelocate = 0
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            var token = d.token.get()
            if (token == null) {
                val paired = pair(box) ?: return
                token = paired.first
                box = paired.second
                attempt = 0
            }
            val prevError = (current as? ClientStatus.Connecting)?.error
            setStatus(ClientStatus.Connecting(box, attempt, prevError))
            val r = connectOnce(box, token)
            d.log("control channel closed: code=${r.code} reason=${r.reason} error=${r.error} welcomed=${r.welcomed}")
            if (r.code == Close.BAD_TOKEN || r.code == Close.REVOKED) {
                d.log(if (r.code == Close.REVOKED) "removed from the box: pairing again" else "token not accepted: pairing again")
                d.token.clear()
                attempt = 0
                continue
            }
            if (r.code == Close.REPLACED) {
                setStatus(ClientStatus.Replaced(box))
                delay(t.replacedRetryMs)
                attempt = 0
                continue
            }
            if (r.welcomed) {
                attempt = 0
            } else if (r.code == 1006 || r.code == 1005) {
                // Never reached the box (or it vanished mid-handshake): it may have a new address.
                val moved = tryRelocate(box)
                if (moved != null) {
                    box = moved
                    attempt = 0
                    continue
                }
            }
            val error = when {
                r.code == Close.PROTOCOL -> "the box refused this app version (${r.reason.ifEmpty { "protocol" }})"
                r.error != null -> r.error
                r.welcomed -> "connection lost"
                else -> "closed (${r.code})"
            }
            setStatus(ClientStatus.Connecting(box, attempt + 1, error))
            val wait = if (r.code == Close.PROTOCOL) t.protocolRetryMs else backoff(attempt)
            attempt++
            delay(wait)
        }
    }

    // ---------------------------------------------------------------- pairing

    private suspend fun request(box: String, path: String, postBody: String? = null): Pair<Int, JSONObject> =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(box + path)
            if (postBody != null) b.post(postBody.toRequestBody(JSON))
            http.newCall(b.build()).execute().use { res ->
                val body = try {
                    JSONObject(res.body?.string().orEmpty())
                } catch (_: Exception) {
                    JSONObject()
                }
                res.code to body
            }
        }

    /** The box at [box] didn't answer: ask relocate (rate-limited) for a new address. */
    private suspend fun tryRelocate(box: String): String? {
        val now = System.currentTimeMillis()
        if (lastRelocate != 0L && now - lastRelocate < t.relocateEveryMs) return null
        lastRelocate = now
        val found = try {
            BoxAddress.normalize(d.relocate(box))
        } catch (e: Exception) {
            d.log("relocate failed: ${e.message}")
            null
        }
        if (found == null || found == box) return null
        d.log("the box moved: reconnecting at its new address")
        return found
    }

    /** Shows codes until staff approve one; returns (token, box), or null if cancelled. */
    private suspend fun pair(startBox: String): Pair<String, String>? {
        var box = startBox
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val screens = d.screens()
            if (screens.isEmpty()) {
                delay(5000)
                continue
            }
            val (code, body) = try {
                request(box, "/pair/start", ClientMsg.pairStart(d.device(), screens))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val moved = tryRelocate(box)
                if (moved != null) {
                    box = moved
                    continue
                }
                setStatus(ClientStatus.Unreachable(box, describe(e)))
                delay(backoff(attempt++))
                continue
            }
            val start = if (code == 200) PairStart.parse(body) else null
            if (start == null) {
                setStatus(ClientStatus.Unreachable(box, body.optString("error").ifEmpty { "the box answered $code" }))
                delay(if (code == 429) t.tooManyRetryMs else backoff(attempt++))
                continue
            }
            attempt = 0
            val pollMs = maxOf(500L, start.pollMs)
            val expires = parseIso(start.expiresAt) ?: (System.currentTimeMillis() + 10 * 60_000)
            d.log("pairing: showing code")
            setStatus(ClientStatus.Pairing(box, start.code, start.expiresAt))
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < expires + 5000) {
                delay(pollMs)
                val st = try {
                    val (sc, sb) = request(box, "/pair/status?id=" + URLEncoder.encode(start.pairingId, "UTF-8"))
                    PairStatus.parse(sc, sb)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null // keep showing the code; the box may be restarting
                }
                when (st) {
                    is PairStatus.Expired -> break // expired (or the box restarted): get a new code
                    is PairStatus.Approved -> {
                        d.token.set(st.token)
                        d.log("paired")
                        return st.token to box
                    }
                    else -> {}
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- control channel

    internal data class Closed(val code: Int, val reason: String, val welcomed: Boolean, val error: String?)

    private suspend fun connectOnce(box: String, token: String): Closed = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        var gotWelcome = false
        var error: String? = null
        welcomed = false
        fun finish(code: Int, reason: String, socket: WebSocket) {
            if (ws === socket) {
                ws = null
                welcomed = false
            }
            if (done.compareAndSet(false, true) && cont.isActive) cont.resume(Closed(code, reason, gotWelcome, error))
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ClientMsg.hello(token, d.device(), d.screens(), d.video()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (done.get()) return
                val msg = BoxMsg.parse(text) ?: return
                when (msg) {
                    is BoxMsg.Welcome -> {
                        gotWelcome = true
                        welcomed = true
                        setStatus(ClientStatus.Connected(box, msg.deviceId, msg.name, msg.labels))
                    }
                    is BoxMsg.Error -> error = msg.message
                    else -> {}
                }
                try {
                    d.onMessage(msg)
                } catch (e: Exception) {
                    d.log("handling a box message failed: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(code, reason, webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(code, reason, webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (error == null) error = describe(t)
                finish(1006, "", webSocket)
            }
        }
        val socket = wsHttp.newWebSocket(Request.Builder().url(BoxAddress.wsUrl(box)).build(), listener)
        ws = socket
        cont.invokeOnCancellation { socket.cancel() }
    }

    private fun parseIso(s: String): Long? = try {
        java.time.Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
