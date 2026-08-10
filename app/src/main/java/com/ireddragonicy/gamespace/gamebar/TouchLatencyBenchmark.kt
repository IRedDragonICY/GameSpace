/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.touch.GameTouchModeManager
import com.ireddragonicy.gamespace.touch.XiaomiTouchFeatureClient
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

private const val PREF_BUCKETS = "touch_benchmark_buckets"
private val GOOD = Color(0xFF00E676)
private val WARN = Color(0xFFFFA726)
private val BAD = Color(0xFFFF5252)
private val DIM = Color.White.copy(alpha = 0.55f)

private fun latColor(ms: Float) = when {
    ms < 0f -> DIM
    ms < 12f -> GOOD
    ms < 22f -> WARN
    else -> BAD
}

/**
 * A/B key for a latency sample.
 *
 * The benchmark used to bucket by HTSR and Super Touch. Both are no-ops on this
 * touch stack, so every run landed in a different bucket while measuring the
 * exact same hardware — the comparison could only ever show noise. The panel
 * scan rate is the one touch variable that genuinely changes, so results are
 * bucketed by the rate the driver reports.
 */
private fun sig(rateHz: Int) = "${rateHz}Hz"

/**
 * The panel's scan rate at the moment a sample is taken, straight from the
 * driver. Read per sample rather than cached: the point of the bucket is to
 * label the sample with the configuration it was actually measured under.
 */
private fun currentReportRateHz(): Int =
    XiaomiTouchFeatureClient
        .getModeCurValue(XiaomiTouchFeatureClient.MODE_REPORT_RATE)
        .takeIf { it > 0 }
        ?: GameTouchModeManager.REPORT_RATE_NORMAL

// ── Bucket: internal (bukan private) agar bisa jadi return type ──
internal data class Bucket(
    var n: Int,
    var sIn: Float,
    var sDisp: Float,
    var sReact: Float,
) {
    val inMs get() = if (n > 0) sIn / n else -1f
    val dispMs get() = if (n > 0) sDisp / n else -1f
    val reactMs get() = if (n > 0) sReact / n else -1f
}

private fun loadBuckets(c: Context): MutableMap<String, Bucket> {
    val raw = c.getSharedPreferences(PREF_BUCKETS, Context.MODE_PRIVATE)
        .getString("b", null) ?: return mutableMapOf()
    return runCatching {
        val o = JSONObject(raw)
        val m = mutableMapOf<String, Bucket>()
        o.keys().forEach { k ->
            val b = o.getJSONObject(k)
            m[k] = Bucket(
                b.getInt("n"),
                b.getDouble("sIn").toFloat(),
                b.getDouble("sDisp").toFloat(),
                b.getDouble("sReact").toFloat(),
            )
        }
        m
    }.getOrDefault(mutableMapOf())
}

private fun saveBuckets(c: Context, m: Map<String, Bucket>) {
    val o = JSONObject()
    m.forEach { (k, b) ->
        o.put(k, JSONObject()
            .put("n", b.n)
            .put("sIn", b.sIn.toDouble())
            .put("sDisp", b.sDisp.toDouble())
            .put("sReact", b.sReact.toDouble()))
    }
    c.getSharedPreferences(PREF_BUCKETS, Context.MODE_PRIVATE)
        .edit().putString("b", o.toString()).apply()
}

// ── Session state ──
class BenchState(val context: Context) {
    var tapIn = mutableStateListOf<Float>()
    var tapDisp = mutableStateListOf<Float>()
    var tapReact = mutableStateListOf<Float>()
    var swipeDev = mutableStateListOf<Float>()
    var swipeRate = mutableStateListOf<Int>()

    private val _buckets = loadBuckets(context)
    var bump by mutableIntStateOf(0)

    internal fun bucketsMap(): Map<String, Bucket> = _buckets

    fun recordTap(inMs: Float, dispMs: Float, reactMs: Float) {
        tapIn.add(inMs); tapDisp.add(dispMs); tapReact.add(reactMs)
        val s = sig(currentReportRateHz())
        val b = _buckets.getOrPut(s) { Bucket(0, 0f, 0f, 0f) }
        b.n++; b.sIn += inMs; b.sDisp += dispMs; b.sReact += reactMs
        saveBuckets(context, _buckets)
        bump++
    }

    fun recordSwipe(dev: Float, hz: Int) {
        swipeDev.add(dev); swipeRate.add(hz)
    }

    fun reset() {
        tapIn.clear(); tapDisp.clear(); tapReact.clear()
        swipeDev.clear(); swipeRate.clear()
        _buckets.clear(); saveBuckets(context, _buckets)
        bump++
    }
}

@Composable
fun rememberBenchState(): BenchState {
    val c = LocalContext.current
    return remember { BenchState(c) }
}

// ─────────────────────────────────────────────────────────────
// TAP LATENCY TEST  (detectTapGestures — stabil di Compose 1.8)
// ─────────────────────────────────────────────────────────────
@Composable
fun TapLatencyTest(state: BenchState, accent: Color, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var target by remember { mutableStateOf<Offset?>(null) }
    var spawnAt by remember { mutableLongStateOf(0L) }
    var last by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    val R = 26.dp

    Box(
        modifier = modifier
            .fillMaxWidth().height(200.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF05070C))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    val now = SystemClock.uptimeMillis()
                    // input→callback latency (full pipeline: HW → kernel → dispatcher → UI)
                    val inMs = if (spawnAt > 0) (now - spawnAt).toFloat() else 0f
                    // input→display estimate: next vsync
                    var dispMs = inMs
                    Choreographer.getInstance().postFrameCallback {
                        dispMs = (SystemClock.uptimeMillis() - spawnAt).toFloat()
                    }
                    val react = if (target != null && spawnAt > 0)
                        (now - spawnAt).toFloat() else -1f
                    last = Triple(inMs, dispMs, react)
                    state.recordTap(inMs, dispMs, react)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    target = null
                })
            },
    ) {
        // target dot
        target?.let { t ->
            Box(
                Modifier
                    .offset { IntOffset(t.x.toInt() - 26, t.y.toInt() - 26) }
                    .size(52.dp).clip(CircleShape)
                    .background(accent.copy(alpha = 0.85f))
                    .border(2.dp, Color.White, CircleShape),
            )
        }
        // last readout
        last?.let { (i, d, r) ->
            Column(Modifier.padding(10.dp)) {
                Text("in→cb ${"%.1f".format(i)}ms",
                    color = latColor(i), fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("in→disp ${"%.1f".format(d)}ms",
                    color = latColor(d), fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
                if (r >= 0) Text("react ${r.toInt()}ms",
                    color = DIM, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        // spawn button
        Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .clip(RoundedCornerShape(8.dp)).background(accent)
                .clickable {
                    if (size.width > 60 && size.height > 60) {
                        target = Offset(
                            Random.nextFloat() * (size.width - 80) + 40,
                            Random.nextFloat() * (size.height - 80) + 40,
                        )
                        spawnAt = SystemClock.uptimeMillis()
                    }
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("TAP TARGET", color = Color.Black,
                fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        if (target == null) {
            Text("Tap anywhere to measure · or spawn a target",
                color = DIM, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.Center))
        }
    }
}

// ─────────────────────────────────────────────────────────────
// SWIPE TRACKING TEST  (detectDragGestures — stabil di Compose 1.8)
// ─────────────────────────────────────────────────────────────
@Composable
fun SwipeTrackingTest(state: BenchState, accent: Color, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var trail by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var result by remember { mutableStateOf<Pair<Float, Int>?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth().height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF05070C))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset: Offset -> trail = listOf(offset) },
                    onDragEnd = {
                        val midY = size.height / 2f
                        if (trail.size > 3) {
                            val devs = trail.map { abs(it.y - midY) }
                            val avg = devs.average().toFloat()
                            result = avg to trail.size
                            state.recordSwipe(avg, trail.size)
                        }
                        trail = emptyList()
                    },
                    onDragCancel = { trail = emptyList() },
                    onDrag = { change: PointerInputChange, _: Offset ->
                        change.consume()
                        trail = trail + change.position
                    },
                )
            },
    ) {
        // guide line + trail
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val y = size.height / 2f
            drawLine(
                accent.copy(alpha = 0.4f),
                Offset(0f, y), Offset(size.width.toFloat(), y),
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect
                    .dashPathEffect(floatArrayOf(10f, 8f), 0f),
            )
            if (trail.size > 1) {
                val p = androidx.compose.ui.graphics.Path()
                p.moveTo(trail[0].x, trail[0].y)
                trail.drop(1).forEach { p.lineTo(it.x, it.y) }
                drawPath(p, GOOD,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            }
        }
        result?.let { (dev, n) ->
            Text(
                "dev ${"%.1f".format(dev)}px · $n samples (lower = smoother)",
                color = if (dev < 12) GOOD else if (dev < 30) WARN else BAD,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(10.dp),
            )
        }
        if (result == null) {
            Text("Swipe along the dashed line",
                color = DIM, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.Center))
        }
    }
}

// ─────────────────────────────────────────────────────────────
// A/B COMPARE (HTSR / SuperTouch) + persisted buckets
// ─────────────────────────────────────────────────────────────
@Composable
fun TouchABCompare(
    state: BenchState,
    tileRepository: TileRepository,
    accent: Color,
) {
    val c = LocalContext.current
    val tm = tileRepository.touchModeManager
    state.bump // observe refresh
    val map = state.bucketsMap()

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Super report (${tm?.reportRateHz?.intValue ?: 0} Hz)",
                color = PanelTheme.TextPrimary,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(
                tm?.superReportEnabled?.value == true,
                { v -> tm?.toggleSuperReport(v) },
                colors = abSwitch(accent),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("A / B RESULTS  (per config, persisted)",
            color = accent, fontSize = 9.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(4.dp))
        if (map.isEmpty()) {
            Text("Run the tap test with each toggle combination to fill this table.",
                color = DIM, fontSize = 10.sp)
        } else {
            map.forEach { (k, b) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(k, color = PanelTheme.TextPrimary,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "in ${"%.1f".format(b.inMs)}  disp ${"%.1f".format(b.dispMs)}  n=${b.n}",
                        color = latColor(b.inMs),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("RESET ALL", color = BAD,
            fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { state.reset() }.padding(4.dp))
    }
}

@Composable
private fun abSwitch(accent: Color) = SwitchDefaults.colors(
    checkedThumbColor = Color.Black,
    checkedTrackColor = accent,
    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
    uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
)

// ── compact last-result chip (used inside the TOUCH tab) ──
@Composable
fun LastTapChip(state: BenchState) {
    state.bump
    val lastVal = state.tapIn.lastOrNull()
    if (lastVal != null) {
        Text(
            "last in→cb ${"%.1f".format(lastVal)}ms · ${state.tapIn.size} taps",
            color = latColor(lastVal),
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
        )
    }
}
