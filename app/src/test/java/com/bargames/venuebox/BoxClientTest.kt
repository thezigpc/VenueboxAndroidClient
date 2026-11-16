package com.bargames.venuebox

import com.bargames.venuebox.client.BoxCheck
import com.bargames.venuebox.client.BoxClient
import com.bargames.venuebox.client.BoxClientDeps
import com.bargames.venuebox.client.ClientStatus
import com.bargames.venuebox.client.Timing
import com.bargames.venuebox.client.TokenStore
import com.bargames.venuebox.client.checkBox
import com.bargames.venuebox.client.defaultHttp
import com.bargames.venuebox.protocol.BoxMsg
import com.bargames.venuebox.protocol.ClientMsg
import com.bargames.venuebox.protocol.DeviceInfo
import com.bargames.venuebox.protocol.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class BoxClientTest {
    private lateinit var box: FakeBox
    private lateinit var scope: CoroutineScope
    private val statuses = CopyOnWriteArrayList<ClientStatus>()
    private val messages = CopyOnWriteArrayList<BoxMsg>()
    private val tokens = MemTokens()
    private var boxUrl: String? = null
    private lateinit var client: BoxClient

    private val timing = Timing(backoffMinMs = 50, backoffMaxMs = 200, replacedRetryMs = 60_000, requestTimeoutMs = 2000, pingMs = 5000)

    @Before
    fun setUp() {
        box = FakeBox().also { it.start() }
        boxUrl = box.url
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client = BoxClient(object : BoxClientDeps {
            override fun boxUrl() = this@BoxClientTest.boxUrl
            override val token: TokenStore get() = tokens
            override fun device() = DeviceInfo("Bar TV", "androidtv", "Test Stick", "0.1.0")
            override fun screens() = listOf(Screen("main", "Bar TV", 1920, 1080, 60))
            override fun onStatus(s: ClientStatus) {
                statuses.add(s)
            }
            override fun onMessage(m: BoxMsg) {
                messages.add(m)
            }
        }, scope, timing)
    }

    @After
    fun tearDown() {
        client.stop()
        scope.cancel()
        box.stop()
    }

    @Test
    fun noBoxMeansNoBox() {
        boxUrl = null
        client.restart()
        await("no-box status") { statuses.lastOrNull() is ClientStatus.NoBox }
    }

    @Test
    fun pairsShowsTheCodeThenConnects() {
        box.pendingPolls = 1
        client.restart()
        await("pairing code") { statuses.any { it is ClientStatus.Pairing } }
        val pairing = statuses.first { it is ClientStatus.Pairing } as ClientStatus.Pairing
        assertEquals("123451", pairing.code)
        await("connected", 8000) { client.current is ClientStatus.Connected }
        assertEquals("tok-1", tokens.get())
        val hello = box.hellos().single()
        assertEquals("tok-1", hello.getString("token"))
        assertEquals(1, hello.getInt("v"))
        assertEquals("androidtv", hello.getString("platform"))
        assertEquals("native-url", hello.getJSONArray("deliveries").getString(0))
        val c = client.current as ClientStatus.Connected
        assertEquals("dev-1", c.deviceId)
        assertEquals(mapOf("main" to "TV 1"), c.labels)
        assertTrue(messages.first() is BoxMsg.Welcome)
    }

    @Test
    fun withATokenItSkipsPairingAndRelaysMessages() {
        tokens.set("tok-1")
        client.restart()
        await("connected") { client.connected }
        assertEquals(0, box.starts.get())
        box.sockets.last().send("""{"type":"show","screen":"main","url":"http://box/tv/trivia","kind":"native-url","title":"Trivia"}""")
        box.sockets.last().send("""{"type":"settings","screen":"main","zoom":1.1}""")
        box.sockets.last().send("""{"type":"preview","screen":"main","requestId":"r1"}""")
        await("messages") { messages.size >= 4 }
        assertEquals(BoxMsg.Show("main", "http://box/tv/trivia", "native-url", "Trivia"), messages[1])
        assertEquals(BoxMsg.Unknown("settings"), messages[2])
        assertEquals(BoxMsg.PreviewRequest("main", "r1"), messages[3])
    }

    @Test
    fun sendsStateAndPreviewOnceWelcomed() {
        tokens.set("tok-1")
        client.restart()
        await("connected") { client.connected }
        assertTrue(client.send(ClientMsg.state("main", "http://box/tv/x", "playing")))
        assertTrue(client.send(ClientMsg.preview("r1", "main", "AAAA", null)))
        await("box got them") { box.received.size >= 3 }
        assertEquals("state", box.received[1].getString("type"))
        assertEquals("playing", box.received[1].getString("status"))
        assertEquals("preview", box.received[2].getString("type"))
        assertEquals("r1", box.received[2].getString("requestId"))
    }

    @Test
    fun badTokenClearsItAndPairsAgain() {
        tokens.set("stale")
        box.pendingPolls = 0
        client.restart()
        await("re-paired and connected", 8000) { client.current is ClientStatus.Connected }
        assertEquals("tok-1", tokens.get())
        assertEquals(1, box.starts.get())
        assertEquals(listOf("stale", "tok-1"), box.hellos().map { it.getString("token") })
    }

    @Test
    fun revokedClearsTheTokenToo() {
        tokens.set("tok-1")
        box.pendingPolls = 100 // stays on the code after re-pairing
        box.onHello = { ws, _ -> ws.close(4403, "revoked") }
        client.restart()
        await("pairing again", 8000) { statuses.any { it is ClientStatus.Pairing } }
        assertNull(tokens.get())
    }

    @Test
    fun replacedWaitsInsteadOfFighting() {
        tokens.set("tok-1")
        box.onHello = { ws, _ -> ws.close(4409, "replaced") }
        client.restart()
        await("replaced") { client.current is ClientStatus.Replaced }
        Thread.sleep(500)
        assertEquals(1, box.connects.get()) // no reconnect storm
        assertEquals("tok-1", tokens.get())
    }

    @Test
    fun reconnectsAfterTheBoxDropsIt() {
        tokens.set("tok-1")
        client.restart()
        await("connected") { client.connected }
        box.sockets.last().close(1001, "restarting")
        await("second hello", 8000) { box.hellos().size >= 2 }
        await("connected again") { client.connected }
        assertEquals("tok-1", tokens.get())
    }

    @Test
    fun expiredCodeGetsANewOne() {
        box.pendingPolls = 100
        box.expireNext = true
        client.restart()
        await("second code", 8000) { box.starts.get() >= 2 }
        val codes = statuses.filterIsInstance<ClientStatus.Pairing>().map { it.code }.distinct()
        assertEquals(listOf("123451", "123452"), codes)
    }

    @Test
    fun unreachableBoxIsReported() {
        boxUrl = "http://127.0.0.1:1" // nothing listens there
        client.restart()
        await("unreachable") { statuses.any { it is ClientStatus.Unreachable } }
    }

    @Test
    fun healthCheck() = runBlocking {
        val ok = checkBox(defaultHttp(timing), box.url)
        assertEquals(BoxCheck.Ok("test-box", 1), ok)
        assertTrue(checkBox(defaultHttp(timing), "http://127.0.0.1:1") is BoxCheck.Failed)
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        val c = BoxClient(object : BoxClientDeps {
            override fun boxUrl() = null
            override val token: TokenStore get() = tokens
            override fun device() = DeviceInfo("x", "android", null, null)
            override fun screens() = emptyList<Screen>()
            override fun onStatus(s: ClientStatus) {}
            override fun onMessage(m: BoxMsg) {}
        }, scope, Timing())
        repeat(50) {
            val first = c.backoff(0)
            assertTrue("$first", first in 750..1250)
            val capped = c.backoff(30)
            assertTrue("$capped", capped in 22_500..37_500)
        }
    }

    private fun await(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for $what; statuses=$statuses")
    }

    private class MemTokens : TokenStore {
        @Volatile private var t: String? = null
        override fun get() = t
        override fun set(token: String) {
            t = token
        }
        override fun clear() {
            t = null
        }
    }
}
