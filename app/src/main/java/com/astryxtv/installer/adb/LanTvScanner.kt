package com.astryxtv.installer.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class DiscoveredTv(
    val ip: String,
    val port: Int = 5555,
    val hostname: String? = null
)

/**
 * Scans the phone's active Wi-Fi /24 subnet for devices listening on ADB TCP port 5555.
 * Uses pure socket probes so no Android location permission is needed.
 */
object LanTvScanner {

    fun detectLocalIpv4(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrNull() ?: return null
        val preferred = interfaces
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("eth") }

        for (iface in preferred) {
            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    suspend fun scanSubnet(
        port: Int = 5555,
        timeoutMs: Int = 300,
        onFound: (DiscoveredTv) -> Unit = {}
    ): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val localIp = detectLocalIpv4() ?: return@withContext emptyList()
        val parts = localIp.split(".")
        if (parts.size != 4) return@withContext emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        val myLastOctet = parts[3].toIntOrNull() ?: -1

        val semaphore = Semaphore(48)
        coroutineScope {
            (1..254).filter { it != myLastOctet }.map { octet ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val ip = "$prefix.$octet"
                        val open = runCatching {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(ip, port), timeoutMs)
                            }
                            true
                        }.getOrDefault(false)
                        if (open) {
                            val host = runCatching {
                                val resolved = java.net.InetAddress.getByName(ip).canonicalHostName
                                if (resolved != ip) resolved else null
                            }.getOrNull()
                            val tv = DiscoveredTv(ip = ip, port = port, hostname = host)
                            onFound(tv)
                            tv
                        } else {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
        }
    }
}
