package com.bargames.venuebox.client

import com.bargames.venuebox.protocol.VideoCaps

/** One decoder as MediaCodecList reports it, free of Android types for JVM tests. */
data class Decoder(
    /** MIME type, e.g. "video/hevc". */
    val mime: String,
    val hardware: Boolean,
    /** It decodes 3840x2160 (video only). */
    val uhd: Boolean = false,
    /** HEVC: it decodes a Main10 profile (10-bit: what HDR10, HDR10+ and HLG need). */
    val main10: Boolean = false,
)

/** Box 0.28.1 `ranges`, in the box's order. */
val PICTURE_RANGES = listOf("sdr", "hdr10", "hdr10plus", "hlg", "dovi")

/**
 * The picture ranges shown correctly end to end: the display takes it ([display]: its HDR
 * types as range names) and a hardware decoder plays its profile (HEVC Main10 for hdr10,
 * hdr10plus and hlg; a Dolby Vision decoder for dovi). "sdr" always.
 */
fun pictureRanges(decoders: List<Decoder>, display: Collection<String>): List<String> {
    val main10 = decoders.any { it.hardware && it.main10 && it.mime.equals("video/hevc", ignoreCase = true) }
    val dovi = decoders.any { it.hardware && it.mime.equals("video/dolby-vision", ignoreCase = true) }
    return PICTURE_RANGES.filter { r ->
        when (r) {
            "sdr" -> true
            "dovi" -> dovi && r in display
            else -> main10 && r in display
        }
    }
}

/** What ExoPlayer itself reads, whatever the device. */
val VIDEO_CONTAINERS = listOf("mp4", "mkv", "mov", "webm", "ts", "hls")

private val VIDEO_NAMES = linkedMapOf(
    "video/avc" to "h264",
    "video/hevc" to "hevc",
    "video/av01" to "av1",
    "video/x-vnd.on2.vp9" to "vp9",
)

private val AUDIO_NAMES = linkedMapOf(
    "audio/mp4a-latm" to "aac",
    "audio/mpeg" to "mp3",
    "audio/opus" to "opus",
    "audio/flac" to "flac",
    "audio/ac3" to "ac3",
    "audio/eac3" to "eac3",
    "audio/vnd.dts" to "dts",
    "audio/true-hd" to "truehd",
)

/** Platform audio every Android since 5.0 decodes (software decoders count: audio is cheap). */
private val ALWAYS_AUDIO = listOf("aac", "mp3", "opus", "flac")

/**
 * hello's `video` from the device's decoders. Only what plays without dropping frames:
 *
 * - h264 always; hevc, av1 and vp9 only with a hardware decoder (software ones can't keep up).
 * - audio: aac mp3 opus flac, plus any surround format a decoder handles; [passthrough] lists
 *   the surround formats the TV/receiver takes as-is (ExoPlayer's AudioCapabilities).
 * - maxHeight 2160 when the display has a 4K mode and a hardware decoder does 4K; else 1080.
 */
fun videoCaps(
    decoders: List<Decoder>,
    display4k: Boolean,
    passthrough: List<String>,
    displayHdr: Collection<String> = emptyList(),
): VideoCaps {
    val video = VIDEO_NAMES.filter { (mime, name) ->
        name == "h264" || decoders.any { it.mime.equals(mime, ignoreCase = true) && it.hardware }
    }.values.toList()
    val decodedAudio = AUDIO_NAMES.filter { (mime, _) -> decoders.any { it.mime.equals(mime, ignoreCase = true) } }.values
    val audio = AUDIO_NAMES.values.filter { it in ALWAYS_AUDIO || it in decodedAudio }
    val uhd = decoders.any { d ->
        d.hardware && d.uhd && VIDEO_NAMES[d.mime.lowercase()]?.let { it in video } == true
    }
    return VideoCaps(
        containers = VIDEO_CONTAINERS,
        video = video,
        audio = audio,
        maxHeight = if (display4k && uhd) 2160 else 1080,
        seek = true,
        passthrough = AUDIO_NAMES.values.filter { it in passthrough },
        ranges = pictureRanges(decoders, displayHdr),
        tracks = true,
    )
}
