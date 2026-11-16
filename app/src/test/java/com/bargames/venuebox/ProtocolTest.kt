package com.bargames.venuebox

import com.bargames.venuebox.protocol.BoxAddress
import com.bargames.venuebox.protocol.BoxMsg
import com.bargames.venuebox.protocol.ClientMsg
import com.bargames.venuebox.protocol.DeviceInfo
import com.bargames.venuebox.protocol.PairStart
import com.bargames.venuebox.protocol.PairStatus
import com.bargames.venuebox.protocol.Screen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val device = DeviceInfo("Bar TV", "androidtv", "Google Chromecast", "0.1.0")
    private val screen = Screen("main", "Bar TV", 1920, 1080, 60)

    @Test
    fun helloHasTheContractFields() {
        val o = JSONObject(ClientMsg.hello("tok", device, listOf(screen)))
        assertEquals("hello", o.getString("type"))
        assertEquals(1, o.getInt("v"))
        assertEquals("tok", o.getString("token"))
        assertEquals("androidtv", o.getString("platform"))
        assertEquals("Google Chromecast", o.getString("model"))
        assertEquals("0.1.0", o.getString("appVersion"))
        assertEquals("Bar TV", o.getString("name"))
        val s = o.getJSONArray("screens").getJSONObject(0)
        assertEquals("main", s.getString("id"))
        assertEquals(1920, s.getInt("w"))
        assertEquals(1080, s.getInt("h"))
        assertEquals(60, s.getInt("hz"))
        assertEquals(listOf("native-url"), o.getJSONArray("deliveries").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test
    fun screenIdMatchesTheBoxRule() {
        assertTrue(Regex("^[\\w.:-]{1,64}$").matches(screen.id))
    }

    @Test
    fun screenWithoutHzOmitsIt() {
        assertFalse(Screen("main", "x", 10, 10, null).toJson().has("hz"))
    }

    @Test
    fun pairStartBody() {
        val o = JSONObject(ClientMsg.pairStart(device, listOf(screen)))
        assertEquals("Bar TV", o.getString("name"))
        assertEquals("androidtv", o.getString("platform"))
        assertEquals(1, o.getJSONArray("screens").length())
        assertFalse(o.has("token"))
    }

    @Test
    fun stateOmitsEmptyDetail() {
        val o = JSONObject(ClientMsg.state("main", "", "off"))
        assertEquals("state", o.getString("type"))
        assertEquals("main", o.getString("screen"))
        assertEquals("", o.getString("showing"))
        assertEquals("off", o.getString("status"))
        assertFalse(o.has("detail"))
        assertEquals("boom", JSONObject(ClientMsg.state("main", "http://x/", "error", "boom")).getString("detail"))
    }

    @Test
    fun previewCarriesJpegOrError() {
        val ok = JSONObject(ClientMsg.preview("r1", "main", "AAAA", null))
        assertEquals("preview", ok.getString("type"))
        assertEquals("r1", ok.getString("requestId"))
        assertEquals("AAAA", ok.getString("jpeg"))
        assertFalse(ok.has("error"))
        val bad = JSONObject(ClientMsg.preview("r1", "main", null, "nope"))
        assertEquals("nope", bad.getString("error"))
        assertFalse(bad.has("jpeg"))
    }

    @Test
    fun parsesEveryBoxMessage() {
        assertEquals(
            BoxMsg.Welcome(1, "d1", "Bar", mapOf("main" to "Bar TV 1")),
            BoxMsg.parse("""{"type":"welcome","v":1,"deviceId":"d1","name":"Bar","screens":[{"id":"main","label":"Bar TV 1"}]}"""),
        )
        assertEquals(
            BoxMsg.Show("main", "http://box/tv/trivia", "native-url", "Trivia"),
            BoxMsg.parse("""{"type":"show","screen":"main","url":"http://box/tv/trivia","kind":"native-url","title":"Trivia"}"""),
        )
        assertEquals(BoxMsg.Show("main", "http://box/idle", "native-url", null), BoxMsg.parse("""{"type":"idle","screen":"main","url":"http://box/idle"}"""))
        assertEquals(BoxMsg.Off("main"), BoxMsg.parse("""{"type":"off","screen":"main"}"""))
        assertEquals(BoxMsg.Reload("main"), BoxMsg.parse("""{"type":"reload","screen":"main"}"""))
        assertEquals(BoxMsg.Identify("main", "TV 3"), BoxMsg.parse("""{"type":"identify","screen":"main","label":"TV 3"}"""))
        assertEquals(BoxMsg.PreviewRequest("main", "q9"), BoxMsg.parse("""{"type":"preview","screen":"main","requestId":"q9"}"""))
        assertEquals(BoxMsg.Error("revoked", "gone"), BoxMsg.parse("""{"type":"error","code":"revoked","message":"gone"}"""))
        assertEquals(BoxMsg.Unknown("settings"), BoxMsg.parse("""{"type":"settings","screen":"main","zoom":1.2}"""))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(BoxMsg.parse("not json"))
        assertNull(BoxMsg.parse("[]"))
        assertNull(BoxMsg.parse("""{"screen":"main"}"""))
    }

    @Test
    fun pairReplies() {
        assertEquals(
            PairStart("p1", "123456", "2026-11-16T10:00:00Z", 2000),
            PairStart.parse(JSONObject("""{"pairingId":"p1","code":"123456","expiresAt":"2026-11-16T10:00:00Z","pollMs":2000}""")),
        )
        assertNull(PairStart.parse(JSONObject("""{"error":"too many"}""")))
        assertEquals(PairStatus.Pending, PairStatus.parse(200, JSONObject("""{"status":"pending"}""")))
        assertEquals(
            PairStatus.Approved("d1", "secret", "/client"),
            PairStatus.parse(200, JSONObject("""{"status":"approved","deviceId":"d1","token":"secret","wsPath":"/client"}""")),
        )
        assertEquals(PairStatus.Expired, PairStatus.parse(404, JSONObject("""{"status":"expired"}""")))
        assertNull(PairStatus.parse(500, JSONObject()))
    }

    @Test
    fun normalizesTypedAddresses() {
        assertEquals("http://192.168.1.20:7210", BoxAddress.normalize("192.168.1.20"))
        assertEquals("http://192.168.1.20:7210", BoxAddress.normalize("  192.168.1.20  "))
        assertEquals("http://192.168.1.20:8080", BoxAddress.normalize("192.168.1.20:8080"))
        assertEquals("http://box.local:7210", BoxAddress.normalize("http://Box.local/some/path"))
        assertEquals("https://box:443", BoxAddress.normalize("https://box:443"))
        assertEquals("http://[fe80::1]:7210", BoxAddress.normalize("[fe80::1]"))
        assertNull(BoxAddress.normalize(""))
        assertNull(BoxAddress.normalize(null))
        assertNull(BoxAddress.normalize("ftp://box"))
        assertNull(BoxAddress.normalize("not an address"))
    }

    @Test
    fun decodesTheBoxsIdleDataUrl() {
        val html = "<!doctype html><meta name=viewport content=\"width=device-width\"><body data-live=\"x\">Café 100% + more #1</body>"
        val encoded = java.net.URLEncoder.encode(html, "UTF-8").replace("+", "%20")
        val d = com.bargames.venuebox.protocol.DataUrl.parse("data:text/html;charset=utf-8,$encoded")!!
        assertEquals("text/html", d.mime)
        assertEquals(html, d.text)
        // '+' is literal in data URLs (not a space) and bare characters pass through.
        assertEquals("a+b <i>", com.bargames.venuebox.protocol.DataUrl.parse("data:text/html,a+b%20<i>")!!.text)
        val b64 = java.util.Base64.getEncoder().encodeToString("<p>hi</p>".toByteArray())
        assertEquals("<p>hi</p>", com.bargames.venuebox.protocol.DataUrl.parse("data:text/html;base64,$b64")!!.text)
        assertEquals("text/plain", com.bargames.venuebox.protocol.DataUrl.parse("data:,x")!!.mime)
        assertNull(com.bargames.venuebox.protocol.DataUrl.parse("data:text/html"))
        assertNull(com.bargames.venuebox.protocol.DataUrl.parse("http://box/"))
    }

    @Test
    fun webSocketUrl() {
        assertEquals("ws://192.168.1.20:7210/client", BoxAddress.wsUrl("http://192.168.1.20:7210"))
        assertEquals("wss://box:443/client", BoxAddress.wsUrl("https://box:443"))
        assertEquals("192.168.1.20", BoxAddress.host("http://192.168.1.20:7210"))
    }
}
