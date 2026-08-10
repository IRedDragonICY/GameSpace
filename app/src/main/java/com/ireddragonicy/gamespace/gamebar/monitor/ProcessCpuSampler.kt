/* --- gamespace/gamebar/monitor/ProcessCpuSampler.kt --- */
package com.ireddragonicy.gamespace.gamebar.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/**
 * In‑process CPU sampler using `/proc` filesystem and ActivityManager mapping.
 */
class ProcessCpuSampler(
    private val context: Context? = null,
    private val topN: Int = 25
) {
    /** utime+stime in ticks, plus starttime to detect pid reuse. */
    private class Sample(val ticks: Long, val startTime: Long, val comm: String)

    private var prev = HashMap<Int, Sample>()
    private var prevMs = 0L

    /** Scratch buffer. */
    private val buf = ByteArray(1024)

    private val pidToPackageMap = HashMap<Int, String>()
    private var lastAmFetchMs = 0L

    private val hz: Long =
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrNull()
            ?.takeIf { it > 0 } ?: 100L

    /** Drops the delta baseline so a restart after a long pause can't emit a bogus first tick. */
    fun reset() {
        prev.clear()
        prevMs = 0L
        pidToPackageMap.clear()
        lastAmFetchMs = 0L
    }

    private fun refreshAmProcessesIfNeeded() {
        val ctx = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAmFetchMs < 3000L && pidToPackageMap.isNotEmpty()) return
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val procs = am.runningAppProcesses ?: return
            pidToPackageMap.clear()
            for (p in procs) {
                if (p.pid > 0 && !p.processName.isNullOrEmpty()) {
                    pidToPackageMap[p.pid] = p.processName
                }
            }
            lastAmFetchMs = now
        } catch (_: Exception) {}
    }

    /**
     * One poll. Tries top-based process sampling first for 100% process coverage across
     * all SELinux domains (system daemons, apps, surfaceflinger, games), falling back to
     * /proc if unavailable.
     */
    fun sample(): List<ProcessUsage> {
        val topResults = sampleViaTop()
        if (topResults.isNotEmpty()) {
            return topResults
        }

        // Fallback to in-process /proc sampling
        val now = SystemClock.elapsedRealtime()
        refreshAmProcessesIfNeeded()

        val names = File("/proc").list() ?: return emptyList()
        val cur = HashMap<Int, Sample>(prev.size + 16)

        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            val stat = readPidFile(pid, "stat") ?: continue
            cur[pid] = parseStat(stat) ?: continue
        }

        val elapsedMs = now - prevMs
        val primed = prevMs != 0L && elapsedMs > 0
        val ranked = if (!primed) emptyList() else
            cur.entries
                .mapNotNull { (pid, s) ->
                    val old = prev[pid] ?: return@mapNotNull null
                    if (old.startTime != s.startTime) return@mapNotNull null
                    pid to (s.ticks - old.ticks).coerceAtLeast(0L)
                }
                .sortedByDescending { it.second }
                .take(topN)
                .map { (pid, delta) ->
                    val pct = delta * 100_000f / (hz * elapsedMs)
                    ProcessUsage(displayName(pid, cur[pid]?.comm ?: pid.toString()), pct)
                }

        prev = cur
        prevMs = now
        return ranked
    }

    /**
     * Uses `top -b -n 1 -m 25` to fetch exact system-wide CPU usage for ALL processes
     * (bypassing SELinux per-domain /proc/<pid>/stat read restrictions).
     */
    private fun sampleViaTop(): List<ProcessUsage> {
        var proc: Process? = null
        try {
            proc = Runtime.getRuntime().exec(
                arrayOf("top", "-b", "-n", "1", "-m", topN.toString())
            )
            val list = ArrayList<ProcessUsage>(topN)
            java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream), 4096).use { r ->
                r.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("Tasks:") ||
                        trimmed.startsWith("Mem:") || trimmed.startsWith("Swap:") ||
                        trimmed.contains("%cpu") || trimmed.startsWith("PID")) {
                        return@forEachLine
                    }

                    val parts = trimmed.split(WHITESPACE)
                    if (parts.size < 9) return@forEachLine

                    val pid = parts[0].toIntOrNull() ?: return@forEachLine
                    val cpuStr = parts.getOrNull(8) ?: return@forEachLine
                    val pct = cpuStr.replace(",", ".").toFloatOrNull() ?: return@forEachLine

                    // ARGS starts at index 11 (or last column)
                    var rawArgs = if (parts.size >= 12) parts.subList(11, parts.size).joinToString(" ")
                                  else parts.last()

                    if (rawArgs.isBlank() || rawArgs.startsWith("top")) return@forEachLine

                    val cleanName = cleanProcessArgs(pid, rawArgs)
                    if (cleanName.isNotBlank() && cleanName != "top") {
                        list.add(ProcessUsage(cleanName, pct))
                    }
                }
            }
            proc.waitFor()
            return list
        } catch (_: Exception) {
            return emptyList()
        } finally {
            proc?.destroy()
        }
    }

    private fun cleanProcessArgs(pid: Int, args: String): String {
        // Check if ActivityManager knows this PID as an app package first
        val amName = pidToPackageMap[pid]
        if (!amName.isNullOrEmpty()) return amName

        // Extract first argument token
        val firstToken = args.split(" ").firstOrNull() ?: args
        val basename = if (firstToken.startsWith("/")) firstToken.substringAfterLast('/') else firstToken

        return basename.ifBlank { args }
    }

    private fun parseStat(line: String): Sample? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val comm = line.substring(open + 1, close)
        val f = line.substring(close + 1).trim().split(WHITESPACE)
        val utime = f.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = f.getOrNull(12)?.toLongOrNull() ?: return null
        val start = f.getOrNull(19)?.toLongOrNull() ?: 0L
        return Sample(utime + stime, start, comm)
    }

    private fun displayName(pid: Int, comm: String): String {
        // 1. Check ActivityManager running app processes first (100% accurate package names)
        val amName = pidToPackageMap[pid]
        if (!amName.isNullOrEmpty()) return amName

        // 2. Read /proc/$pid/cmdline for system daemons
        val raw = readPidFile(pid, "cmdline")
        if (!raw.isNullOrEmpty()) {
            val end = raw.indexOf('\u0000').takeIf { it >= 0 } ?: raw.length
            val argv0 = raw.substring(0, end).trim()
            if (argv0.isNotEmpty()) {
                return if (argv0.startsWith("/")) argv0.substringAfterLast('/') else argv0
            }
        }

        // 3. Fallback to /proc/$pid/stat comm
        return comm
    }

    private fun readPidFile(pid: Int, name: String): String? {
        var fd: FileDescriptor? = null
        return try {
            fd = Os.open("/proc/$pid/$name", OsConstants.O_RDONLY, 0)
            val n = Os.read(fd, buf, 0, buf.size)
            if (n <= 0) null else String(buf, 0, n)
        } catch (_: Exception) {
            null
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
