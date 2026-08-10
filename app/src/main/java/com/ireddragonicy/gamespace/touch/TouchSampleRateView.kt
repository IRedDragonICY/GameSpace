/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.touch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Live multi-touch sample-rate readout.
 *
 * Why a View (not Compose Canvas): Compose [androidx.compose.ui.input.pointer.PointerEvent]
 * does NOT expose batched historical samples, so a Compose-only tester caps at the
 * display refresh rate. The panel's true report rate (480/540/571 Hz…) only survives
 * in [MotionEvent.getHistoricalEventTime], which we read here.
 *
 * Anti-scroll: we are usually hosted inside a Compose verticalScroll. Compose's
 * scrollable becomes the hit-target (the AndroidView node has no pointerInput), so
 * without [requestDisallowInterceptTouchEvent] a drag both draws here AND scrolls the
 * panel. Disallowing intercept on DOWN/MOVE fixes it cleanly.
 */
class TouchSampleRateView @JvmOverloads constructor(
    context: Context,
    private val compact: Boolean = false,
    private val expanded: Boolean = false,
) : View(context) {

    private val meter = RateMeter()

    // per-finger latest position (rebuilt every event) → multi-touch viz
    private val pointers = mutableMapOf<Int, PointF>()
    private var estLagMs = 0f

    // rate sparkline ring buffer (pushed ~25 Hz)
    private val spark = IntArray(72)
    private var sparkHead = 0
    private var sparkCount = 0
    private var lastSparkT = 0L

    private val crosshair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 2f }
    private val fingerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val fingerId = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = if (compact) 28f else 34f
    }
    private val rate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; textSize = if (compact) 84f else 132f; isFakeBoldText = true
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 2.5f
    }

    // cached strings (onDraw must not allocate)
    private var sInstant = -1; private var sPeak = -1; private var sAvg = -1
    private var sDisp = -1; private var sFingers = -1; private var sJit = -1f; private var sLag = -1f
    private var instantText = "0 Hz"
    private var detailText = ""
    private var extraText = ""

    var topOffset = 0f
    var bottomOffset = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val mask = event.actionMasked
        if (mask == MotionEvent.ACTION_DOWN) { meter.reset(); estLagMs = 0f }

        // rebuild active-pointer map (multi-touch)
        pointers.clear()
        for (i in 0 until event.pointerCount) {
            pointers[event.getPointerId(i)] = PointF(event.getX(i), event.getY(i))
        }

        // feed the panel-rate timeline (pointer-0 stream; history = true rate)
        when (mask) {
            MotionEvent.ACTION_DOWN -> {
                meter.addSample(event.eventTime); meter.addDispatch(event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) meter.addSample(event.getHistoricalEventTime(h))
                meter.addSample(event.eventTime); meter.addDispatch(event.eventTime)
            }
        }
        if (mask == MotionEvent.ACTION_UP || mask == MotionEvent.ACTION_CANCEL) {
            meter.stop(); pointers.clear()
        }

        // input-pipeline lag estimate (hardware timestamp → UI callback)
        val lag = (SystemClock.uptimeMillis() - event.eventTime).toFloat().coerceAtLeast(0f)
        estLagMs = if (estLagMs == 0f) lag else estLagMs * 0.8f + lag * 0.2f

        // THE scroll-conflict fix: stop Compose/View ancestors from stealing the drag
        parent?.requestDisallowInterceptTouchEvent(
            mask != MotionEvent.ACTION_UP && mask != MotionEvent.ACTION_CANCEL
        )

        meter.update(event.eventTime)

        // sparkline sample (~25 Hz)
        if (event.eventTime - lastSparkT > 40) {
            lastSparkT = event.eventTime
            spark[sparkHead] = meter.instantHz
            sparkHead = (sparkHead + 1) % spark.size
            if (sparkCount < spark.size) sparkCount++
        }

        refreshTexts()
        invalidate()
        return true
    }

    private fun refreshTexts() {
        val fingers = pointers.size
        if (meter.instantHz != sInstant || fingers != sFingers ||
            meter.peakHz != sPeak || meter.averageHz != sAvg || meter.dispatchHz != sDisp ||
            meter.jitterMs != sJit || estLagMs != sLag
        ) {
            sInstant = meter.instantHz; sPeak = meter.peakHz; sAvg = meter.averageHz
            sDisp = meter.dispatchHz; sFingers = fingers; sJit = meter.jitterMs; sLag = estLagMs
            instantText = if (fingers > 0) "$sInstant Hz · ${fingers}" else "$sInstant Hz"
            detailText = "peak $sPeak  avg(1s) $sAvg  dispatch $sDisp  σ ${"%.1f".format(sJit)}ms"
            extraText = "est input-lag ${"%.1f".format(sLag)}ms · samples ${meter.sampleCount}" +
                " · Δ ${meter.minDeltaMs}–${meter.maxDeltaMs}ms"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        // one crosshair + ring + id per active finger
        for ((id, p) in pointers) {
            canvas.drawLine(p.x, 0f, p.x, height.toFloat(), crosshair)
            canvas.drawLine(0f, p.y, width.toFloat(), p.y, crosshair)
            canvas.drawCircle(p.x, p.y, 26f, fingerRing)
            canvas.drawText(id.toString(), p.x, p.y + 8f, fingerId)
        }

        val base = (if (compact) 104f else 150f) + topOffset
        canvas.drawText(instantText, 36f, base, rate)
        canvas.drawText(detailText, 36f, base + (if (compact) 40f else 56f), label)
        if (expanded) {
            canvas.drawText(extraText, 36f, base + 96f, label)
            drawSparkline(canvas)
        }
        canvas.drawText(
            if (meter.tracking) "Keep dragging · add more fingers to test multi-touch"
            else "Touch & drag · multi-touch supported",
            36f, height - (if (compact) 24f else 40f) - bottomOffset, label
        )
    }

    private fun drawSparkline(canvas: Canvas) {
        if (sparkCount < 2) return
        val left = 36f
        val top = height - 150f - bottomOffset
        val w = width - left - 36f
        val h = 70f
        val peak = (meter.peakHz.coerceAtLeast(120)).toFloat()
        // frame
        canvas.drawLine(left, top + h, left + w, top + h, crosshair)
        var prevX = 0f; var prevY = 0f; var first = true
        for (k in 0 until sparkCount) {
            val idx = (sparkHead - sparkCount + k + spark.size) % spark.size
            val x = left + w * k / (spark.size - 1).coerceAtLeast(1)
            val y = top + h - (spark[idx].coerceAtLeast(0) / peak).coerceAtMost(1f) * h
            if (!first) canvas.drawLine(prevX, prevY, x, y, sparkPaint)
            prevX = x; prevY = y; first = false
        }
    }
}
