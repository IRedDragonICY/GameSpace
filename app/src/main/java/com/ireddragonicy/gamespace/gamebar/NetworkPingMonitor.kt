/*
 * Copyright (C) 2025 IRedDragonICY
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ireddragonicy.gamespace.gamebar

import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import android.os.ServiceManager
import com.android.internal.app.IGameSpaceService

/**
 * Monitors network latency (ping) to the current game's server.
 *
 * How it works:
 * 1. Reads /proc/net/tcp and /proc/net/tcp6 to find active TCP connections
 *    belonging to the game's UID.
 * 2. Filters out local/private IPs to find the remote game server.
 * 3. Runs ICMP ping to measure round-trip time (RTT).
 * 4. Exposes latencyMs as a Compose state for the UI.
 *
 * Lifecycle: call start(gameUid) when a game session begins,
 * stop() when it ends.
 */
class NetworkPingMonitor {

    companion object {
        private const val TAG = "NetworkPingMonitor"
        private const val PING_INTERVAL_MS = 4000L
        private const val PING_TIMEOUT_SEC = 1
        private const val PROC_TCP = "/proc/net/tcp"
        private const val PROC_TCP6 = "/proc/net/tcp6"
        private const val PROC_UDP = "/proc/net/udp"
        private const val PROC_UDP6 = "/proc/net/udp6"
    }

    /** Current latency in milliseconds. -1 = no connection / not started. */
    val latencyMs: MutableState<Int> = mutableStateOf(-1)

    /** Last resolved server IP for display/debug. */
    var lastServerIp: String? = null
        private set

    /** All connected servers and their latencies */
    val allServerPings: MutableState<List<Pair<String, Int>>> = mutableStateOf(emptyList())

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val hostNameCache = mutableMapOf<String, String>()

    private fun getHostName(ip: String): String {
        return hostNameCache.getOrPut(ip) {
            scope.launch {
                try {
                    val host = java.net.InetAddress.getByName(ip).hostName
                    if (host != ip) {
                        hostNameCache[ip] = host
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
            ip
        }
    }

    /**
     * Start monitoring ping for the given game UID.
     * Automatically resolves the game server IPs from socket stats.
     */
    fun start(gameUid: Int) {
        stop() // Cancel any previous monitor
        Log.i(TAG, "Starting ping monitor for UID=$gameUid")

        monitorJob = scope.launch {
            while (isActive) {
                try {
                    val serverIps = resolveAllGameServerIps(gameUid)
                    if (serverIps.isNotEmpty()) {
                        val pings = serverIps.map { ip ->
                            val host = getHostName(ip)
                            val display = if (host != ip) "$host\n$ip" else ip
                            Pair(display, measurePing(ip))
                        }.sortedBy { it.second.takeIf { p -> p > 0 } ?: Int.MAX_VALUE }

                        allServerPings.value = pings
                        lastServerIp = pings.firstOrNull()?.first
                        
                        // Set main latency badge to the lowest valid ping
                        val bestPing = pings.firstOrNull { it.second > 0 }?.second ?: -1
                        latencyMs.value = bestPing
                    } else {
                        latencyMs.value = -1
                        lastServerIp = null
                        allServerPings.value = emptyList()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ping monitor error", e)
                    latencyMs.value = -1
                    allServerPings.value = emptyList()
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    /** Stop monitoring. */
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        latencyMs.value = -1
        lastServerIp = null
        allServerPings.value = emptyList()
        Log.i(TAG, "Ping monitor stopped")
    }

    private fun resolveAllGameServerIps(gameUid: Int): List<String> {
        val ips = mutableSetOf<String>()
        try {
            val service = IGameSpaceService.Stub.asInterface(ServiceManager.getService("game_space"))
            if (service == null) return emptyList()

            val uidStr = gameUid.toString()
            val lines = service.dumpSocketStats() ?: return emptyList()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (!trimmed.startsWith("tcp") && !trimmed.startsWith("udp")) continue

                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size < 7) continue

                val remAddress = parts[4]
                val state = parts[5]
                val uid = parts[6]

                if (uid != uidStr) continue
                if (state != "ESTABLISHED") continue

                val remIpPart = remAddress.substringBeforeLast(':')
                if (remIpPart == "0.0.0.0" || remIpPart == "::" || remIpPart == "*") continue

                val cleanIp = remIpPart.removePrefix("::ffff:").removeSurrounding("[", "]")
                if (cleanIp.isNotEmpty() && !isLocalIp(cleanIp)) {
                    ips.add(cleanIp)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse socket stats", e)
        }
        return ips.toList()
    }

    /** Check if IP is local/private/loopback — we want public game server IPs only. */
    private fun isLocalIp(ip: String): Boolean {
        if (ip.startsWith("127.") || ip.startsWith("0.") || ip == "0.0.0.0") return true
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("192.168.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            if (second in 16..31) return true
        }
        if (ip.startsWith("169.254.")) return true  // Link-local
        if (ip.startsWith("::1") || ip.startsWith("fe80")) return true  // IPv6 loopback/link-local
        return false
    }

    /**
     * Measure ping RTT to the given IP using ICMP ping command.
     * Returns RTT in ms, or -1 on timeout/failure.
     */
    private fun measurePing(ip: String): Int {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("ping", "-c", "1", "-W", PING_TIMEOUT_SEC.toString(), ip)
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var rtt = -1

            reader.forEachLine { line ->
                // Parse "time=42.3 ms" from ping output
                if (line.contains("time=")) {
                    val timeStr = line.substringAfter("time=").substringBefore(" ms")
                    rtt = timeStr.toFloatOrNull()?.toInt() ?: -1
                }
            }

            process.waitFor()
            reader.close()
            rtt
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed for $ip", e)
            -1
        }
    }
}
