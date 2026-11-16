package com.bargames.venuebox

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A box on localhost speaking the v1 contract (pairing + /client), scriptable per test. */
class FakeBox : Dispatcher() {
    val server = MockWebServer().also { it.dispatcher = this }
    val url: String get() = "http://${server.hostName}:${server.port}"

    var token = "tok-1"
    /** Status polls answered "pending" before "approved". */
    var pendingPolls = 1
    /** Answer the next status poll with 404 (code expired). */
    @Volatile var expireNext = false
    @Volatile var startStatus = 200

    val starts = AtomicInteger()
    val polls = AtomicInteger()
    val connects = AtomicInteger()
    /** Every JSON message clients sent over /client, in order. */
    val received = CopyOnWriteArrayList<JSONObject>()
    /** Open server-side sockets, newest last. */
    val sockets = CopyOnWriteArrayList<WebSocket>()

    /** What the box does with a hello (default: accept a matching token, close 4401 otherwise). */
    @Volatile var onHello: (WebSocket, JSONObject) -> Unit = { ws, hello ->
        if (hello.optString("token") == token) {
            ws.send("""{"type":"welcome","v":1,"deviceId":"dev-1","name":"Bar TV","screens":[{"id":"main","label":"TV 1"}]}""")
        } else {
            ws.close(4401, "bad token")
        }
    }

    fun start() = server.start()
    fun stop() = server.shutdown()

    fun hellos() = received.filter { it.optString("type") == "hello" }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        return when {
            path == "/health" -> json(200, """{"ok":true,"box":"test-box","protocol":1}""")
            path == "/pair/start" -> {
                val n = starts.incrementAndGet()
                if (startStatus != 200) return json(startStatus, """{"error":"nope"}""")
                val expires = Instant.now().plusSeconds(600).toString()
                json(200, """{"pairingId":"p$n","code":"12345$n","expiresAt":"$expires","pollMs":500}""")
            }
            path.startsWith("/pair/status") -> {
                val n = polls.incrementAndGet()
                when {
                    expireNext -> {
                        expireNext = false
                        json(404, """{"status":"expired"}""")
                    }
                    n > pendingPolls -> json(200, """{"status":"approved","deviceId":"dev-1","token":"$token","wsPath":"/client"}""")
                    else -> json(200, """{"status":"pending"}""")
                }
            }
            path == "/client" -> {
                connects.incrementAndGet()
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.add(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val o = JSONObject(text)
                        received.add(o)
                        if (o.optString("type") == "hello") onHello(webSocket, o)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                })
            }
            else -> json(404, "{}")
        }
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)
}
