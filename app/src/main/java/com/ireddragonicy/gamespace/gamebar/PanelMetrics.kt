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
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)

package com.ireddragonicy.gamespace.gamebar

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Point
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.SystemProperties
import android.graphics.Rect
import android.graphics.drawable.*
import android.net.Uri
import android.os.BatteryManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.android.settingslib.display.BrightnessUtils.*
import androidx.collection.LruCache
import androidx.core.graphics.drawable.*
import androidx.compose.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.ireddragonicy.gamespace.R

import com.ireddragonicy.gamespace.gamebar.brightness.*
import com.ireddragonicy.gamespace.gamebar.fps.*
import com.ireddragonicy.gamespace.gamebar.tiles.*
import com.ireddragonicy.gamespace.settings.PerAppSettingsActivity
import com.ireddragonicy.gamespace.thermal.ThermalProfiles

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale


import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.ireddragonicy.gamespace.telemetry.TelemetryBus
import com.ireddragonicy.gamespace.telemetry.TelemetrySnapshot
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import dagger.hilt.android.EntryPointAccessors

@Composable
fun rememberTelemetryBus(): TelemetryBus {
    val ctx = LocalContext.current
    return remember(ctx.applicationContext) {
        EntryPointAccessors.fromApplication(
            ctx.applicationContext, ServiceViewEntryPoint::class.java
        ).telemetryBus()
    }
}

/** The one composable every panel widget reads. One collect, one frame, no polling. */
@Composable
fun rememberTelemetry(): TelemetrySnapshot =
    rememberTelemetryBus().snapshot.collectAsState().value

/**
 * Trip-relative colour for *silicon* (CPU/GPU). One rule, ratio-based.
 * Battery stays on its own absolute scale ([batteryTempColor]).
 */
fun heatColor(heat: Float): Color = when {
    heat <= 0f   -> Color(0xFF42A5F5)   // no reading / cool
    heat < 0.45f -> Color(0xFF42A5F5)
    heat < 0.70f -> Color(0xFF66BB6A)   // normal gaming
    heat < 0.90f -> Color(0xFFFFA726)   // heavy load
    else         -> Color(0xFFEF5350)   // at / over trip → throttling
}

@Composable
fun FpsGraph(

    modifier: Modifier = Modifier,
    interactor: FpsInteractor
) {
    val realHistory by interactor.realFpsHistory.collectAsState(initial = emptyList())
    val totalHistory by interactor.totalFpsHistory.collectAsState(initial = emptyList())
    val maxFps by interactor.dynamicMaxFps.collectAsState()
    val isAfme by interactor.isAfmeActive.collectAsState()
    val afmeMult by interactor.afmeMultiplier.collectAsState()

    val realColor = LocalPanelAccent.current   // Cyan — real game FPS (Game Turbo style)
    val afmeColor = Color(0xFF00E5FF)   // Bright cyan — total output with AFME

    // Compute stats from real FPS (game render rate)
    val realStats = remember(realHistory) {
        if (realHistory.isNotEmpty()) {
            Triple(
                realHistory.minOrNull() ?: 0f,
                realHistory.average().toFloat(),
                realHistory.maxOrNull() ?: 0f
            )
        } else Triple(0f, 0f, 0f)
    }

    // Compute stats from total FPS (display output)
    val totalStats = remember(totalHistory) {
        if (totalHistory.isNotEmpty()) {
            Triple(
                totalHistory.minOrNull() ?: 0f,
                totalHistory.average().toFloat(),
                totalHistory.maxOrNull() ?: 0f
            )
        } else Triple(0f, 0f, 0f)
    }

    // Generated FPS = total - real
    val avgGenFps = (totalStats.second - realStats.second).coerceAtLeast(0f)

    // Color based on real FPS health
    val clampedMax = maxFps.takeIf { it > 0f } ?: 60f
    val graphColor = remember(realStats, clampedMax) {
        when {
            realStats.second >= clampedMax * 0.9f -> realColor
            realStats.second >= clampedMax * 0.75f -> Color(0xFFFFA000)
            else -> Color(0xFFD32F2F)
        }
    }

    // Normalize points for graph drawing
    val realNorm = remember(realHistory, clampedMax) {
        realHistory.map { (it / clampedMax).coerceIn(0f, 1f) }
    }
    val totalNorm = remember(totalHistory, clampedMax) {
        totalHistory.map { (it / clampedMax).coerceIn(0f, 1f) }
    }

    // ROG chart: scanline grid + glow series inside a chamfered container
    val chartShape = remember { chamferShape(bigCut = 10.dp, smallCut = 4.dp) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), chartShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Legend: MIN/AVG/MAX chips (+ AFME breakdown when active)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatChip("MIN", "${realStats.first.toInt()}", graphColor)
            StatChip("AVG", "${realStats.second.toInt()}", graphColor)
            StatChip("MAX", "${realStats.third.toInt()}", graphColor)
            Spacer(modifier = Modifier.weight(1f))
            if (isAfme) {
                StatChip("+GEN", "${avgGenFps.toInt()}", afmeColor)
                StatChip("${afmeMult}×", "${totalStats.second.toInt()}", afmeColor)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isAfme) 48.dp else 40.dp)
        ) {
            drawChartFrame(graphColor)
            if (realNorm.size > 1) {
                drawSeries(
                    points = realNorm,
                    color = graphColor,
                    stepX = size.width / (realNorm.size - 1),
                )
            }
            if (isAfme && totalNorm.size > 1) {
                drawSeries(
                    points = totalNorm,
                    color = afmeColor,
                    stepX = size.width / (totalNorm.size - 1),
                    fill = false,
                    dashed = true,
                )
            }
        }
    }
}

internal fun getPercentage(value: Double, min: Float, max: Float): Double {
    return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
}

/**
 * Per-cluster CPU frequency bar graph — ROG Game Turbo style.
 * Shows each cluster as a horizontal bar: current freq vs max freq.
 * Includes a ceiling notch for thermal throttling (hwMaxKhz).
 */
@Composable
fun CpuClusterGraph(modifier: Modifier = Modifier) {
    val clusters = rememberTelemetry().cpuClusters
    val panelShape = remember { chamferShape(bigCut = 10.dp, smallCut = 4.dp) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), panelShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (clusters.isEmpty()) {
            Text(
                text = "Reading CPU clusters…",
                color = PanelTextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        } else {
            clusters.forEach { c ->
                val ratio = if (c.hwMaxKhz > 0)
                    (c.curKhz.toFloat() / c.hwMaxKhz).coerceIn(0f, 1f) else 0f
                val capRatio = if (c.hwMaxKhz > 0)
                    (c.scalingMaxKhz.toFloat() / c.hwMaxKhz).coerceIn(0f, 1f) else 1f
                val capped = c.scalingMaxKhz in 1 until c.hwMaxKhz - 1_000L
                val barColor = when {
                    ratio < 0.3f -> Color(0xFF66BB6A)
                    ratio < 0.6f -> LocalPanelAccent.current
                    ratio < 0.85f -> Color(0xFFFFA726)
                    else -> Color(0xFFEF5350)
                }
                val animatedRatio by animateFloatAsState(
                    targetValue = ratio,
                    animationSpec = tween(300),
                    label = "cl_${c.name}",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${c.name}×${c.cpuCount}",
                        color = PanelTextSecondary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(40.dp),
                    )
                    Canvas(Modifier.weight(1f).height(10.dp)) {
                        val w = size.width
                        val h = size.height
                        val cr = CornerRadius(2.dp.toPx())
                        drawRoundRect(Color.White.copy(alpha = 0.06f), cornerRadius = cr)
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(barColor.copy(alpha = 0.55f), barColor)
                            ),
                            size = Size(w * animatedRatio, h),
                            cornerRadius = cr,
                        )
                        if (capped) {
                            drawLine(
                                PanelTextSecondary.copy(alpha = 0.8f),
                                Offset(w * capRatio, 0f),
                                Offset(w * capRatio, h),
                                1.5.dp.toPx(),
                            )
                        }
                    }
                    Text(
                        text = "${c.curKhz / 1000}M",
                        color = barColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(38.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}


/**
 * GPU Load Graph — rolling sparkline chart showing GPU usage over time.
 * History is collected at parent level (hoisted above AnimatedVisibility)
 * so it persists across show/hide — same pattern as FPS graph.
 */
@Composable
fun GpuLoadGraph(
    modifier: Modifier = Modifier,
    gpuUsage: Int,
    gpuFreqMhz: Int,
    gpuTempC: Float,
    gpuHeat: Float,
    history: List<Int>,
) {
    val maxSamples = 30
    val usageColor = when {
        gpuUsage > 80 -> Color(0xFFEF5350)
        gpuUsage > 50 -> Color(0xFFFFA726)
        else -> LocalPanelAccent.current
    }
    val gpuPanelShape = remember { chamferShape(bigCut = 10.dp, smallCut = 4.dp) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), gpuPanelShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("GPU", color = PanelTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            Text("$gpuUsage%", color = usageColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$gpuFreqMhz MHz", color = LocalPanelAccent.current.copy(alpha = 0.7f), fontSize = 9.sp)
            Spacer(Modifier.width(8.dp))
            val gpuTColor by animateColorAsState(heatColor(gpuHeat), tween(500), "gpu_graph_tc")
            Text(
                "%.1f°C".format(gpuTempC),
                color = gpuTColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (history.size >= 2) {
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                drawChartFrame(usageColor)
                val stepX = size.width / (maxSamples - 1).toFloat()
                drawSeries(
                    points = history.map { it / 100f },
                    color = usageColor,
                    stepX = stepX,
                    startX = (maxSamples - history.size) * stepX,
                )
            }
        } else {
            Box(
                Modifier.fillMaxWidth().height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Collecting GPU data…", color = PanelTextSecondary, fontSize = 9.sp)
            }
        }
    }
}


// ── Usage Dot Bar (vertical segmented indicator) ──

/**
 * Tiny vertical bar with 5 dot segments that light up based on usage percentage.
 * Color: cyan (<50%) -> orange (50-80%) -> red (>80%).
 * Pure Canvas drawing — zero overhead, no coroutines, no state.
 */
@Composable
fun UsageDotBar(
    percentage: Int,
    accentColor: Color = LocalPanelAccent.current,
    modifier: Modifier = Modifier,
) {
    val segments = 5
    val litCount = ((percentage / 100f) * segments).toInt().coerceIn(0, segments)

    Canvas(
        modifier = modifier
            .width(4.dp)
            .height(18.dp)
    ) {
        val segmentHeight = size.height / segments
        val dotHeight = segmentHeight * 0.55f
        val gap = segmentHeight - dotHeight
        val dotWidth = size.width

        for (i in 0 until segments) {
            // Draw top-to-bottom: i=0 is top (highest segment)
            val segIndex = segments - 1 - i  // segIndex 4=top, 0=bottom
            val isLit = (segments - i) <= litCount  // bottom segments light first
            val y = i * segmentHeight + gap / 2f

            val segColor = if (isLit) {
                when {
                    segIndex >= 4 -> Color(0xFFEF5350)         // Red — top (80-100%)
                    segIndex >= 3 -> Color(0xFFFFA726)         // Orange — (60-80%)
                    else -> accentColor.copy(alpha = 0.85f)    // Cyan — (0-60%)
                }
            } else {
                Color.White.copy(alpha = 0.08f)
            }

            drawRoundRect(
                color = segColor,
                topLeft = Offset(0f, y),
                size = Size(dotWidth, dotHeight),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/**
 * Tall vertical usage gauge — 8 segments spanning full height between columns.
 * Fills the gap ("rongga") between CPU↔FPS and FPS↔GPU.
 * Bottom segments light up first. Color: cyan → orange → red.
 * Pure Canvas drawing — zero overhead.
 */
@Composable
fun VerticalUsageGauge(
    percentage: Int,
    accentColor: Color = LocalPanelAccent.current,
    modifier: Modifier = Modifier,
) {
    val segments = 8
    val litCount = ((percentage / 100f) * segments).toInt().coerceIn(0, segments)

    val animatedLit by animateFloatAsState(
        targetValue = (percentage / 100f) * segments,
        animationSpec = tween(400),
        label = "gauge_anim"
    )

    Canvas(
        modifier = modifier
            .width(6.dp)
            .height(70.dp)
    ) {
        val totalHeight = size.height
        val dotWidth = size.width
        val segmentHeight = totalHeight / segments
        val dotHeight = segmentHeight * 0.6f
        val gap = segmentHeight - dotHeight
        val cornerR = CornerRadius(2.dp.toPx())

        for (i in 0 until segments) {
            // i=0 is top (highest), i=7 is bottom (lowest)
            val segIndex = segments - 1 - i  // segIndex 7=top, 0=bottom
            val isLit = (segments - i) <= litCount  // bottom lights first
            val y = i * segmentHeight + gap / 2f

            val segColor = if (isLit) {
                when {
                    segIndex >= 7 -> Color(0xFFEF5350)             // Red — top (87-100%)
                    segIndex >= 6 -> Color(0xFFFF7043)             // Deep orange (75-87%)
                    segIndex >= 5 -> Color(0xFFFFA726)             // Orange (62-75%)
                    segIndex >= 4 -> Color(0xFFFFCA28)             // Amber (50-62%)
                    else -> accentColor.copy(alpha = 0.85f)        // Cyan (0-50%)
                }
            } else {
                Color.White.copy(alpha = 0.06f)
            }

            drawRoundRect(
                color = segColor,
                topLeft = Offset(0f, y),
                size = Size(dotWidth, dotHeight),
                cornerRadius = cornerR,
            )
        }
    }
}

/** Battery temperature colors — absolute thresholds */
fun batteryTempColor(tempC: Float): Color = when {
    tempC < 30f -> Color(0xFF42A5F5)   // Blue — cool
    tempC < 37f -> Color(0xFF66BB6A)   // Green — normal
    tempC < 42f -> Color(0xFFFFA726)   // Orange — warm (battery warning)
    else -> Color(0xFFEF5350)          // Red — hot (battery danger)
}

/** SoC temperature colors — trip-relative thresholds */
fun socTempColor(tempC: Float, maxTempC: Float = 100f): Color = when {
    tempC < maxTempC - 30f -> Color(0xFF42A5F5)   // Blue — cool/idle
    tempC < maxTempC - 15f -> Color(0xFF66BB6A)   // Green — normal gaming
    tempC < maxTempC - 5f -> Color(0xFFFFA726)    // Orange — heavy load
    else -> Color(0xFFEF5350)                     // Red — throttling zone
}

/** Compat shim for old calls */
fun cpuTempColor(tempC: Float) = socTempColor(tempC)
fun gpuTempColor(tempC: Float) = socTempColor(tempC)



// ══════════════════════════════════════════════════════════════════
// Feature: Frametime Graph with 1% / 0.1% Low
// ══════════════════════════════════════════════════════════════════

@Composable
fun FrametimeGraph(
    modifier: Modifier = Modifier,
    interactor: FpsInteractor
) {
    val realHistory by interactor.realFpsHistory.collectAsState(initial = emptyList())
    val maxFps by interactor.dynamicMaxFps.collectAsState()
    val accentColor = MaterialTheme.colorScheme.primary

    // Convert FPS → frametime (ms)
    val frametimes = remember(realHistory) {
        realHistory.filter { it > 0f }.map { 1000f / it }
    }

    // Compute percentile stats
    val ftStats = remember(frametimes) {
        if (frametimes.size >= 3) {
            val sorted = frametimes.sorted()
            val avg = frametimes.average().toFloat()
            val p99Idx = ((sorted.size - 1) * 0.99f).toInt().coerceIn(0, sorted.lastIndex)
            val p999Idx = ((sorted.size - 1) * 0.999f).toInt().coerceIn(0, sorted.lastIndex)
            FrametimeStats(
                avg = avg,
                min = sorted.first(),
                max = sorted.last(),
                p1Low = sorted[p99Idx],    // 1% low = P99 frametime (slowest 1%)
                p01Low = sorted[p999Idx],  // 0.1% low = P99.9 frametime
            )
        } else FrametimeStats()
    }

    // Normalize frametimes for graph: use dynamic range 0..maxFrametime
    val maxFt = remember(frametimes) {
        (frametimes.maxOrNull() ?: 33f).coerceAtLeast(16.7f)
    }
    val normalizedFt = remember(frametimes, maxFt) {
        frametimes.map { (it / maxFt).coerceIn(0f, 1f) }
    }

    // Color based on average frametime health
    val targetFt = if (maxFps > 0f) 1000f / maxFps else 16.7f
    val graphColor = remember(ftStats, targetFt) {
        when {
            ftStats.avg <= targetFt * 1.1f -> accentColor
            ftStats.avg <= targetFt * 1.5f -> Color(0xFFFFA000)
            else -> Color(0xFFD32F2F)
        }
    }

    // ROG chart: frametime series (high = bad = top) over the tactical grid,
    // with the target-frametime rail and percentile-low legend chips.
    val ftShape = remember { chamferShape(bigCut = 10.dp, smallCut = 4.dp) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), ftShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Single compact stats row + slim single-line FT target label (top-right).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "AVG %.1fms · 1%% %.1fms · 0.1%% %.1fms".format(
                    ftStats.avg, ftStats.p1Low, ftStats.p01Low
                ),
                color = graphColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "FT · target %.1f ms".format(targetFt),
                color = accentColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            drawChartFrame(graphColor)

            // Target frametime rail (dashed)
            val targetY = size.height - ((targetFt / maxFt).coerceIn(0f, 1f) * size.height)
            drawLine(
                color = accentColor.copy(alpha = 0.35f),
                start = Offset(0f, targetY),
                end = Offset(size.width, targetY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
            )

            if (normalizedFt.size > 1) {
                drawSeries(
                    points = normalizedFt,
                    color = graphColor,
                    stepX = size.width / (normalizedFt.size - 1),
                )
            }
        }
    }
}

private data class FrametimeStats(
    val avg: Float = 0f,
    val min: Float = 0f,
    val max: Float = 0f,
    val p1Low: Float = 0f,
    val p01Low: Float = 0f,
)

