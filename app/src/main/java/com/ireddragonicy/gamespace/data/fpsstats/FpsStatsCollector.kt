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
    private val bus: com.ireddragonicy.gamespace.telemetry.TelemetryBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds
    private var recordingJob: Job? = null
    private var currentPackageName = ""
    private var currentAppName = ""
    private var startTimeMs = 0L
    private val samples: MutableList<PerformanceSample> = Collections.synchronizedList(mutableListOf())
    private val threadSnapshots: MutableList<ThreadSnapshot> = Collections.synchronizedList(mutableListOf())
    private var prevThreadTicks = mutableMapOf<Int, Long>()
    private var prevTotalCpuTicks = 0L

    fun startRecording(packageName: String, appName: String) {
        if (_isRecording.value) return
        currentPackageName = packageName; currentAppName = appName
        startTimeMs = System.currentTimeMillis()
        samples.clear(); threadSnapshots.clear()
        prevThreadTicks.clear(); prevTotalCpuTicks = 0L
        _elapsedSeconds.value = 0
        bus.start()
        _isRecording.value = true
        recordingJob = scope.launch { collectLoop() }
    }

    fun stopRecording(): FpsStatsSession? {
        if (!_isRecording.value) return null
        _isRecording.value = false
        recordingJob?.cancel(); recordingJob = null
        bus.stop()

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
        val s = bus.snapshot.value                       // the SAME frame the panel rendered
        val fpsHistory = fpsInteractor.realFpsHistory.value
        val currentFps = fpsHistory.lastOrNull() ?: 0f
        val frameTimeMs = if (currentFps > 0f) 1000f / currentFps else 0f
        return PerformanceSample(
            timestampMs = now,
            fps = currentFps,
            frameTimeMs = frameTimeMs,
            cpuUsageTotal = s.cpuUsageTotal.toFloat(),
            cpuUsagePerCluster = s.cpuUsagePerCluster,
            cpuFreqPerCluster = s.cpuFreqPerClusterMhz,
            gpuFreqMHz = s.gpuFreqMhz,
            gpuLoadPercent = s.gpuUsage.toFloat(),
            cpuTempC = s.cpuTempC,
            gpuTempC = s.gpuTempC,        // honest die temp — no more 104 °C in recordings
            batteryTempC = s.batteryTempC,
            batteryPowerW = s.batteryPowerW,
            batteryCapacity = s.batteryCapacity,
            ddrTempC = s.ddrTempC,
        )
    }

    private var cachedPid: Int = -1
    private var cachedTaskFiles: Array<File>? = null

    // ── Thread Statistics ──

    private fun collectThreadSnapshot(): ThreadSnapshot? {
        val pid = getGamePid() ?: return null
        if (pid != cachedPid) {
            cachedTaskFiles = File("/proc/$pid/task").listFiles()
            cachedPid = pid
        }
        val taskFiles = cachedTaskFiles ?: return null

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



    companion object {
        private const val TAG = "FpsStatsCollector"
        private const val MAX_SAMPLES = 3600 // 1 hour at 1Hz
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }
}
