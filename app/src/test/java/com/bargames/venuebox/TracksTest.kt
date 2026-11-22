package com.bargames.venuebox

import com.bargames.venuebox.client.Pick
import com.bargames.venuebox.client.menuRows
import com.bargames.venuebox.client.pickAudio
import com.bargames.venuebox.client.pickSubtitle
import com.bargames.venuebox.client.sideSubtitles
import com.bargames.venuebox.client.tracksOn
import com.bargames.venuebox.protocol.BoxMsg
import com.bargames.venuebox.protocol.ClientMsg
import com.bargames.venuebox.protocol.DeviceInfo
import com.bargames.venuebox.protocol.RemoteAsk
import com.bargames.venuebox.protocol.Screen
import com.bargames.venuebox.protocol.Tracks
import com.bargames.venuebox.protocol.TracksOn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Box 0.29.0: tracks, the remote's keys, video-remote. */
class TracksTest {
    private val sub = "http://media.example/sub.vtt?api_key=SECRET"
    // As in the box's own test (video.test.ts).
    private val tracksJson = """{"audio":[{"id":"a1","label":"English","index":0},{"id":"a2","label":"Commentary","index":1},{"id":"a3","label":"Not in this stream"}],
        "subtitles":[{"id":"s1","label":"PGS","index":0},{"id":"s2","label":"English","url":"$sub","format":"vtt","forced":true}],"audioId":"a2","subtitleId":"s2"}"""

    private fun tracks() = Tracks.parse(JSONObject(tracksJson))!!

    @Test
    fun playCarriesTracks() {
        val m = BoxMsg.parse("""{"type":"video","screen":"main","requestId":"r1","op":"play","url":"http://media.example/v.mkv?api_key=SECRET","title":"A movie","tracks":$tracksJson}""") as BoxMsg.Video
        val t = m.tracks!!
        assertEquals(listOf("a1", "a2", "a3"), t.audio.map { it.id })
        assertEquals(1, t.audio[1].index)
        assertNull(t.audio[2].index)
        assertEquals("vtt", t.subtitles[1].format)
        assertTrue(t.subtitles[1].forced)
        assertEquals("a2", t.audioId)
        assertEquals("s2", t.subtitleId)
        assertTrue(t.subtitleGiven)
        assertFalse("the subtitle's key never prints", m.toString().contains("SECRET"))
    }

    @Test
    fun subtitleNullMeansOffAndMissingMeansDefault() {
        val off = Tracks.parse(JSONObject("""{"audio":[],"subtitles":[],"subtitleId":null}"""))!!
        assertTrue(off.subtitleGiven)
        assertNull(off.subtitleId)
        val none = Tracks.parse(JSONObject("""{"audio":[],"subtitles":[]}"""))!!
        assertFalse(none.subtitleGiven)
    }

    @Test
    fun trackOpsAndKeysParse() {
        val a = BoxMsg.parse("""{"type":"video","screen":"main","requestId":"r2","op":"audio","id":"a2"}""") as BoxMsg.Video
        assertEquals("audio", a.op)
        assertEquals("a2", a.trackId)
        val s = BoxMsg.parse("""{"type":"video","screen":"main","requestId":"r3","op":"subtitle","id":null}""") as BoxMsg.Video
        assertEquals("subtitle", s.op)
        assertNull(s.trackId)
        assertEquals(BoxMsg.Key("main", "Up"), BoxMsg.parse("""{"type":"key","screen":"main","key":"Up"}"""))
    }

    @Test
    fun helloDeclaresKnownKeysInTheBoxOrderAndTracks() {
        val caps = com.bargames.venuebox.protocol.VideoCaps(listOf("mp4"), listOf("h264"), listOf("aac"), 1080, true, tracks = true)
        val o = JSONObject(ClientMsg.hello("tok", DeviceInfo("TV", "androidtv", null, null), listOf(Screen("main", "TV", 1920, 1080)), caps, listOf("Select", "Up", "Nonsense", "VolumeUp")))
        val k = o.getJSONArray("keys")
        assertEquals(listOf("Up", "Select", "VolumeUp"), (0 until k.length()).map { k.getString(it) })
        assertTrue(o.getJSONObject("video").getBoolean("tracks"))
        val none = JSONObject(ClientMsg.hello("tok", DeviceInfo("TV", "androidtv", null, null), listOf(Screen("main", "TV", 1920, 1080))))
        assertFalse(none.has("keys"))
    }

    @Test
    fun repliesAndStatesCarryTheTrackIds() {
        val r = JSONObject(ClientMsg.videoReply("r1", true, state = "playing", position = 100.0, tracks = TracksOn("a2", null)))
        assertEquals("a2", r.getString("audio"))
        assertTrue("subtitle off is an explicit null", r.has("subtitle") && r.isNull("subtitle"))
        val s = JSONObject(ClientMsg.videoState("main", "playing", 101.0, tracks = TracksOn("a2", "s1")))
        assertEquals("s1", s.getString("subtitle"))
        val plain = JSONObject(ClientMsg.videoState("main", "playing", 101.0))
        assertFalse("no tracks: no ids", plain.has("audio") || plain.has("subtitle"))
    }

    @Test
    fun videoRemoteShapes() {
        fun action(a: RemoteAsk) = JSONObject(ClientMsg.videoRemote("main", a)).also {
            assertEquals("video-remote", it.getString("type"))
            assertEquals("main", it.getString("screen"))
        }.get("action")
        assertEquals("next", action(RemoteAsk.Next))
        assertEquals("previous", action(RemoteAsk.Previous))
        assertEquals("a3", (action(RemoteAsk.Audio("a3")) as JSONObject).getString("audio"))
        val off = action(RemoteAsk.Subtitle(null)) as JSONObject
        assertTrue(off.has("subtitle") && off.isNull("subtitle"))
    }

    @Test
    fun picks() {
        val t = tracks()
        assertEquals(Pick.Embedded(1), pickAudio(t, "a2"))
        assertEquals(Pick.NotInStream, pickAudio(t, "a3"))
        assertEquals(Pick.NoSuchTrack, pickAudio(t, "zz"))
        assertEquals(Pick.Embedded(0), pickSubtitle(t, "s1"))
        assertEquals(Pick.Side("s2"), pickSubtitle(t, "s2"))
        assertEquals(Pick.Off, pickSubtitle(t, null))
        assertEquals(Pick.NoSuchTrack, pickSubtitle(null, "s1"))
        assertEquals(listOf("s2"), sideSubtitles(t).map { it.id })
    }

    @Test
    fun selectionMapsBackToIds() {
        val t = tracks()
        assertEquals(TracksOn("a2", "s2"), tracksOn(t, audioIndex = 1, textIndex = null, textSide = "s2"))
        assertEquals(TracksOn("a1", "s1"), tracksOn(t, audioIndex = 0, textIndex = 0, textSide = null))
        assertEquals(TracksOn("a1", null), tracksOn(t, audioIndex = 0, textIndex = null, textSide = null))
        assertEquals("an unlisted stream track", TracksOn(null, null), tracksOn(t, audioIndex = 5, textIndex = 3, textSide = null))
    }

    @Test
    fun theMenuListsEveryTrackAndMarksWhatIsOnAndWhatRestarts() {
        val rows = menuRows(tracks(), TracksOn("a2", null))
        assertEquals(listOf("Audio", "English", "Commentary", "Not in this stream", "Subtitles", "Off", "PGS", "English"), rows.map { it.label })
        assertEquals(listOf("a2"), rows.filter { it.on && it.kind == "audio" }.map { it.id })
        assertTrue("subtitles off is on", rows.first { it.label == "Off" }.on)
        assertEquals(listOf("a3"), rows.filter { it.restarts }.map { it.id })
        assertFalse(rows[0].selectable)
    }
}
