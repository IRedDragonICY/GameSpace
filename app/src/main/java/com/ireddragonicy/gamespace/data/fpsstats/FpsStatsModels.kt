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

/**
 * Single performance sample collected every ~1 second during a recording session.
 */
data class PerformanceSample(
    val timestampMs: Long,
    val fps: Float,
    val frameTimeMs: Float,
    val cpuUsageTotal: Float,
    val cpuUsagePerCluster: List<Float>,
    val cpuFreqPerCluster: List<Int>,
    val gpuFreqMHz: Int,
    val gpuLoadPercent: Float,
    val cpuTempC: Float,
    val gpuTempC: Float,
    val batteryTempC: Float,
    val batteryPowerW: Float,
    val batteryCapacity: Int,
    val ddrTempC: Float,
)

/**
 * Per-thread CPU usage information.
 */
data class ThreadInfo(
    val tid: Int,
    val name: String,
    val cpuPercent: Float,
)

/**
 * Snapshot of thread statistics at a point in time (collected every ~2s).
 */
data class ThreadSnapshot(
    val timestampMs: Long,
    val threads: List<ThreadInfo>,
)

/**
 * Summary statistics computed at the end of a recording session.
 */
data class SessionSummary(
    val fpsMax: Float = 0f,
    val fpsMin: Float = 0f,
    val fpsAvg: Float = 0f,
    val fpsVariance: Float = 0f,
    val smoothnessPercent: Float = 0f,
    val fpsLow5Percent: Float = 0f,
    val jankCount: Int = 0,
    val bigJankCount: Int = 0,
    val frameTimeMaxMs: Float = 0f,
    val tempMaxC: Float = 0f,
    val tempMinC: Float = 0f,
    val tempAvgC: Float = 0f,
    val powerMaxW: Float = 0f,
    val powerMinW: Float = 0f,
    val powerAvgW: Float = 0f,
)

/**
 * Complete recording session with all samples and computed summary.
 */
data class FpsStatsSession(
    val id: String,
    val packageName: String,
    val appName: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationSec: Int,
    val platform: String,
    val model: String,
    val osVersion: String,
    val thermalProfile: String,
    val samples: List<PerformanceSample>,
    val threadSnapshots: List<ThreadSnapshot>,
    val summary: SessionSummary,
) {
    companion object {
        /**
         * Compute summary statistics from raw samples.
         */
        fun computeSummary(
            samples: List<PerformanceSample>,
            targetFps: Float = 60f,
        ): SessionSummary {
            if (samples.isEmpty()) return SessionSummary()

            val fpsValues = samples.map { it.fps }.filter { it > 0f }
            if (fpsValues.isEmpty()) return SessionSummary()

            val fpsMax = fpsValues.max()
            val fpsMin = fpsValues.min()
            val fpsAvg = fpsValues.average().toFloat()
            val fpsVariance = fpsValues.map { (it - fpsAvg) * (it - fpsAvg) }
                .average().toFloat()

            // Smoothness: % of frames >= targetFps/2 (e.g. >=30fps for 60fps target)
            val smoothThreshold = targetFps * 0.75f  // >=45fps for 60fps target
            val smoothnessPercent = fpsValues.count { it >= smoothThreshold } * 100f / fpsValues.size

            // 5% low: sort ascending, take 5th percentile
            val sorted = fpsValues.sorted()
            val p5Index = (sorted.size * 0.05f).toInt().coerceIn(0, sorted.size - 1)
            val fpsLow5 = sorted[p5Index]

            // Jank: frame time > 2× target (e.g. >33.3ms for 60fps)
            val targetFrameTimeMs = 1000f / targetFps
            val frameTimes = samples.map { it.frameTimeMs }.filter { it > 0f }
            val jankCount = frameTimes.count { it > targetFrameTimeMs * 2f }
            val bigJankCount = frameTimes.count { it > targetFrameTimeMs * 4f }
            val frameTimeMax = frameTimes.maxOrNull() ?: 0f

            val temps = samples.map { it.cpuTempC }.filter { it > 0f }
            val powers = samples.map { it.batteryPowerW }

            return SessionSummary(
                fpsMax = fpsMax,
                fpsMin = fpsMin,
                fpsAvg = fpsAvg,
                fpsVariance = fpsVariance,
                smoothnessPercent = smoothnessPercent,
                fpsLow5Percent = fpsLow5,
                jankCount = jankCount,
                bigJankCount = bigJankCount,
                frameTimeMaxMs = frameTimeMax,
                tempMaxC = temps.maxOrNull() ?: 0f,
                tempMinC = temps.minOrNull() ?: 0f,
                tempAvgC = if (temps.isNotEmpty()) temps.average().toFloat() else 0f,
                powerMaxW = powers.maxOrNull() ?: 0f,
                powerMinW = powers.minOrNull() ?: 0f,
                powerAvgW = if (powers.isNotEmpty()) powers.average().toFloat() else 0f,
            )
        }
    }
}

/**
 * Lightweight session info for the list view (without samples data).
 */
data class SessionListItem(
    val id: String,
    val packageName: String,
    val appName: String,
    val startTimeMs: Long,
    val durationSec: Int,
    val fpsAvg: Float,
    val powerAvgW: Float,
)
