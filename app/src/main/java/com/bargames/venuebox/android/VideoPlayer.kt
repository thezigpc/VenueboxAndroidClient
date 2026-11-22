package com.bargames.venuebox.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bargames.venuebox.BuildConfig
import com.bargames.venuebox.client.Decoder
import com.bargames.venuebox.client.videoCaps
import com.bargames.venuebox.protocol.BoxMsg
import com.bargames.venuebox.protocol.ClientMsg
import com.bargames.venuebox.protocol.VideoCaps
import com.bargames.venuebox.protocol.VideoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.max

/**
 * Box 0.28.0 "video": plays a link full screen over the page (which keeps running underneath),
 * with Media3/ExoPlayer, and answers every op with a video-reply.
 *
 * Privacy: the link and poster carry the media server's key. They're never logged (Media3's
 * own logging is off, and no EventLogger is attached: both print URIs), never stored, never
 * in state or in any message. Errors are described from their codes, never their text.
 */
@OptIn(UnstableApi::class)
class VideoPlayer(
    private val act: Activity,
    private val root: FrameLayout,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val send: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var poster: ImageView? = null
    private var posterJob: Job? = null
    private var overlay: TextView? = null
    private var title: String = ""
    /** The last state the box heard about (from a reply or a video-state). */
    private var told: String? = null
    /** The player failed: what to tell `status` until it closes. */
    private var error: String? = null
    /** When the last video-state went out (uptime ms): while playing, one at most every 10 s. */
    private var lastPush = 0L

    val isOpen get() = view != null

    /** The video's own surface (for previews: the window copy doesn't include it). */
    val surface: SurfaceView? get() = view?.videoSurfaceView as? SurfaceView

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (state() == VideoState.PLAYING && SystemClock.uptimeMillis() - lastPush >= PLAYING_EVERY_MS) push(force = true)
            if (p.isPlaying) updateOverlay()
            handler.postDelayed(this, 1000)
        }
    }
    private val hideOverlay = Runnable { if (player?.playWhenReady != false) overlay?.visibility = View.GONE }
    private val closeAfterError = Runnable { close(tell = false) }

    // ---------------------------------------------------------------- box → player

    fun handle(m: BoxMsg.Video) {
        if (m.screen != SCREEN_ID) return reply(m, false, "error", "this device has no screen '${m.screen}'")
        when (m.op) {
            "play" -> play(m)
            "status" -> reply(m, true, state = state(), withTime = true)
            "stop" -> {
                if (isOpen) close(tell = true)
                reply(m, true, state = VideoState.STOPPED)
            }
            "pause", "resume", "seek" -> {
                val p = player
                if (p == null || error != null) return reply(m, false, "error", "nothing is playing")
                when (m.op) {
                    "pause" -> p.pause()
                    "resume" -> {
                        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                        p.play()
                    }
                    else -> {
                        val pos = m.position ?: return reply(m, false, "error", "seek needs a position")
                        p.seekTo(clampMs(p, pos))
                    }
                }
                showOverlay()
                told = state()
                reply(m, true, state = told, withTime = true)
            }
            else -> reply(m, false, "unsupported", "unknown video op '${m.op}'")
        }
    }

    private fun play(m: BoxMsg.Video) {
        val url = m.url
        val scheme = url?.let { Uri.parse(it).scheme?.lowercase() }
        if (url == null || (scheme != "http" && scheme != "https")) {
            return reply(m, false, "unsupported", "only http and https video links play here")
        }
        close(tell = false) // a new play replaces the old one
        Log.i(TAG, "video: play '${m.title.orEmpty().take(80)}'${if (m.startAt != null) " from ${m.startAt.toInt()} s" else ""}")
        quietMedia3()
        title = m.title.orEmpty()
        error = null
        val p = try {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Venuebox/${BuildConfig.VERSION_NAME} (Android)")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
            ExoPlayer.Builder(act, DefaultRenderersFactory(act).setEnableDecoderFallback(true))
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(act, httpFactory)))
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    false,
                )
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "video: player didn't start (${e.javaClass.simpleName})")
            return reply(m, false, "error", "the player didn't start")
        }
        player = p
        p.addListener(listener)
        val item = MediaItem.Builder().setUri(url).apply { hlsHint(m.mime)?.let { setMimeType(it) } }.build()
        val startMs = m.startAt?.let { (it * 1000).toLong() } ?: 0L
        p.setMediaItem(item, startMs)
        p.prepare()
        p.playWhenReady = true
        openView(p, m.poster)
        told = VideoState.LOADING
        reply(m, true, state = VideoState.LOADING)
        lastPush = SystemClock.uptimeMillis()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    /**
     * Closes the player and shows the page again. [tell]: send video-state "stopped" (it ended,
     * was stopped, or a new show/off came); a new play replaces it silently.
     */
    fun close(tell: Boolean) {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(closeAfterError)
        posterJob?.cancel()
        posterJob = null
        val p = player
        val pos = p?.let { seconds(it.currentPosition) }
        val dur = p?.let { durationOf(it) }
        player = null
        p?.removeListener(listener)
        p?.release()
        val wasOpen = view != null
        view?.let { root.removeView(it) }
        poster?.let { root.removeView(it) }
        overlay?.let { root.removeView(it) }
        view = null
        poster = null
        overlay = null
        error = null
        if (wasOpen) {
            Log.i(TAG, "video: closed")
            if (tell) send(ClientMsg.videoState(SCREEN_ID, VideoState.STOPPED, pos, dur))
            told = VideoState.STOPPED
        }
    }

    // ---------------------------------------------------------------- the TV's remote

    /** Keys while the player is open (and the settings panel closed). True: used. */
    fun onKey(e: KeyEvent): Boolean {
        val p = player
        if (!isOpen) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (e.action == KeyEvent.ACTION_UP) close(tell = true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (down && e.repeatCount == 0 && p != null) {
                    if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) p.pause() else resume(p)
                    byRemote()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (down && e.repeatCount == 0 && p != null) resume(p).also { byRemote() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && e.repeatCount == 0 && p != null) p.pause().also { byRemote() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                if (down && p != null) seekBy(p, -BACK_MS)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                if (down && p != null) seekBy(p, FORWARD_MS)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                if (down) showOverlay()
                return true
            }
        }
        return false
    }

    private fun resume(p: ExoPlayer) {
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.play()
    }

    private fun seekBy(p: ExoPlayer, deltaMs: Long) {
        if (!p.isCurrentMediaItemSeekable) return
        p.seekTo(clampMs(p, (p.currentPosition + deltaMs) / 1000.0))
        byRemote()
    }

    /** Someone used the TV's remote: show where it is and tell the box. */
    private fun byRemote() {
        showOverlay()
        push(force = true)
    }

    // ---------------------------------------------------------------- state

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_IS_PLAYING_CHANGED)) {
                updateOverlay()
                if (player.playbackState == Player.STATE_ENDED) {
                    Log.i(TAG, "video: ended")
                    close(tell = true)
                    return
                }
                push(force = false)
            }
        }

        override fun onRenderedFirstFrame() {
            poster?.visibility = View.GONE
        }

        override fun onPlayerError(e: PlaybackException) {
            val why = describe(e)
            Log.w(TAG, "video: failed (${e.errorCodeName})")
            error = why
            val p = player
            send(ClientMsg.videoState(SCREEN_ID, VideoState.ERROR, p?.let { seconds(it.currentPosition) }, p?.let { durationOf(it) }, why))
            told = VideoState.ERROR
            overlay?.apply {
                text = "Can't play this video: $why"
                visibility = View.VISIBLE
            }
            handler.removeCallbacks(tick)
            handler.removeCallbacks(hideOverlay)
            handler.postDelayed(closeAfterError, ERROR_SHOWN_MS)
        }
    }

    private fun state(): String {
        val p = player ?: return VideoState.STOPPED
        if (error != null) return VideoState.ERROR
        return when (p.playbackState) {
            Player.STATE_READY -> if (p.playWhenReady) VideoState.PLAYING else VideoState.PAUSED
            Player.STATE_ENDED -> VideoState.STOPPED
            else -> if (p.playWhenReady) VideoState.LOADING else VideoState.PAUSED
        }
    }

    /** video-state when the state changed since the box last heard ([force]: anyway, with the time). */
    private fun push(force: Boolean) {
        val p = player ?: return
        val s = state()
        if (!force && s == told) return
        told = s
        lastPush = SystemClock.uptimeMillis()
        send(ClientMsg.videoState(SCREEN_ID, s, seconds(p.currentPosition), durationOf(p)))
    }

    private fun reply(
        m: BoxMsg.Video,
        ok: Boolean,
        reason: String? = null,
        message: String? = null,
        state: String? = null,
        withTime: Boolean = false,
    ) {
        val p = player?.takeIf { withTime }
        send(ClientMsg.videoReply(m.requestId, ok, reason, message, state, p?.let { seconds(it.currentPosition) }, p?.let { durationOf(it) }))
    }

    // ---------------------------------------------------------------- views

    private fun openView(p: ExoPlayer, posterUrl: String?) {
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val v = PlayerView(act).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            keepScreenOn = true
            isFocusable = false
            player = p
        }
        root.addView(v, match)
        view = v
        if (posterUrl != null) {
            val img = ImageView(act).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
            }
            root.addView(img, match)
            poster = img
            posterJob = scope.launch {
                val bmp = fetchPoster(posterUrl) ?: return@launch
                if (poster === img && p.isPlaying.not() && player === p) {
                    img.setImageBitmap(bmp)
                    img.visibility = View.VISIBLE
                }
            }
        }
        overlay = TextView(act).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB0000000.toInt())
            setPadding(dp(32), dp(16), dp(32), dp(16))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }.also {
            root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        showOverlay()
    }

    /** The poster from the media server, while the first frame loads. Failures are silent. */
    private suspend fun fetchPoster(u: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(u).build()).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val bytes = res.body?.bytes() ?: return@use null
                if (bytes.size > 8 * 1024 * 1024) return@use null
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (opts.outWidth / sample > 1920 || opts.outHeight / sample > 1080) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showOverlay() {
        updateOverlay()
        overlay?.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_MS)
    }

    @SuppressLint("SetTextI18n")
    private fun updateOverlay() {
        val o = overlay ?: return
        val p = player ?: return
        if (error != null) return
        val mark = when (state()) {
            VideoState.PLAYING -> "▶"
            VideoState.PAUSED -> "❚❚"
            else -> "…"
        }
        val dur = durationOf(p)
        val time = clock(p.currentPosition / 1000) + (if (dur != null) " / " + clock((dur).toLong()) else "")
        o.text = "$mark  $time    $title"
        if (state() == VideoState.PAUSED) {
            o.visibility = View.VISIBLE
            handler.removeCallbacks(hideOverlay)
        }
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    companion object {
        const val PLAYING_EVERY_MS = 10_000L
        const val OVERLAY_MS = 4000L
        const val ERROR_SHOWN_MS = 6000L
        const val BACK_MS = 10_000L
        const val FORWARD_MS = 30_000L

        private fun seconds(ms: Long) = max(0L, ms) / 1000.0
        private fun durationOf(p: Player) = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { seconds(it) }

        private fun clampMs(p: Player, s: Double): Long {
            val ms = (s * 1000).toLong().coerceAtLeast(0)
            val d = p.duration
            return if (d != C.TIME_UNSET && d > 0) ms.coerceAtMost(max(0L, d - 1000)) else ms
        }

        private fun clock(totalS: Long): String {
            val h = totalS / 3600
            val m = (totalS % 3600) / 60
            val s = totalS % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        /** The box's mime is a hint; only HLS needs saying (MKV/MP4 are sniffed). */
        private fun hlsHint(mime: String?) = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl" -> MimeTypes.APPLICATION_M3U8
            else -> null
        }

        /** Media3 logs URIs in errors: keep it silent (the link carries the media server's key). */
        fun quietMedia3() = androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)

        /** What went wrong, from the error's code and type: never its text (it can hold the link). */
        fun describe(e: PlaybackException): String {
            val fmt = (e as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
            val codec = codecName(fmt) ?: decoderMime(e)?.let { codecName(it) }
            return when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                    val code = causes(e).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                    "the server refused the stream" + (if (code != null) " (HTTP $code)" else "")
                }
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "the server has no such video"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "couldn't reach the media server"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "the media server didn't answer"
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "the server didn't send a video"
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "plain HTTP isn't allowed for this server"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                    "this video's file format isn't supported"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                    "the video file is damaged or incomplete"
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                    if (codec != null) "no $codec decoder" else "no decoder for this video"
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                    if (codec != null) "this device can't decode $codec" else "this device can't decode this video"
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                    "this video is too big for the device's ${codec ?: "video"} decoder"
                PlaybackException.ERROR_CODE_DECODING_FAILED -> "decoding failed" + (if (codec != null) " ($codec)" else "")
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                    "the TV's audio output failed"
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "fell behind the live stream"
                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED, PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ->
                    "protected (DRM) video isn't supported"
                else -> "playback failed (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})"
            }
        }

        private fun causes(e: Throwable) = generateSequence(e) { it.cause }.take(8)

        private fun decoderMime(e: Throwable) =
            causes(e).filterIsInstance<MediaCodecRenderer.DecoderInitializationException>().firstOrNull()?.mimeType

        private fun codecName(f: Format?) = f?.sampleMimeType?.let { codecName(it) }

        private fun codecName(mime: String) = when (mime.lowercase()) {
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            else -> null
        }

        // ------------------------------------------------------------ what this device plays

        /** hello's `video` for this device (see [videoCaps]). */
        @Suppress("DEPRECATION")
        fun caps(ctx: Context): VideoCaps {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { info -> info.supportedTypes.map { type -> decoder(info, type) } }
            val display = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            val display4k = display?.supportedModes?.any { m: Display.Mode -> max(m.physicalWidth, m.physicalHeight) >= 3840 } == true
            val audio = try {
                AudioCapabilities.getCapabilities(ctx)
            } catch (_: Exception) {
                AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
            }
            val passthrough = listOf(
                "ac3" to C.ENCODING_AC3,
                "eac3" to C.ENCODING_E_AC3,
                "dts" to C.ENCODING_DTS,
                "truehd" to C.ENCODING_DOLBY_TRUEHD,
            ).filter { (_, enc) -> audio.supportsEncoding(enc) }.map { it.first }
            return videoCaps(decoders, display4k, passthrough, display?.let { hdrRanges(it) }.orEmpty())
        }

        /** The display's HDR types as range names (this mode, and on 34+ also what the mode lists). */
        @Suppress("DEPRECATION")
        private fun hdrRanges(d: Display): Set<String> {
            val types = mutableSetOf<Int>()
            try {
                d.hdrCapabilities?.supportedHdrTypes?.let { types += it.toList() }
            } catch (_: Exception) {
            }
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    types += d.mode.supportedHdrTypes.toList()
                } catch (_: Exception) {
                }
            }
            return types.mapNotNull {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "hdr10"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10plus"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dovi"
                    else -> null
                }
            }.toSet()
        }

        private fun decoder(info: MediaCodecInfo, type: String): Decoder {
            val hw = if (Build.VERSION.SDK_INT >= 29) {
                info.isHardwareAccelerated
            } else {
                val n = info.name.lowercase()
                !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.") || n.contains(".sw.") || n.contains("ffmpeg"))
            }
            val caps = try {
                info.getCapabilitiesForType(type)
            } catch (_: Exception) {
                null
            }
            val uhd = type.startsWith("video/") && caps?.videoCapabilities?.isSizeSupported(3840, 2160) == true
            val main10 = type.equals(MimeTypes.VIDEO_H265, ignoreCase = true) && caps?.profileLevels?.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            } == true
            return Decoder(type.lowercase(), hw, uhd, main10)
        }
    }
}
