package com.bargames.venuebox.protocol

import java.net.URI

object BoxAddress {
    /**
     * What staff typed → the box base URL. "192.168.1.20" → "http://192.168.1.20:7210";
     * keeps an explicit scheme and port; drops any path. Null if it isn't an address.
     */
    fun normalize(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        val withScheme = if (Regex("^[a-zA-Z]+://").containsMatchIn(raw)) raw else "http://$raw"
        val u = try {
            URI(withScheme)
        } catch (_: Exception) {
            return null
        }
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host ?: return null
        if (host.isEmpty()) return null
        val port = if (u.port > 0) u.port else DEFAULT_PORT
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$scheme://${hostPart.lowercase()}:$port"
    }

    /** "http://box:7210" → "ws://box:7210/client". */
    fun wsUrl(box: String, path: String = "/client"): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return when {
            box.startsWith("https://") -> "wss://" + box.removePrefix("https://") + p
            box.startsWith("http://") -> "ws://" + box.removePrefix("http://") + p
            else -> box + p
        }
    }

    /** The host part of a normalized box URL (for matching discovery results). */
    fun host(box: String): String? = try {
        URI(box).host?.removePrefix("[")?.removeSuffix("]")
    } catch (_: Exception) {
        null
    }
}
