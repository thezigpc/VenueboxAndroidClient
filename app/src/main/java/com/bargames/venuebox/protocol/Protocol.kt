// Venuebox client protocol v1, mirrored from the box:
// https://github.com/thezigpc/venueplay/blob/main/apps/broker/src/venuebox/protocol.ts
// (the source of truth; docs/NATIVE-CLIENTS.md). Don't change shapes here without changing
// them there first.
//
// HTTP (pairing), on http://<box LAN address>:7210:
//   POST /pair/start   { name, platform, model?, appVersion?, screens: Screen[] }
//                      → { pairingId, code, expiresAt, pollMs }
//   GET  /pair/status?id=<pairingId>
//                      → { status: "pending" } | { status: "approved", deviceId, token, wsPath }
//                        (the token is handed out once) | 404 { status: "expired" }
//   GET  /health       → { ok, box, protocol }
// WebSocket /client: JSON messages; the client's first message is "hello".

package com.bargames.venuebox.protocol

import org.json.JSONArray
import org.json.JSONObject

const val CLIENT_PROTOCOL = 1
const val DEFAULT_PORT = 7210

/** WebSocket close codes the box uses. */
object Close {
    const val BAD_TOKEN = 4401
    const val REVOKED = 4403
    const val PROTOCOL = 4400
    const val REPLACED = 4409
}

/** One display. Android reports exactly one. id: 1-64 chars of [\w.:-]. */
data class Screen(val id: String, val name: String, val w: Int, val h: Int, val hz: Int? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("w", w)
        put("h", h)
        if (hz != null && hz > 0) put("hz", hz)
    }
}

data class DeviceInfo(val name: String, val platform: String, val model: String?, val appVersion: String?)

// ---------------------------------------------------------------- client → box

object ClientMsg {
    fun hello(token: String, device: DeviceInfo, screens: List<Screen>): String = JSONObject().apply {
        put("type", "hello")
        put("v", CLIENT_PROTOCOL)
        put("token", token)
        putDevice(device)
        put("screens", screensJson(screens))
        put("deliveries", JSONArray().put("native-url"))
    }.toString()

    fun screens(screens: List<Screen>): String =
        JSONObject().put("type", "screens").put("screens", screensJson(screens)).toString()

    /** status: "playing" | "loading" | "error" | "off"; showing is the URL, "" when off. */
    fun state(screen: String, showing: String, status: String, detail: String? = null): String = JSONObject().apply {
        put("type", "state")
        put("screen", screen)
        put("showing", showing)
        put("status", status)
        if (!detail.isNullOrEmpty()) put("detail", detail)
    }.toString()

    fun preview(requestId: String, screen: String, jpeg: String?, error: String?): String = JSONObject().apply {
        put("type", "preview")
        put("requestId", requestId)
        put("screen", screen)
        if (jpeg != null) put("jpeg", jpeg)
        if (error != null) put("error", error)
    }.toString()

    /** Body of POST /pair/start. */
    fun pairStart(device: DeviceInfo, screens: List<Screen>): String =
        JSONObject().apply { putDevice(device); put("screens", screensJson(screens)) }.toString()

    private fun JSONObject.putDevice(d: DeviceInfo) {
        put("name", d.name)
        put("platform", d.platform)
        if (d.model != null) put("model", d.model)
        if (d.appVersion != null) put("appVersion", d.appVersion)
    }

    private fun screensJson(screens: List<Screen>) = JSONArray().apply { screens.forEach { put(it.toJson()) } }
}

// ---------------------------------------------------------------- box → client

sealed interface BoxMsg {
    data class Welcome(val v: Int, val deviceId: String, val name: String, val labels: Map<String, String>) : BoxMsg
    data class Show(val screen: String, val url: String, val kind: String, val title: String?) : BoxMsg
    data class Off(val screen: String) : BoxMsg
    data class Reload(val screen: String) : BoxMsg
    data class Identify(val screen: String, val label: String?) : BoxMsg
    data class PreviewRequest(val screen: String, val requestId: String) : BoxMsg
    data class Error(val code: String, val message: String) : BoxMsg
    /** Anything this version doesn't know (e.g. a future "settings"): ignored. */
    data class Unknown(val type: String) : BoxMsg

    companion object {
        /** Null for anything that isn't a JSON object with a string "type". */
        fun parse(raw: String): BoxMsg? {
            val o = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return null
            }
            val type = o.optString("type", "")
            if (type.isEmpty()) return null
            val screen = o.optString("screen", "")
            return when (type) {
                "welcome" -> {
                    val labels = LinkedHashMap<String, String>()
                    val arr = o.optJSONArray("screens") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val id = s.optString("id", "")
                        if (id.isNotEmpty()) labels[id] = s.optString("label", id)
                    }
                    Welcome(o.optInt("v", CLIENT_PROTOCOL), o.optString("deviceId", ""), o.optString("name", ""), labels)
                }
                // The box sends idle screens as an ordinary show; "idle" is tolerated as the same thing.
                "show", "idle" -> Show(screen, o.optString("url", ""), o.optString("kind", "native-url"), o.optStringOrNull("title"))
                "off" -> Off(screen)
                "reload" -> Reload(screen)
                "identify" -> Identify(screen, o.optStringOrNull("label"))
                "preview" -> PreviewRequest(screen, o.optString("requestId", ""))
                "error" -> Error(o.optString("code", ""), o.optString("message", ""))
                else -> Unknown(type)
            }
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

// ---------------------------------------------------------------- HTTP replies

data class PairStart(val pairingId: String, val code: String, val expiresAt: String, val pollMs: Long) {
    companion object {
        fun parse(o: JSONObject): PairStart? {
            val code = o.optString("code", "")
            val id = o.optString("pairingId", "")
            if (code.isEmpty() || id.isEmpty()) return null
            return PairStart(id, code, o.optString("expiresAt", ""), o.optLong("pollMs", 2000))
        }
    }
}

sealed interface PairStatus {
    data object Pending : PairStatus
    data class Approved(val deviceId: String, val token: String, val wsPath: String) : PairStatus
    data object Expired : PairStatus

    companion object {
        fun parse(httpStatus: Int, o: JSONObject): PairStatus? = when {
            httpStatus == 404 -> Expired
            httpStatus != 200 -> null
            o.optString("status") == "approved" && o.optString("token").isNotEmpty() ->
                Approved(o.optString("deviceId", ""), o.optString("token"), o.optString("wsPath", "/client"))
            o.optString("status") == "expired" -> Expired
            else -> Pending
        }
    }
}
