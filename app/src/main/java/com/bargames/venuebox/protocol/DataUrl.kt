package com.bargames.venuebox.protocol

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A decoded `data:` URL (RFC 2397). The box sends its idle page as
 * `data:text/html;charset=utf-8,<percent-encoded html>`; WebView loads the decoded text with
 * loadDataWithBaseURL, which is reliable for long pages (loadUrl with a long data: URL isn't).
 */
data class DataUrl(val mime: String, val text: String) {
    companion object {
        fun parse(url: String): DataUrl? {
            if (!url.startsWith("data:", ignoreCase = true)) return null
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val meta = url.substring(5, comma).split(';').map { it.trim() }
            val payload = url.substring(comma + 1)
            val mime = meta.firstOrNull()?.takeIf { it.contains('/') }?.lowercase() ?: "text/plain"
            val charset = meta.firstOrNull { it.startsWith("charset=", ignoreCase = true) }?.substringAfter('=')
                ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
            val bytes = try {
                if (meta.any { it.equals("base64", ignoreCase = true) }) {
                    Base64.getMimeDecoder().decode(percentDecode(payload))
                } else {
                    percentDecode(payload)
                }
            } catch (_: IllegalArgumentException) {
                return null
            }
            return DataUrl(mime, String(bytes, charset))
        }

        /** %XX → byte; everything else as is ('+' stays '+', unlike form decoding). */
        private fun percentDecode(s: String): ByteArray {
            val out = ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        out.write(v)
                        i += 3
                        continue
                    }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toByteArray()
        }
    }
}
