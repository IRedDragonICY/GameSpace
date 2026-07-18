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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Live touch sample-rate readout, embeddable anywhere (panel tab or full screen).
 *
 * Measurement notes:
 * - Deliberately does NOT call requestUnbufferedDispatch: at high sample rates
 *   the extra samples arrive as history inside per-vsync callbacks; unbuffered
 *   dispatch disables that batching and consecutive MOVEs get coalesced (lost)
 *   whenever the UI thread lags, capping the reading near the refresh rate.
 * - Historical samples carry the real panel rate; the dispatch rate shown
 *   separately naturally sits near the display refresh rate.
 *
 * All rate math lives in [RateMeter] (unit-tested); this view only draws.
 */
class TouchSampleRateView @JvmOverloads constructor(
    context: Context,
    private val compact: Boolean = false,
) : View(context) {

    private val meter = RateMeter()

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = if (compact) 32f else 44f
    }
    private val ratePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = if (compact) 96f else 150f
        isFakeBoldText = true
    }

    private var touchX = -1f
    private var touchY = -1f

    // Draw strings are rebuilt only when the displayed integers change:
    // onDraw runs at event rate and must not allocate.
    private var shownInstant = -1
    private var shownAverage = -1
    private var shownDispatch = -1
    private var shownPeak = -1
    private var instantText = "0 Hz"
    private var detailText = "peak: 0 Hz    avg(1s): 0 Hz    dispatch: 0 Hz"

    var topOffset = 0f
    var bottomOffset = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                meter.reset()
                meter.addSample(event.eventTime)
                meter.addDispatch(event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    meter.addSample(event.getHistoricalEventTime(i))
                }
                meter.addSample(event.eventTime)
                meter.addDispatch(event.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> meter.stop()
        }

        touchX = event.x
        touchY = event.y
        meter.update(event.eventTime)
        refreshTexts()
        invalidate()
        return true
    }

    private fun refreshTexts() {
        if (meter.instantHz != shownInstant) {
            shownInstant = meter.instantHz
            instantText = "$shownInstant Hz"
        }
        if (meter.peakHz != shownPeak || meter.averageHz != shownAverage ||
            meter.dispatchHz != shownDispatch
        ) {
            shownPeak = meter.peakHz
            shownAverage = meter.averageHz
            shownDispatch = meter.dispatchHz
            detailText =
                "peak: $shownPeak Hz    avg(1s): $shownAverage Hz    dispatch: $shownDispatch Hz"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        if (meter.tracking && touchX >= 0f) {
            canvas.drawLine(touchX, 0f, touchX, height.toFloat(), crosshairPaint)
            canvas.drawLine(0f, touchY, width.toFloat(), touchY, crosshairPaint)
        }

        val baseline = (if (compact) 120f else 230f) + topOffset
        canvas.drawText(instantText, 60f, baseline, ratePaint)
        canvas.drawText(detailText, 60f, baseline + (if (compact) 48f else 80f), labelPaint)
        canvas.drawText(
            if (meter.tracking) "Keep dragging your finger…" else "Touch and drag to measure",
            60f,
            height - (if (compact) 32f else 90f) - bottomOffset,
            labelPaint,
        )
    }
}
