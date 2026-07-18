/*
 * Copyright (C) 2026 IRedDragonICY
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
package com.ireddragonicy.gamespace.touch

/**
 * Windowed event-rate meter for touch sample streams.
 *
 * Pure logic, no Android dependencies — unit-testable. Feed it every sample
 * timestamp (historical samples included, in order) via [addSample]; feed the
 * per-callback timestamp via [addDispatch]. Read rates after [update].
 *
 * Semantics (kept bit-identical to the original tester):
 * - [averageHz]: samples over a sliding 1 s window.
 * - [instantHz]: harmonic rate of the last [INSTANT_DEPTH] inter-sample deltas;
 *   deltas ≥ [MAX_DELTA_MS] ms (finger lift / stall) are discarded.
 * - [dispatchHz]: rate of dispatch callbacks only — under vsync batching this
 *   sits near the display refresh rate and is NOT the panel sample rate.
 * - [peakHz]: highest [instantHz] observed since [reset] while tracking.
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

    var averageHz: Int = 0
        private set
    var instantHz: Int = 0
        private set
    var dispatchHz: Int = 0
        private set
    var peakHz: Int = 0
        private set
    var tracking: Boolean = false
        private set

    /** Start a new measurement run (finger down). */
    fun reset() {
        tracking = true
        windowSamples.clear()
        dispatchSamples.clear()
        recentDeltas.clear()
        lastSampleTime = 0L
        peakHz = 0
    }

    /** Stop tracking (finger lifted); rates keep their last values. */
    fun stop() {
        tracking = false
    }

    /** Record one panel sample timestamp (ms). Call in chronological order. */
    fun addSample(timeMs: Long) {
        val last = lastSampleTime
        if (last in 1..timeMs) {
            val delta = timeMs - last
            if (delta < MAX_DELTA_MS) {
                recentDeltas.addLast(delta)
                while (recentDeltas.size > INSTANT_DEPTH) recentDeltas.removeFirst()
            }
        }
        lastSampleTime = timeMs
        windowSamples.addLast(timeMs)
    }

    /** Record one dispatch callback timestamp (ms). */
    fun addDispatch(timeMs: Long) {
        dispatchSamples.addLast(timeMs)
    }

    /** Evict stale samples and recompute all rates as of [nowMs]. */
    fun update(nowMs: Long) {
        averageHz = windowedRate(windowSamples, nowMs)
        dispatchHz = windowedRate(dispatchSamples, nowMs)

        val sum = recentDeltas.sum()
        val rate = if (sum > 0) ((recentDeltas.size * 1000L) / sum).toInt() else 0
        instantHz = rate

        if (tracking && rate > peakHz) peakHz = rate
    }

    private fun windowedRate(samples: ArrayDeque<Long>, nowMs: Long): Int {
        while (samples.isNotEmpty() && nowMs - samples.first() > WINDOW_MS) {
            samples.removeFirst()
        }
        if (samples.isEmpty()) return 0
        val duration = nowMs - samples.first()
        return if (duration > MIN_WINDOW_MS) {
            ((samples.size * 1000L) / duration).toInt()
        } else {
            samples.size
        }
    }
}
