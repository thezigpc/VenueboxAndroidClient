package com.bargames.venuebox

import com.bargames.venuebox.client.Decoder
import com.bargames.venuebox.client.videoCaps
import com.bargames.venuebox.protocol.BoxMsg
import com.bargames.venuebox.protocol.ClientMsg
import com.bargames.venuebox.protocol.DeviceInfo
import com.bargames.venuebox.protocol.Screen
import com.bargames.venuebox.protocol.VideoCaps
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Box 0.28.0 "video": hello's caps, the messages, and what the decoders allow. */
class VideoTest {
    private val link = "http://media.example:8096/Videos/1/stream.mkv?api_key=SECRET"

    @Test
    fun helloCarriesTheCapsWithTheBoxFieldNames() {
        val caps = VideoCaps(listOf("mp4", "hls"), listOf("h264"), listOf("aac"), 1080, true)
        val o = JSONObject(ClientMsg.hello("tok", DeviceInfo("TV", "androidtv", null, null), listOf(Screen("main", "TV", 1920, 1080)), caps))
        val v = o.getJSONObject("video")
        assertEquals(setOf("containers", "video", "audio", "maxHeight", "seek"), v.keySet())
        assertEquals(1080, v.getInt("maxHeight"))
        assertTrue(v.getBoolean("seek"))
        assertFalse("no passthrough: left out", v.has("passthrough"))
    }

    @Test
    fun parsesEveryOp() {
        val play = BoxMsg.parse(
            """{"type":"video","screen":"main","requestId":"r1","op":"play","url":"$link","title":"A movie","mime":"video/x-matroska","poster":"$link","startAt":30.5}""",
        ) as BoxMsg.Video
        assertEquals("main", play.screen)
        assertEquals("r1", play.requestId)
        assertEquals("play", play.op)
        assertEquals(link, play.url)
        assertEquals("A movie", play.title)
        assertEquals("video/x-matroska", play.mime)
        assertEquals(30.5, play.startAt!!, 0.0)
        assertNull(play.position)

        val seek = BoxMsg.parse("""{"type":"video","screen":"main","requestId":"r2","op":"seek","position":600}""") as BoxMsg.Video
        assertEquals(600.0, seek.position!!, 0.0)
        assertNull(seek.url)
        for (op in listOf("pause", "resume", "stop", "status")) {
            val m = BoxMsg.parse("""{"type":"video","screen":"main","requestId":"x","op":"$op"}""") as BoxMsg.Video
            assertEquals(op, m.op)
        }
        val bad = BoxMsg.parse("""{"type":"video","screen":"main","requestId":"r3","op":"seek","position":-5}""") as BoxMsg.Video
        assertNull("a negative position is no position", bad.position)
    }

    @Test
    fun theLinkNeverPrints() {
        val m = BoxMsg.parse("""{"type":"video","screen":"main","requestId":"r1","op":"play","url":"$link","title":"A movie","poster":"$link"}""")!!
        assertFalse(m.toString(), m.toString().contains("SECRET"))
        assertFalse(m.toString().contains("media.example"))
    }

    @Test
    fun replyAndStateShapes() {
        val r = JSONObject(ClientMsg.videoReply("r1", true, state = "playing", position = 612.94, duration = 5400.0))
        assertEquals("video-reply", r.getString("type"))
        assertEquals("r1", r.getString("requestId"))
        assertTrue(r.getBoolean("ok"))
        assertEquals("playing", r.getString("state"))
        assertEquals(612.9, r.getDouble("position"), 0.0001)
        assertEquals(5400.0, r.getDouble("duration"), 0.0)
        assertFalse(r.has("reason"))

        val no = JSONObject(ClientMsg.videoReply("r2", false, "unsupported", "only http and https video links play here"))
        assertFalse(no.getBoolean("ok"))
        assertEquals("unsupported", no.getString("reason"))
        assertFalse(no.has("state"))
        assertFalse(no.has("position"))

        val s = JSONObject(ClientMsg.videoState("main", "error", 12.0, null, "the server refused the stream (HTTP 403)"))
        assertEquals("video-state", s.getString("type"))
        assertEquals("main", s.getString("screen"))
        assertEquals("error", s.getString("state"))
        assertFalse("unknown duration: left out", s.has("duration"))
        assertEquals("the server refused the stream (HTTP 403)", s.getString("message"))
    }

    // ---------------------------------------------------------------- caps from decoders

    private fun hw(mime: String, uhd: Boolean = false) = Decoder(mime, true, uhd)
    private fun sw(mime: String, uhd: Boolean = false) = Decoder(mime, false, uhd)

    @Test
    fun onlyHardwareHevcAv1Vp9AndH264Always() {
        val caps = videoCaps(
            listOf(sw("video/avc"), sw("video/hevc"), sw("video/av01"), hw("video/x-vnd.on2.vp9"), sw("audio/mp4a-latm")),
            display4k = false, passthrough = emptyList(),
        )
        assertEquals(listOf("h264", "vp9"), caps.video)
        assertEquals(listOf("mp4", "mkv", "mov", "webm", "ts", "hls"), caps.containers)
        assertTrue(caps.seek)
    }

    @Test
    fun fourKNeedsTheDisplayAndADecoder() {
        val decoders = listOf(hw("video/avc", uhd = false), hw("video/hevc", uhd = true))
        assertEquals(2160, videoCaps(decoders, display4k = true, passthrough = emptyList()).maxHeight)
        assertEquals("a 1080p TV", 1080, videoCaps(decoders, display4k = false, passthrough = emptyList()).maxHeight)
        assertEquals(
            "a 4K TV, 1080p decoders",
            1080,
            videoCaps(listOf(hw("video/avc"), hw("video/hevc")), display4k = true, passthrough = emptyList()).maxHeight,
        )
        assertEquals(
            "software 4K doesn't count",
            1080,
            videoCaps(listOf(hw("video/avc"), sw("video/hevc", uhd = true)), display4k = true, passthrough = emptyList()).maxHeight,
        )
    }

    @Test
    fun audioIsTheBasicsPlusDecodedSurroundAndPassthroughIsSeparate() {
        val caps = videoCaps(listOf(hw("video/avc"), sw("audio/ac3"), hw("audio/eac3")), display4k = false, passthrough = listOf("dts", "eac3"))
        assertEquals(listOf("aac", "mp3", "opus", "flac", "ac3", "eac3"), caps.audio)
        assertEquals(listOf("eac3", "dts"), caps.passthrough)
        val plain = videoCaps(listOf(hw("video/avc")), display4k = false, passthrough = emptyList())
        assertEquals(listOf("aac", "mp3", "opus", "flac"), plain.audio)
        assertEquals(emptyList<String>(), plain.passthrough)
    }

    @Test
    fun rangesNeedTheDisplayAndTheDecoder() {
        val all = listOf("hdr10", "hdr10plus", "hlg", "dovi")
        val main10 = Decoder("video/hevc", hardware = true, uhd = true, main10 = true)
        val dv = hw("video/dolby-vision")
        assertEquals(listOf("sdr"), videoCaps(listOf(hw("video/avc"), main10, dv), display4k = true, passthrough = emptyList()).ranges)
        assertEquals(PICTURE_ALL, videoCaps(listOf(hw("video/avc"), main10, dv), true, emptyList(), all).ranges)
        assertEquals(
            "an HDR TV, 8-bit HEVC only, no Dolby Vision decoder",
            listOf("sdr"),
            videoCaps(listOf(hw("video/avc"), hw("video/hevc", true)), true, emptyList(), all).ranges,
        )
        assertEquals(
            "software Main10 doesn't count",
            listOf("sdr", "dovi"),
            videoCaps(listOf(hw("video/avc"), Decoder("video/hevc", false, true, true), dv), true, emptyList(), all).ranges,
        )
        assertEquals(
            "an HDR10-only TV",
            listOf("sdr", "hdr10"),
            videoCaps(listOf(hw("video/avc"), main10, dv), true, emptyList(), listOf("hdr10")).ranges,
        )
        val o = videoCaps(listOf(hw("video/avc"), main10), true, emptyList(), listOf("hdr10", "hlg")).toJson()
        assertEquals("hlg", o.getJSONArray("ranges").getString(2))
    }

    private val PICTURE_ALL = listOf("sdr", "hdr10", "hdr10plus", "hlg", "dovi")

    @Test
    fun namesPassTheBoxCheck() {
        val names = Regex("^[a-z0-9][a-z0-9.+-]{0,23}$")
        val caps = videoCaps(
            listOf(hw("video/avc", true), hw("video/hevc", true), hw("video/av01"), hw("video/x-vnd.on2.vp9"), hw("audio/true-hd"), hw("audio/vnd.dts")),
            display4k = true, passthrough = listOf("ac3", "eac3", "dts", "truehd"),
        )
        for (n in caps.containers + caps.video + caps.audio + caps.passthrough + caps.ranges) assertTrue(n, names.matches(n))
    }
}
