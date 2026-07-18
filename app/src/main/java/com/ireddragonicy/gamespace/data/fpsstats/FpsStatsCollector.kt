/*
 * Copyright (C) 2026 IRedDragonICY
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ireddragonicy.gamespace.data.fpsstats

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemProperties
import android.provider.Settings
import android.os.UserHandle
import android.util.Log
import com.ireddragonicy.gamespace.gamebar.fps.FpsInteractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects performance metrics at 1Hz during gameplay recording.
 *
 * Data sources (all verified on POCO F7 / SM8735 / sun):
 * - FPS: TaskFpsCallback via FpsInteractor
 * - CPU Usage: /proc/stat delta calculation
 * - CPU Freq: /sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq
 * - GPU Freq: /sys/class/kgsl/kgsl-3d0/gpuclk
 * - GPU Load: /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
 * - Temps: /sys/class/thermal/thermal_zoneN/temp
 * - Battery: /sys/class/power_supply/battery/temp, current_now, voltage_now
 * - Threads: /proc/PID/task/TID/stat
 */
@Singleton
class FpsStatsCollector @Inject constructor(
    private val context: Context,
    private val fpsInteractor: FpsInteractor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds

    private var recordingJob: Job? = null
    private var currentPackageName: String = ""
    private var currentAppName: String = ""
    private var startTimeMs: Long = 0L

    // Thread-safe collections — collectLoop() runs on IO, stopRecording() on main
    private val samples: MutableList<PerformanceSample> =
        Collections.synchronizedList(mutableListOf())
    private val threadSnapshots: MutableList<ThreadSnapshot> =
        Collections.synchronizedList(mutableListOf())

    // Previous /proc/stat values for delta CPU usage calculation
    private var prevCpuTotal = LongArray(0)
    private var prevCpuIdle = LongArray(0)
    private var prevThreadTicks = mutableMapOf<Int, Long>()
    private var prevTotalCpuTicks = 0L

    // ── CPU cluster mapping (POCO F7 / SM8735) ──
    // policy0: cpu0,1  policy2: cpu2,3,4  policy5: cpu5,6  policy7: cpu7
    private val cpuClusters = listOf(
        intArrayOf(0, 1),       // Cluster 0: Cortex-A520
        intArrayOf(2, 3, 4),    // Cluster 1: Cortex-A720
        intArrayOf(5, 6),       // Cluster 2: Cortex-A720
        intArrayOf(7),          // Cluster 3: Cortex-X4 Prime
    )

    // Thermal zone indices (verified via adb)
    private val cpuThermalZones = intArrayOf(1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)
    private val gpuThermalZones = intArrayOf(24, 25, 26, 27, 28, 29)

    fun startRecording(packageName: String, appName: String) {
        if (_isRecording.value) return

        currentPackageName = packageName
        currentAppName = appName
        startTimeMs = System.currentTimeMillis()
        samples.clear()
        threadSnapshots.clear()
        prevCpuTotal = LongArray(0)
        prevCpuIdle = LongArray(0)
        prevThreadTicks.clear()
        prevTotalCpuTicks = 0L
        _elapsedSeconds.value = 0
        _isRecording.value = true

        recordingJob = scope.launch {
            collectLoop()
        }
    }

    fun stopRecording(): FpsStatsSession? {
        if (!_isRecording.value) return null
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        val endTime = System.currentTimeMillis()
        val duration = ((endTime - startTimeMs) / 1000).toInt()

        // Snapshot under synchronization to avoid ConcurrentModificationException
        val samplesCopy: List<PerformanceSample>
        val threadsCopy: List<ThreadSnapshot>
        synchronized(samples) { samplesCopy = samples.toList() }
        synchronized(threadSnapshots) { threadsCopy = threadSnapshots.toList() }

        val summary = FpsStatsSession.computeSummary(samplesCopy)

        // Read thermal profile
        val thermalProfile = try {
            Settings.System.getStringForUser(
                context.contentResolver,
                "mithermal_app_profiles",
                UserHandle.USER_CURRENT
            ) ?: "default"
        } catch (_: Exception) { "unknown" }

        return FpsStatsSession(
            id = UUID.randomUUID().toString(),
            packageName = currentPackageName,
            appName = currentAppName,
            startTimeMs = startTimeMs,
            endTimeMs = endTime,
            durationSec = duration,
            platform = SystemProperties.get("ro.board.platform", "unknown").uppercase(),
            model = Build.MODEL,
            osVersion = "Android ${Build.VERSION.RELEASE}",
            thermalProfile = thermalProfile,
            samples = samplesCopy,
            threadSnapshots = threadsCopy,
            summary = summary,
        )
    }

    private suspend fun collectLoop() {
        // Initial /proc/stat read for delta calculation
        readCpuUsage() // Prime the delta baseline

        while (_isRecording.value) {
            try {
                val sample = collectSample()
                samples.add(sample)

                // Thread stats every 2 seconds (heavier operation)
                if (_elapsedSeconds.value % 2 == 0) {
                    val threadSnap = collectThreadSnapshot()
                    if (threadSnap != null) {
                        threadSnapshots.add(threadSnap)
                    }
                }

                // Cap memory: downsample if session exceeds 1 hour (3600 samples)
                if (samples.size > MAX_SAMPLES) {
                    // Remove every other sample from the first half to halve memory
                    synchronized(samples) {
                        val half = samples.size / 2
                        var i = half - 1
                        while (i >= 0) {
                            if (i % 2 == 1) samples.removeAt(i)
                            i--
                        }
                    }
                }

                _elapsedSeconds.value++
            } catch (e: Exception) {
                Log.w(TAG, "Sample failed", e)
            }
            delay(1000L)
        }
    }

    private fun collectSample(): PerformanceSample {
        val now = System.currentTimeMillis()

        // FPS from FpsInteractor (latest value)
        val fpsHistory = fpsInteractor.realFpsHistory.value
        val currentFps = fpsHistory.lastOrNull() ?: 0f
        val frameTimeMs = if (currentFps > 0f) 1000f / currentFps else 0f

        // CPU usage (per-core + total) — also updates prevCpuTotal/prevCpuIdle
        val cpuUsage = readCpuUsage()
        val cpuUsageTotal = cpuUsage.firstOrNull() ?: 0f
        val cpuUsagePerCluster = computeClusterUsage(cpuUsage)

        // CPU frequency (per-cluster, in MHz)
        val cpuFreqPerCluster = readCpuFreqPerCluster()

        // GPU
        val gpuFreqMHz = readGpuFreqMHz()
        val gpuLoad = readGpuLoadPercent()

        // Temperatures
        val cpuTemp = readAverageThermal(cpuThermalZones)
        val gpuTemp = readAverageThermal(gpuThermalZones)
        val ddrTemp = readThermalZone(23)

        // Battery
        val batteryTemp = readBatteryTemp()
        val batteryPower = readBatteryPower()
        val batteryCapacity = readBatteryCapacity()

        return PerformanceSample(
            timestampMs = now,
            fps = currentFps,
            frameTimeMs = frameTimeMs,
            cpuUsageTotal = cpuUsageTotal,
            cpuUsagePerCluster = cpuUsagePerCluster,
            cpuFreqPerCluster = cpuFreqPerCluster,
            gpuFreqMHz = gpuFreqMHz,
            gpuLoadPercent = gpuLoad,
            cpuTempC = cpuTemp,
            gpuTempC = gpuTemp,
            batteryTempC = batteryTemp,
            batteryPowerW = batteryPower,
            batteryCapacity = batteryCapacity,
            ddrTempC = ddrTemp,
        )
    }

    // ── /proc/stat parsing for CPU usage ──

    private fun readCpuUsage(): List<Float> {
        val lines = try {
            File("/proc/stat").readLines()
        } catch (_: Exception) { return emptyList() }

        val stats = mutableListOf<LongArray>()
        for (line in lines) {
            if (!line.startsWith("cpu")) continue
            val parts = line.trim().split(WHITESPACE_REGEX)
            if (parts.size < 8) continue
            // user, nice, system, idle, iowait, irq, softirq, steal
            val values = LongArray(8) { parts[it + 1].toLongOrNull() ?: 0L }
            stats.add(values)
        }

        if (stats.isEmpty()) return emptyList()

        val usages = mutableListOf<Float>()
        val newTotals = LongArray(stats.size)
        val newIdles = LongArray(stats.size)

        for (i in stats.indices) {
            val s = stats[i]
            val total = s.sum()
            val idle = s[3] + s[4] // idle + iowait
            newTotals[i] = total
            newIdles[i] = idle

            if (prevCpuTotal.size > i) {
                val dTotal = total - prevCpuTotal[i]
                val dIdle = idle - prevCpuIdle[i]
                val usage = if (dTotal > 0) (1.0f - dIdle.toFloat() / dTotal.toFloat()) * 100f else 0f
                usages.add(usage.coerceIn(0f, 100f))
            } else {
                usages.add(0f)
            }
        }

        prevCpuTotal = newTotals
        prevCpuIdle = newIdles
        return usages
    }

    private fun computeClusterUsage(allCpuUsage: List<Float>): List<Float> {
        // allCpuUsage[0] = total, [1] = cpu0, [2] = cpu1, etc.
        return cpuClusters.map { cluster ->
            val clusterUsages = cluster.map { cpuIdx ->
                allCpuUsage.getOrNull(cpuIdx + 1) ?: 0f  // +1 because [0] is total
            }
            if (clusterUsages.isNotEmpty()) clusterUsages.average().toFloat() else 0f
        }
    }

    // ── CPU Frequency ──

    private fun readCpuFreqPerCluster(): List<Int> {
        return cpuClusters.map { cluster ->
            val freqs = cluster.map { cpuIdx ->
                readSysfsInt("/sys/devices/system/cpu/cpu$cpuIdx/cpufreq/scaling_cur_freq")
            }
            val avgKHz = if (freqs.isNotEmpty()) freqs.average().toInt() else 0
            avgKHz / 1000 // Convert KHz -> MHz
        }
    }

    // ── GPU ──

    private fun readGpuFreqMHz(): Int {
        val hz = readSysfsLong("/sys/class/kgsl/kgsl-3d0/gpuclk")
        if (hz > 0) return (hz / 1_000_000).toInt()
        val hz2 = readSysfsLong("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")
        return if (hz2 > 0) (hz2 / 1_000_000).toInt() else 0
    }

    private fun readGpuLoadPercent(): Float {
        return try {
            val line = File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").readText().trim()
            line.replace("%", "").trim().toFloatOrNull() ?: 0f
        } catch (_: Exception) { 0f }
    }

    // ── Thermal ──

    private fun readThermalZone(index: Int): Float {
        val milliC = readSysfsInt("/sys/class/thermal/thermal_zone$index/temp")
        return milliC / 1000f
    }

    private fun readAverageThermal(zones: IntArray): Float {
        val temps = zones.map { readThermalZone(it) }.filter { it > 0f }
        return if (temps.isNotEmpty()) temps.average().toFloat() else 0f
    }

    // ── Battery ──

    private fun readBatteryTemp(): Float {
        val deciC = readSysfsInt("/sys/class/power_supply/battery/temp")
        return deciC / 10f
    }

    private fun readBatteryPower(): Float {
        val currentUa = readSysfsLong("/sys/class/power_supply/battery/current_now")
        val voltageUv = readSysfsLong("/sys/class/power_supply/battery/voltage_now")
        // Power = |current| * voltage, convert uA * uV -> W
        // Use toDouble() to avoid Long overflow for extreme values
        return (kotlin.math.abs(currentUa.toDouble() * voltageUv.toDouble()) / 1_000_000_000_000.0).toFloat()
    }

    private fun readBatteryCapacity(): Int {
        return readSysfsInt("/sys/class/power_supply/battery/capacity")
    }

    // ── Thread Statistics ──

    private fun collectThreadSnapshot(): ThreadSnapshot? {
        val pid = getGamePid() ?: return null
        val taskDir = File("/proc/$pid/task")
        if (!taskDir.exists()) return null

        val threads = mutableListOf<ThreadInfo>()

        // Read current total CPU ticks from /proc/stat (line 0 = aggregate)
        val procStatLines = try { File("/proc/stat").readLines() } catch (_: Exception) { return null }
        val cpuLine = procStatLines.firstOrNull { it.startsWith("cpu ") } ?: return null
        val cpuParts = cpuLine.trim().split(WHITESPACE_REGEX)
        if (cpuParts.size < 8) return null
        val currentTotalCpu = (1..7).sumOf { cpuParts[it].toLongOrNull() ?: 0L }

        val deltaTotalCpu = if (prevTotalCpuTicks > 0) currentTotalCpu - prevTotalCpuTicks else 0L

        val currentThreadTicks = mutableMapOf<Int, Long>()
        val numCpus = Runtime.getRuntime().availableProcessors()

        try {
            val taskFiles = taskDir.listFiles() ?: return null
            for (tidDir in taskFiles) {
                val tid = tidDir.name.toIntOrNull() ?: continue
                val statFile = File(tidDir, "stat")
                if (!statFile.exists()) continue

                try {
                    val statLine = statFile.readText().trim()
                    // Format: pid (comm) state ... utime stime ...
                    val closeParen = statLine.lastIndexOf(')')
                    if (closeParen < 0) continue
                    val afterComm = statLine.substring(closeParen + 2).split(' ')
                    if (afterComm.size < 13) continue

                    val name = statLine.substring(
                        statLine.indexOf('(') + 1,
                        closeParen
                    )
                    // Fields after state: index 11 = utime, 12 = stime (0-based after state field)
                    val utime = afterComm[11].toLongOrNull() ?: 0L
                    val stime = afterComm[12].toLongOrNull() ?: 0L
                    val ticks = utime + stime

                    currentThreadTicks[tid] = ticks

                    val prevTicks = prevThreadTicks[tid] ?: 0L
                    val deltaTicks = ticks - prevTicks

                    val cpuPercent = if (deltaTotalCpu > 0 && prevTicks > 0) {
                        (deltaTicks.toFloat() / deltaTotalCpu.toFloat()) * 100f * numCpus
                    } else 0f

                    if (cpuPercent > 0.1f) {  // Filter noise
                        threads.add(ThreadInfo(
                            tid = tid,
                            name = name,
                            cpuPercent = cpuPercent.coerceIn(0f, 100f),
                        ))
                    }
                } catch (_: Exception) { /* skip this thread */ }
            }
        } catch (_: Exception) { return null }

        prevThreadTicks = currentThreadTicks
        prevTotalCpuTicks = currentTotalCpu

        // Sort by CPU usage descending, take top 20
        return ThreadSnapshot(
            timestampMs = System.currentTimeMillis(),
            threads = threads.sortedByDescending { it.cpuPercent }.take(20),
        )
    }

    private fun getGamePid(): Int? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.find { it.processName == currentPackageName }?.pid
        } catch (_: Exception) { null }
    }

    // ── Sysfs helpers ──

    private fun readSysfsInt(path: String): Int {
        return try {
            File(path).readText().trim().toIntOrNull() ?: 0
        } catch (_: Exception) { 0 }
    }

    private fun readSysfsLong(path: String): Long {
        return try {
            File(path).readText().trim().toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
    }

    companion object {
        private const val TAG = "FpsStatsCollector"
        private const val MAX_SAMPLES = 3600 // 1 hour at 1Hz
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }
}
