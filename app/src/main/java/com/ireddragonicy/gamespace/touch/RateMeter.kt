/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.touch

/**
 * Windowed event-rate meter + jitter stats for touch sample streams.
 *
 * Pure logic, no Android deps. Feed every sample timestamp (history included,
 * in order) via [addSample]; feed the per-callback timestamp via [addDispatch].
 * Read rates + jitter after [update].
 */
class RateMeter {
    private companion object {
        const val WINDOW_MS = 1000L
        const val MIN_WINDOW_MS = 100L
        const val INSTANT_DEPTH = 20
        const val MAX_DELTA_MS = 101L
    }

    private val windowSamples = ArrayDeque<Long>()
    private val dispatchSamples = ArrayDeque<Long>()
    private val recentDeltas = ArrayDeque<Long>()
    private var lastSampleTime = 0L

    var averageHz: Int = 0; private set
    var instantHz: Int = 0; private set
    var dispatchHz: Int = 0; private set
    var peakHz: Int = 0; private set
    var tracking: Boolean = false; private set

    // ── new: spread / throughput stats ────────────────────────────────────
    /** Std-dev of recent inter-sample deltas (ms) = sample jitter. */
    var jitterMs: Float = 0f; private set
    var minDeltaMs: Long = 0L; private set
    var maxDeltaMs: Long = 0L; private set
    /** Total samples fed since [reset] (all fingers' timeline). */
    var sampleCount: Int = 0; private set

    fun reset() {
        tracking = true
        windowSamples.clear(); dispatchSamples.clear(); recentDeltas.clear()
        lastSampleTime = 0L; peakHz = 0
        jitterMs = 0f; minDeltaMs = 0L; maxDeltaMs = 0L; sampleCount = 0
    }

    fun stop() { tracking = false }

    fun addSample(timeMs: Long) {
        sampleCount++
        val last = lastSampleTime
        if (last in 1..timeMs) {
            val delta = timeMs - last
            if (delta < MAX_DELTA_MS) {
                recentDeltas.addLast(delta)
                while (recentDeltas.size > INSTANT_DEPTH) recentDeltas.removeFirst()
                if (minDeltaMs == 0L || delta < minDeltaMs) minDeltaMs = delta
                if (delta > maxDeltaMs) maxDeltaMs = delta
            }
        }
        lastSampleTime = timeMs
        windowSamples.addLast(timeMs)
    }

    fun addDispatch(timeMs: Long) { dispatchSamples.addLast(timeMs) }

    fun update(nowMs: Long) {
        averageHz = windowedRate(windowSamples, nowMs)
        dispatchHz = windowedRate(dispatchSamples, nowMs)
        val sum = recentDeltas.sum()
        instantHz = if (sum > 0) ((recentDeltas.size * 1000L) / sum).toInt() else 0
        if (tracking && instantHz > peakHz) peakHz = instantHz
        // population-corrected std-dev of the delta window
        val n = recentDeltas.size
        jitterMs = if (n > 1) {
            val mean = sum.toDouble() / n
            var v = 0.0
            for (d in recentDeltas) { val dd = d - mean; v += dd * dd }
            kotlin.math.sqrt(v / (n - 1)).toFloat()
        } else 0f
    }

    private fun windowedRate(samples: ArrayDeque<Long>, nowMs: Long): Int {
        while (samples.isNotEmpty() && nowMs - samples.first() > WINDOW_MS) samples.removeFirst()
        if (samples.isEmpty()) return 0
        val duration = nowMs - samples.first()
        return if (duration > MIN_WINDOW_MS) ((samples.size * 1000L) / duration).toInt()
        else samples.size
    }
}
