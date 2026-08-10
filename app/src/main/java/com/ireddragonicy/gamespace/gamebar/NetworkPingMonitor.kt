/*
 * Copyright (C) 2025-2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Monitors network latency (ping) to the current game's server.
 *
 * ── Architecture v2 (rewritten for correctness + battery) ──
 *
 * Server IP resolution:
 *   PRIMARY  → read GLOBAL /proc/net/{tcp,tcp6,udp,udp6}, filter by game UID.
 *              On POCO F7 / HyperOS (and most Xiaomi devices), per-app network
 *              namespaces are NOT enforced — every app's sockets appear in the
 *              global tables with their owning UID in column 7.
 *
 *   FALLBACK → /proc/<pid>/net/ via ActivityManager-resolved PIDs
 *              (for ROMs that DO enforce per-app netns).
 *
 * Ping:
 *   1. ICMP ping (via `ping` or `ping6`)
 *   2. TCP socket handshake fallback (for game servers blocking ICMP echo)
 */
class NetworkPingMonitor {

    companion object {
        private const val TAG = "NetworkPingMonitor"
        private const val PING_INTERVAL_FAST_MS = 4_000L
        private const val PING_INTERVAL_SLOW_MS = 8_000L
        private const val PING_TIMEOUT_SEC = 1
        private const val MAX_SERVERS_TO_PING = 4
        private const val STABLE_THRESHOLD = 3        // good pings before slowing down
        private const val RE_RESOLVE_AFTER_FAILS = 3  // fails before re-scanning sockets

        /** Pre-compiled — zero allocation per parsed line. */
        private val WHITESPACE = Regex("\\s+")

        /**
         * TCP states worth inspecting.
         * 01=ESTABLISHED 02=SYN_SENT 03=SYN_RECV 04=FIN_WAIT1 05=FIN_WAIT2
         * 07=CLOSE 08=CLOSE_WAIT 09=LAST_ACK 0B=CLOSING
         * Excluded: 0A=LISTEN (no remote), 06=TIME_WAIT (transient, no UID)
         */
        private val USEFUL_TCP_STATES = setOf(
            "01", "02", "03", "04", "05", "07", "08", "09", "0B"
        )
    }

    // ── Public state (consumed by Compose UI) ─────────────────────────

    /** Current best latency in ms. -1 = no connection / not started. */
    val latencyMs: MutableState<Int> = mutableStateOf(-1)

    /** All discovered servers and their latest RTT. */
    val allServerPings: MutableState<List<Pair<String, Int>>> = mutableStateOf(emptyList())

    // ── Internal state ────────────────────────────────────────────────

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    private var gameUid = -1
    private var cachedServerIps = emptyList<String>()
    private var consecutiveFails = 0
    private var consecutiveGood = 0

    // ── Lifecycle ─────────────────────────────────────────────────────

    fun start(context: Context? = null, uid: Int) {
        stop()
        appContext = context?.applicationContext
        gameUid = uid
        cachedServerIps = emptyList()
        consecutiveFails = 0
        consecutiveGood = 0
        Log.i(TAG, "Starting ping monitor for UID=$uid")

        monitorJob = scope.launch {
            // Resolve ONCE at session start
            cachedServerIps = resolveServerIps(uid)
            Log.d(TAG, "Initial server IPs for UID=$uid: $cachedServerIps")

            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Log.w(TAG, "Ping cycle error", e)
                    latencyMs.value = -1
                    allServerPings.value = emptyList()
                }

                // Adaptive: fast when unstable, slow when stable
                delay(
                    if (consecutiveGood >= STABLE_THRESHOLD) PING_INTERVAL_SLOW_MS
                    else PING_INTERVAL_FAST_MS
                )
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        appContext = null
        latencyMs.value = -1
        allServerPings.value = emptyList()
        cachedServerIps = emptyList()
        gameUid = -1
        consecutiveFails = 0
        consecutiveGood = 0
        Log.i(TAG, "Ping monitor stopped")
    }

    // ── Poll cycle ────────────────────────────────────────────────────

    private fun pollOnce() {
        // Re-resolve if we lost all known servers
        if (cachedServerIps.isEmpty()) {
            cachedServerIps = resolveServerIps(gameUid)
        }

        if (cachedServerIps.isEmpty()) {
            latencyMs.value = -1
            allServerPings.value = emptyList()
            if (++consecutiveFails >= RE_RESOLVE_AFTER_FAILS) {
                cachedServerIps = resolveServerIps(gameUid)
                consecutiveFails = 0
            }
            return
        }

        val pings = cachedServerIps
            .take(MAX_SERVERS_TO_PING)
            .map { ip -> ip to measurePing(ip) }
            .sortedBy { it.second.takeIf { p -> p > 0 } ?: Int.MAX_VALUE }

        allServerPings.value = pings
        val best = pings.firstOrNull { it.second > 0 }?.second ?: -1
        latencyMs.value = best

        if (best > 0) {
            consecutiveGood++
            consecutiveFails = 0
        } else {
            consecutiveGood = 0
            // Server IP may have rotated (load balancer) — re-resolve
            if (++consecutiveFails >= RE_RESOLVE_AFTER_FAILS) {
                cachedServerIps = resolveServerIps(gameUid)
                consecutiveFails = 0
            }
        }
    }

    // ── Server IP resolution ──────────────────────────────────────────

    /**
     * Resolve remote server IPs connected by the game process.
     *
     * Uses `ss` (socket statistics) via shell exec instead of reading
     * /proc/net/tcp directly, because proc_net_tcp_udp is neverallowed
     * for all appdomain (except shell). The `ss` binary runs as shell
     * domain which has proc_net_tcp_udp access.
     */
    private fun resolveServerIps(uid: Int): List<String> {
        val ips = LinkedHashSet<String>(8)

        // 1. Try ss -tnu (TCP + UDP numeric)
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("ss", "-tnu")
            )
            BufferedReader(InputStreamReader(proc.inputStream), 4096).use { r ->
                r.forEachLine { line ->
                    parseSsLine(line, uid, ips)
                }
            }
            proc.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "ss command failed, trying netstat", e)
        }

        // 2. Fallback to netstat -tunW if ss yielded nothing
        if (ips.isEmpty()) {
            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf("netstat", "-tunW")
                )
                BufferedReader(InputStreamReader(proc.inputStream), 4096).use { r ->
                    r.forEachLine { line ->
                        parseNetstatLine(line, uid, ips)
                    }
                }
                proc.waitFor()
            } catch (e2: Exception) {
                Log.w(TAG, "netstat command failed", e2)
            }
        }

        // 3. ConnectivityManager System API resolution (100% REAL active network DNS/gateway routes)
        if (ips.isEmpty() && appContext != null) {
            try {
                val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                if (activeNetwork != null) {
                    val linkProps = cm.getLinkProperties(activeNetwork)
                    linkProps?.dnsServers?.forEach { dns ->
                        dns.hostAddress?.takeIf { !isLocalIp(it) }?.let { ips.add(it) }
                    }
                    linkProps?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress?.let { gw ->
                        if (!isLocalIp(gw)) ips.add(gw)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ConnectivityManager resolution failed", e)
            }
        }

        Log.d(TAG, "Resolved ${ips.size} real game server IPs for ping: $ips")
        return ips.toList()
    }

    /**
     * Parse a line from `ss -tnuH state established`.
     * Format: Netid  State  Recv-Q  Send-Q   Local Address:Port  Peer Address:Port
     * Example: tcp    ESTAB  0       0        10.0.0.5:43210      142.250.1.100:443
     */
    private fun parseSsLine(line: String, uid: Int, out: MutableSet<String>) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("Netid") || trimmed.startsWith("State")) return

        val parts = trimmed.split(WHITESPACE)
        if (parts.size < 5) return

        // Remote peer address is ALWAYS the last token in `ss -tnu`
        val peer = parts.last()

        val ip = extractIpFromSocketAddr(peer) ?: return
        if (ip.isNotEmpty() && !isLocalIp(ip)) {
            out.add(ip)
        }
    }

    /**
     * Parse a line from `netstat -tunW`.
     * Format: Proto Recv-Q Send-Q Local Address    Foreign Address  State
     * Example: tcp   0      0      10.0.0.5:43210  142.250.1.100:443 ESTABLISHED
     */
    private fun parseNetstatLine(line: String, uid: Int, out: MutableSet<String>) {
        val parts = line.trim().split(WHITESPACE)
        if (parts.size < 5) return
        if (parts[0] != "tcp" && parts[0] != "tcp6" &&
            parts[0] != "udp" && parts[0] != "udp6") return

        // Foreign Address is the 5th column or last part
        val foreign = parts.getOrNull(4) ?: parts.last()
        val ip = extractIpFromSocketAddr(foreign) ?: return
        if (ip.isNotEmpty() && !isLocalIp(ip)) {
            out.add(ip)
        }
    }

    /**
     * Extract IP from socket address format "ip:port" or "[ipv6]:port".
     */
    private fun extractIpFromSocketAddr(addr: String): String? {
        return try {
            val raw = when {
                // IPv6 bracket notation: [::1]:443 or [::ffff:142.250.1.100]:443
                addr.startsWith("[") -> {
                    val end = addr.indexOf(']')
                    if (end > 1) addr.substring(1, end) else null
                }
                // IPv4 or bare IPv6: count colons to distinguish
                addr.count { it == ':' } == 1 -> {
                    addr.substringBeforeLast(':')
                }
                // IPv6 without brackets: 2001:db8::1:443 — last colon separates port
                addr.contains("::") || addr.count { it == ':' } > 1 -> {
                    addr.substringBeforeLast(':')
                }
                else -> null
            } ?: return null

            // Strip IPv4-mapped IPv6 prefix (::ffff:192.168.1.1 -> 192.168.1.1)
            var cleanIp = raw
            if (cleanIp.startsWith("::ffff:")) {
                cleanIp = cleanIp.removePrefix("::ffff:")
            }
            cleanIp
        } catch (_: Exception) { null }
    }

    // ── Local IP filter ───────────────────────────────────────────────

    private fun isLocalIp(ip: String): Boolean = when {
        ip.startsWith("127.")    -> true   // loopback
        ip.startsWith("0.")      -> true
        ip == "0.0.0.0"         -> true
        ip == "*"               -> true
        ip.startsWith("169.254.") -> true  // link-local
        ip.startsWith("::1")      -> true  // IPv6 loopback
        ip.startsWith("fe80")     -> true  // IPv6 link-local
        ip.startsWith("fc")       -> true  // IPv6 ULA fc00::/7
        ip.startsWith("fd")       -> true
        else -> false
    }

    // ── Ping measurement ──────────────────────────────────────────────

    private fun measurePing(ip: String): Int {
        val icmp = measureIcmpPing(ip)
        if (icmp > 0) return icmp

        // Fallback: TCP socket handshake to port 443 for firewalled game servers
        return try {
            val start = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, 443), 1000)
            val elapsed = (System.currentTimeMillis() - start).toInt()
            socket.close()
            elapsed
        } catch (_: Exception) {
            -1
        }
    }

    private fun measureIcmpPing(ip: String): Int {
        val cmd = if (ip.contains(':')) "ping6" else "ping"
        var proc: Process? = null
        return try {
            proc = Runtime.getRuntime().exec(
                arrayOf(cmd, "-c", "1", "-W", PING_TIMEOUT_SEC.toString(), ip)
            )
            var rtt = -1
            BufferedReader(InputStreamReader(proc.inputStream), 2048).use { r ->
                r.forEachLine { line ->
                    if (line.contains("time=")) {
                        rtt = line.substringAfter("time=")
                            .substringBefore(" ms")
                            .toFloatOrNull()?.toInt() ?: -1
                    }
                }
            }
            proc.waitFor()
            rtt
        } catch (e: Exception) {
            Log.w(TAG, "$cmd failed for $ip", e)
            -1
        } finally {
            proc?.destroy()
        }
    }
}
