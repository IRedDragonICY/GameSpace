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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateMeterTest {

    /** Feed a steady stream at [hz] for [durationMs], batched like vsync dispatch. */
    private fun RateMeter.feedSteady(hz: Int, durationMs: Long, batchMs: Long = 8L): Long {
        val periodUs = 1_000_000L / hz
        var tUs = 0L
        var lastDispatch = 0L
        reset()
        while (tUs / 1000 <= durationMs) {
            addSample(tUs / 1000)
            if (tUs / 1000 - lastDispatch >= batchMs) {
                lastDispatch = tUs / 1000
                addDispatch(lastDispatch)
                update(lastDispatch)
            }
            tUs += periodUs
        }
        update(durationMs)
        return durationMs
    }

    @Test
    fun steady480HzMeasures480() {
        val meter = RateMeter()
        meter.feedSteady(hz = 480, durationMs = 2000)
        assertTrue("avg=${meter.averageHz}", meter.averageHz in 470..490)
        assertTrue("instant=${meter.instantHz}", meter.instantHz in 450..510)
        assertTrue("peak=${meter.peakHz}", meter.peakHz >= meter.instantHz)
    }

    @Test
    fun steady120HzMeasures120() {
        val meter = RateMeter()
        meter.feedSteady(hz = 120, durationMs = 2000)
        assertTrue("avg=${meter.averageHz}", meter.averageHz in 115..125)
    }

    @Test
    fun dispatchRateStaysAtVsyncWhileSamplesRunFast() {
        val meter = RateMeter()
        // 480 Hz samples delivered in 8 ms batches ≈ 120 Hz dispatch
        meter.feedSteady(hz = 480, durationMs = 2000, batchMs = 8)
        assertTrue("dispatch=${meter.dispatchHz}", meter.dispatchHz in 110..135)
        assertTrue("avg=${meter.averageHz}", meter.averageHz in 470..490)
    }

    @Test
    fun windowEvictsOldSamples() {
        val meter = RateMeter()
        meter.reset()
        // 480 Hz burst for 500 ms, then silence until t=5000 — window must be empty
        var t = 0L
        while (t <= 500) {
            meter.addSample(t)
            t += 2
        }
        meter.update(5000)
        assertEquals(0, meter.averageHz)
    }

    @Test
    fun liftGapDoesNotPolluteInstantRate() {
        val meter = RateMeter()
        meter.reset()
        // 10 samples at 2 ms, a 400 ms hole (lift), then 10 more at 2 ms
        var t = 0L
        repeat(10) { meter.addSample(t); t += 2 }
        t += 400
        repeat(10) { meter.addSample(t); t += 2 }
        meter.update(t)
        // The 400 ms delta must be discarded (≥ 101 ms), leaving ~500 Hz instant
        assertTrue("instant=${meter.instantHz}", meter.instantHz in 450..550)
    }

    @Test
    fun peakOnlyMovesWhileTracking() {
        val meter = RateMeter()
        meter.reset()
        var t = 0L
        repeat(50) { meter.addSample(t); t += 2 }
        meter.update(t)
        val peak = meter.peakHz
        assertTrue(peak > 0)

        meter.stop()
        repeat(50) { meter.addSample(t); t += 1 } // faster stream after stop
        meter.update(t)
        assertEquals(peak, meter.peakHz)
    }

    @Test
    fun resetClearsEverything() {
        val meter = RateMeter()
        meter.feedSteady(hz = 480, durationMs = 1000)
        meter.reset()
        assertEquals(0, meter.peakHz)
        meter.update(2000)
        assertEquals(0, meter.averageHz)
    }
}
