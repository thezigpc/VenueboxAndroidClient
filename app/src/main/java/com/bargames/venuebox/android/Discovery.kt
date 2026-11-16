package com.bargames.venuebox.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.bargames.venuebox.client.BoxCheck
import com.bargames.venuebox.client.checkBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** A box seen on the LAN (mDNS _venuebox._tcp). Remember it by [id], not by its address. */
data class FoundBox(val id: String, val name: String, val port: Int, val addresses: List<String>, val url: String? = null)

/** Finds boxes with NsdManager. The emulator (NAT) and isolated networks see nothing: "type the address" stays. */
class Discovery(context: Context, private val http: OkHttpClient) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Browses for [browseMs], resolves what it saw, and finds a working URL for each box. */
    suspend fun browse(browseMs: Long = 3000): List<FoundBox> {
        val seen = ConcurrentHashMap<String, NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS browse failed to start: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                seen[info.serviceName] = info
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS browse failed: ${e.message}")
            return emptyList()
        }
        try {
            delay(browseMs)
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        val boxes = LinkedHashMap<String, FoundBox>()
        // NsdManager resolves one service at a time on older Android: do them in turn.
        for (info in seen.values) {
            val r = resolve(info) ?: continue
            val attrs = r.attributes ?: emptyMap()
            fun txt(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) }
            val id = txt("id")?.takeIf { it.isNotBlank() } ?: continue
            val name = txt("name")?.takeIf { it.isNotBlank() } ?: r.serviceName
            val addrs = addresses(r)
            val prev = boxes[id]
            boxes[id] = FoundBox(id, name, r.port, ((prev?.addresses ?: emptyList()) + addrs).distinct())
        }
        return boxes.values.map { it.copy(url = reachable(it)) }
    }

    /** The first of the box's addresses whose /health answers, as a box URL. */
    suspend fun reachable(b: FoundBox): String? {
        for (a in b.addresses) {
            val host = if (a.contains(':')) "[$a]" else a
            val url = "http://$host:${b.port}"
            if (checkBox(http, url) is BoxCheck.Ok) return url
        }
        return null
    }

    /** The box with discovery id [id]: where does it answer now? */
    suspend fun relocate(id: String): FoundBox? = browse().firstOrNull { it.id == id && it.url != null }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(4000) {
        suspendCancellableCoroutine { cont ->
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(si)
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun addresses(info: NsdServiceInfo): List<String> {
        val list = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        // IPv4 first: venue LANs are IPv4; link-local IPv6 needs a scope id.
        return list.sortedBy { if (it is Inet4Address) 0 else 1 }.mapNotNull { it.hostAddress?.substringBefore('%') }
    }

    companion object {
        const val SERVICE_TYPE = "_venuebox._tcp"
    }
}
