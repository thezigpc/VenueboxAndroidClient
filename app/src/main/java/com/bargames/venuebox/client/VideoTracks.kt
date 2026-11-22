package com.bargames.venuebox.client

import com.bargames.venuebox.protocol.Track
import com.bargames.venuebox.protocol.Tracks
import com.bargames.venuebox.protocol.TracksOn

/** Side-loaded subtitles get this Format id prefix, to tell them from the stream's own. */
const val SIDE_SUBTITLE_PREFIX = "vb-sub:"

/** What switching to a track means for the player. */
sealed interface Pick {
    /** No track with that id in play's `tracks`. */
    data object NoSuchTrack : Pick
    /** Listed, but neither in the stream nor a side file: only the app can switch (it restarts the stream). */
    data object NotInStream : Pick
    /** Subtitles off. */
    data object Off : Pick
    /** The n-th track of its kind inside the stream (container order). */
    data class Embedded(val index: Int) : Pick
    /** A side-loaded subtitle file (added at play time). */
    data class Side(val id: String) : Pick
}

fun pickAudio(t: Tracks?, id: String?): Pick {
    val track = t?.audio?.firstOrNull { it.id == id } ?: return Pick.NoSuchTrack
    return track.index?.let { Pick.Embedded(it) } ?: Pick.NotInStream
}

fun pickSubtitle(t: Tracks?, id: String?): Pick {
    if (id == null) return Pick.Off
    val track = t?.subtitles?.firstOrNull { it.id == id } ?: return Pick.NoSuchTrack
    return when {
        track.index != null -> Pick.Embedded(track.index)
        track.url != null -> Pick.Side(track.id)
        else -> Pick.NotInStream
    }
}

/** Subtitle files to add when the stream opens (each carries the key: never log [Track.url]). */
fun sideSubtitles(t: Tracks?): List<Track> = t?.subtitles?.filter { it.index == null && it.url != null }.orEmpty()

/**
 * The ids on now, from what the player selected: [audioIndex] the selected audio group's
 * position among audio groups; [textIndex] the selected text group's position among the
 * stream's own text groups, or [textSide] a side file's id. Null subtitle: off.
 */
fun tracksOn(t: Tracks, audioIndex: Int?, textIndex: Int?, textSide: String?): TracksOn {
    val audio = audioIndex?.let { i -> t.audio.firstOrNull { it.index == i }?.id }
    val subtitle = when {
        textSide != null -> t.subtitles.firstOrNull { it.id == textSide }?.id
        textIndex != null -> t.subtitles.firstOrNull { it.index == textIndex }?.id
        else -> null
    }
    return TracksOn(audio, subtitle)
}

/** A row of the player's "Audio & subtitles" menu. */
data class MenuRow(
    /** "header" | "audio" | "subtitle". */
    val kind: String,
    val label: String,
    /** The track id; null for "Off" and headers. */
    val id: String? = null,
    val on: Boolean = false,
    /** Not in this stream: picking it asks the app to restart the stream with it. */
    val restarts: Boolean = false,
) {
    val selectable get() = kind != "header"
}

fun menuRows(t: Tracks, on: TracksOn): List<MenuRow> = buildList {
    if (t.audio.isNotEmpty()) {
        add(MenuRow("header", "Audio"))
        t.audio.forEach { add(MenuRow("audio", it.label, it.id, it.id == on.audio, !it.inStream)) }
    }
    add(MenuRow("header", "Subtitles"))
    add(MenuRow("subtitle", "Off", null, on.subtitle == null))
    t.subtitles.forEach { add(MenuRow("subtitle", it.label, it.id, it.id == on.subtitle, !it.inStream)) }
}
