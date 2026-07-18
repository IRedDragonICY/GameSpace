/*
 * Copyright (C) 2025 AxionOS
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

// ── Per-Cluster CPU Graph (dynamic — reads all policy dirs) ──

data class CpuClusterInfo(
    val policyName: String,     // e.g. "policy0"
    val cpuIds: List<Int>,      // e.g. [0, 1]
    val currentKhz: Long,
    val maxKhz: Long,
    val coreType: String,       // e.g. "A520", "A725", "X925"
)

/**
 * Dynamically reads all CPU frequency scaling policies from sysfs.
 * Returns per-cluster frequency info with core type labels.
 * SM8735 (sun): policy0=A520×2, policy2=A725×3, policy5=A725×2, policy7=X925×1
 */
@Composable
fun rememberCpuClusters(): List<CpuClusterInfo> {
    val clusters = remember { mutableStateOf<List<CpuClusterInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val cpufreqDir = java.io.File("/sys/devices/system/cpu/cpufreq/")
                val policies = cpufreqDir.listFiles()?.filter {
                    it.isDirectory && it.name.startsWith("policy")
                }?.sortedBy {
                    it.name.removePrefix("policy").toIntOrNull() ?: 0
                } ?: emptyList()

                clusters.value = policies.map { policyDir ->
                    val cpuIds = java.io.File(policyDir, "affected_cpus")
                        .readText().trim().split(" ")
                        .mapNotNull { it.toIntOrNull() }
                    val curKhz = java.io.File(policyDir, "scaling_cur_freq")
                        .readText().trim().toLongOrNull() ?: 0L
                    val maxKhz = java.io.File(policyDir, "cpuinfo_max_freq")
                        .readText().trim().toLongOrNull() ?: 1L

                    // Map cluster to SoC core type based on max freq
                    val coreType = when {
                        maxKhz > 3100000 -> "X925"      // Prime core >3.1GHz
                        maxKhz > 2700000 -> "A725"      // Performance >2.7GHz
                        maxKhz > 2500000 -> "A725"      // Performance >2.5GHz
                        else -> "A520"                    // Efficiency ≤2.5GHz
                    }

                    CpuClusterInfo(
                        policyName = policyDir.name,
                        cpuIds = cpuIds,
                        currentKhz = curKhz,
                        maxKhz = maxKhz,
                        coreType = coreType,
                    )
                }
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(1000L)
        }
    }

    return clusters.value
}

/**
 * Per-cluster CPU frequency bar graph — ROG Game Turbo style.
 * Shows each cluster as a horizontal bar: current freq vs max freq.
 * Color: green (low), cyan (medium), orange (high), red (maxed out).
 */
@Composable
fun CpuClusterGraph(modifier: Modifier = Modifier) {
    val clusters = rememberCpuClusters()
    val panelShape = remember { chamferShape(bigCut = 10.dp, smallCut = 4.dp) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), panelShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (clusters.isEmpty()) {
            Text(
                text = "Reading CPU clusters...",
                color = PanelTextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        } else {
            clusters.forEach { cluster ->
                val ratio = if (cluster.maxKhz > 0)
                    (cluster.currentKhz.toFloat() / cluster.maxKhz).coerceIn(0f, 1f)
                else 0f

                val barColor = when {
                    ratio < 0.3f -> Color(0xFF66BB6A)   // Green — idle
                    ratio < 0.6f -> LocalPanelAccent.current        // Cyan — moderate
                    ratio < 0.85f -> Color(0xFFFFA726)   // Orange — high
                    else -> Color(0xFFEF5350)            // Red — maxed
                }

                val animatedRatio by animateFloatAsState(
                    targetValue = ratio,
                    animationSpec = tween(300),
                    label = "cluster_${cluster.policyName}"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Cluster label: "A520×2"
                    Text(
                        text = "${cluster.coreType}×${cluster.cpuIds.size}",
                        color = PanelTextSecondary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(40.dp),
                    )

                    // Frequency blade bar — chamfered, glow-tipped
                    val bladeShape = remember { chamferShape(bigCut = 4.dp, smallCut = 2.dp) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(bladeShape)
                            .background(Color.White.copy(alpha = 0.06f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedRatio)
                                .clip(bladeShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(barColor.copy(alpha = 0.55f), barColor)
                                    )
                                )
                        )
                    }

                    // Current freq label
                    val freqMhz = cluster.currentKhz / 1000
                    Text(
                        text = "${freqMhz}M",
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
    gpuUsage: MutableState<Int>,
    gpuFreq: MutableState<String>,
    gpuTemp: MutableState<Float>,
    history: List<Int>,
) {
    val maxSamples = 30

    val usageColor = when {
        gpuUsage.value > 80 -> Color(0xFFEF5350)
        gpuUsage.value > 50 -> Color(0xFFFFA726)
        else -> LocalPanelAccent.current
    }

    val gpuPanelShape = remember { chamferShape(bigCut = 10.dp, smallCut = 4.dp) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), gpuPanelShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header: GPU xx% | 685 MHz | 58.5°C
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "GPU",
                color = PanelTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${gpuUsage.value}%",
                color = usageColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = gpuFreq.value,
                color = LocalPanelAccent.current.copy(alpha = 0.7f),
                fontSize = 9.sp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            val gpuTColor by animateColorAsState(
                targetValue = gpuTempColor(gpuTemp.value),
                animationSpec = tween(500), label = "gpu_graph_tc"
            )
            Text(
                text = "%.1f°C".format(gpuTemp.value),
                color = gpuTColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // ROG chart: right-aligned rolling series over the tactical grid
        if (history.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
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
            // Loading state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Collecting GPU data...",
                    color = PanelTextSecondary,
                    fontSize = 9.sp,
                )
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

// ── Temperature color helper (dynamic: cool → hot) ──

/**
 * Returns dynamic color based on temperature:
 * - Blue  (<35°C): cool/idle
 * - Green (35-42°C): normal/stable
 * - Orange (42-50°C): warm/gaming
 * - Red   (>50°C): hot/throttling
 */
@Deprecated("Use batteryTempColor/cpuTempColor/gpuTempColor for accurate thresholds")
fun tempColor(tempC: Float): Color = when {
    tempC < 35f -> Color(0xFF42A5F5)
    tempC < 42f -> Color(0xFF66BB6A)
    tempC < 50f -> Color(0xFFFFA726)
    else -> Color(0xFFEF5350)
}

/** Battery temperature colors — stricter thresholds (batteries are fragile) */
fun batteryTempColor(tempC: Float): Color = when {
    tempC < 30f -> Color(0xFF42A5F5)   // Blue — cool
    tempC < 37f -> Color(0xFF66BB6A)   // Green — normal
    tempC < 42f -> Color(0xFFFFA726)   // Orange — warm (battery warning)
    else -> Color(0xFFEF5350)          // Red — hot (battery danger)
}

/** CPU temperature colors — higher thresholds (SoC runs hotter) */
fun cpuTempColor(tempC: Float): Color = when {
    tempC < 45f -> Color(0xFF42A5F5)   // Blue — cool/idle
    tempC < 60f -> Color(0xFF66BB6A)   // Green — normal gaming
    tempC < 75f -> Color(0xFFFFA726)   // Orange — heavy load
    else -> Color(0xFFEF5350)          // Red — throttling zone
}

/** GPU temperature colors — even higher thresholds (GPU runs hottest) */
fun gpuTempColor(tempC: Float): Color = when {
    tempC < 50f -> Color(0xFF42A5F5)   // Blue — cool/idle
    tempC < 65f -> Color(0xFF66BB6A)   // Green — normal gaming
    tempC < 78f -> Color(0xFFFFA726)   // Orange — heavy load
    else -> Color(0xFFEF5350)          // Red — throttling zone
}

// ── Sysfs Temperature Reader (for CPU/GPU thermal zones) ──

/**
 * Reads temperature from a sysfs thermal zone file.
 * Thermal zones report in millidegrees Celsius (e.g., 49900 = 49.9°C).
 * Updates every 2 seconds.
 */
@Composable
fun rememberSysfsTemp(sysfsPath: String): MutableState<Float> {
    val temp = remember { mutableStateOf(0f) }

    LaunchedEffect(sysfsPath) {
        while (true) {
            try {
                val raw = java.io.File(sysfsPath).readText().trim().toLongOrNull() ?: 0L
                temp.value = raw / 1000f
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(2000L)
        }
    }

    return temp
}

// ── CPU Usage Monitor (Game Turbo style) ──
@Composable
fun rememberCpuUsage(): MutableState<Int> {
    val cpuUsage = remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        var prevTotal = 0L
        var prevIdle = 0L

        // Do initial read immediately to seed prev values
        try {
            val lines = java.io.File("/proc/stat").readLines()
            val cpuLine = lines.firstOrNull { it.startsWith("cpu ") }
            if (cpuLine != null) {
                val parts = cpuLine.split("\\s+".toRegex()).drop(1).map { it.toLong() }
                if (parts.size >= 4) {
                    prevIdle = parts[3]
                    prevTotal = parts.sum()
                }
            }
        } catch (_: Exception) {}
        delay(500) // Short initial delay

        while (isActive) {
            try {
                val lines = java.io.File("/proc/stat").readLines()
                val cpuLine = lines.firstOrNull { it.startsWith("cpu ") }
                if (cpuLine != null) {
                    val parts = cpuLine.split("\\s+".toRegex()).drop(1).map { it.toLong() }
                    if (parts.size >= 4) {
                        val idle = parts[3]
                        val total = parts.sum()
                        val diffTotal = total - prevTotal
                        val diffIdle = idle - prevIdle
                        if (diffTotal > 0) {
                            cpuUsage.value = ((diffTotal - diffIdle) * 100 / diffTotal).toInt()
                                .coerceIn(0, 100)
                        }
                        prevTotal = total
                        prevIdle = idle
                    }
                }
            } catch (_: Exception) {}
            delay(1000)
        }
    }

    return cpuUsage
}

// ── GPU Usage Monitor (Adreno kgsl sysfs) ──
@Composable
fun rememberGpuUsage(): MutableState<Int> {
    val gpuUsage = remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        // Detect which GPU sysfs path works
        val kgslPath = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
        val devfreqBase = "/sys/class/devfreq/"
        var gpuPath: String? = null
        var useDevfreq = false

        // Try kgsl first (Adreno)
        if (java.io.File(kgslPath).exists()) {
            gpuPath = kgslPath
        } else {
            // Fallback: find devfreq GPU directory
            try {
                val devfreqDir = java.io.File(devfreqBase)
                val gpuDir = devfreqDir.listFiles()?.firstOrNull {
                    it.name.contains("gpu") || it.name.contains("3d0") || it.name.contains("kgsl")
                }
                if (gpuDir != null) {
                    val loadFile = java.io.File(gpuDir, "load")
                    if (loadFile.exists()) {
                        gpuPath = loadFile.absolutePath
                        useDevfreq = true
                    }
                }
            } catch (_: Exception) {}
        }

        while (isActive) {
            try {
                if (gpuPath != null) {
                    val text = java.io.File(gpuPath).readText().trim()
                    if (useDevfreq) {
                        // devfreq format: "busy_time total_time"
                        val parts = text.split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            val busy = parts[0].toLongOrNull() ?: 0
                            val total = parts[1].toLongOrNull() ?: 1
                            gpuUsage.value = ((busy * 100) / total.coerceAtLeast(1)).toInt()
                                .coerceIn(0, 100)
                        }
                    } else {
                        // kgsl format: "xx %" or "xx"
                        val pct = text.replace("%", "").trim().split("\\s+".toRegex())[0].toIntOrNull()
                        if (pct != null) {
                            gpuUsage.value = pct.coerceIn(0, 100)
                        }
                    }
                } else {
                    // Last resort: try reading via Runtime (bypasses direct Java file SELinux)
                    val process = Runtime.getRuntime().exec(arrayOf("cat", kgslPath))
                    val result = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()
                    if (result.isNotEmpty()) {
                        val pct = result.replace("%", "").trim().split("\\s+".toRegex())[0].toIntOrNull()
                        if (pct != null) {
                            gpuUsage.value = pct.coerceIn(0, 100)
                            gpuPath = kgslPath // Cache successful path
                        }
                    }
                }
            } catch (_: Exception) {}
            delay(1000)
        }
    }

    return gpuUsage
}

// ── CPU Frequency Monitor (highest active core) ──
@Composable
fun rememberCpuFrequency(): MutableState<String> {
    val cpuFreq = remember { mutableStateOf("-- GHz") }

    LaunchedEffect(Unit) {
        // Find all CPU frequency paths
        val cpuDir = java.io.File("/sys/devices/system/cpu/")
        val cpuFreqPaths = try {
            cpuDir.listFiles()
                ?.filter { it.name.matches(Regex("cpu\\d+")) }
                ?.mapNotNull { dir ->
                    val freqFile = java.io.File(dir, "cpufreq/scaling_cur_freq")
                    if (freqFile.exists()) freqFile else null
                }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }

        while (isActive) {
            try {
                var maxFreqKhz = 0L
                for (path in cpuFreqPaths) {
                    val khz = path.readText().trim().toLongOrNull() ?: 0L
                    if (khz > maxFreqKhz) maxFreqKhz = khz
                }
                if (maxFreqKhz > 0) {
                    val ghz = maxFreqKhz / 1_000_000.0
                    cpuFreq.value = if (ghz >= 1.0) {
                        String.format("%.1f GHz", ghz)
                    } else {
                        "${maxFreqKhz / 1000} MHz"
                    }
                }
            } catch (_: Exception) {}
            delay(1000)
        }
    }

    return cpuFreq
}

// ── GPU Frequency Monitor (Adreno kgsl) — multi-sample peak ──
@Composable
fun rememberGpuFrequency(): MutableState<String> {
    val gpuFreq = remember { mutableStateOf("-- MHz") }

    LaunchedEffect(Unit) {
        // GPU clock paths for Adreno
        val gpuClkPath = "/sys/class/kgsl/kgsl-3d0/gpuclk"
        val devfreqPath = "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"

        // Read initial value immediately (no 1s wait)
        try {
            val hz = java.io.File(gpuClkPath).readText().trim().toLongOrNull()
                ?: java.io.File(devfreqPath).readText().trim().toLongOrNull()
                ?: 0L
            if (hz > 0) gpuFreq.value = "${hz / 1_000_000} MHz"
        } catch (_: Exception) {}

        // Then sample continuously
        while (isActive) {
            try {
                // Sample 5 times over 1 second to catch peak freq
                // (msm-adreno-tz governor fluctuates rapidly between frames)
                val samples = mutableListOf<Long>()
                repeat(5) {
                    val hz = java.io.File(gpuClkPath).readText().trim().toLongOrNull()
                        ?: java.io.File(devfreqPath).readText().trim().toLongOrNull()
                        ?: 0L
                    if (hz > 0) samples.add(hz)
                    delay(200)
                }
                if (samples.isNotEmpty()) {
                    val peakHz = samples.max()
                    val mhz = peakHz / 1_000_000
                    gpuFreq.value = "$mhz MHz"
                }
            } catch (_: Exception) {
                delay(1000)
            }
        }
    }

    return gpuFreq
}

// ══════════════════════════════════════════════════════════════════
// Feature: RAM Usage Monitor
// ══════════════════════════════════════════════════════════════════

data class RamInfo(
    val totalGb: Float = 0f,
    val usedGb: Float = 0f,
    val usagePercent: Float = 0f,
    val swapTotalGb: Float = 0f,
    val swapUsedGb: Float = 0f,
    val swapPercent: Float = 0f,
)

/** Parse a "Key:  12345 kB" line from /proc/meminfo into GiB. */
private fun meminfoGb(lines: Map<String, Long>, key: String): Float =
    (lines[key] ?: 0L) / 1_048_576f

@Composable
fun rememberRamUsage(): RamInfo {
    val context = LocalContext.current
    var ramInfo by remember { mutableStateOf(RamInfo()) }

    LaunchedEffect(Unit) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        while (true) {
            try {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val totalGb = memInfo.totalMem / 1_073_741_824f
                val availGb = memInfo.availMem / 1_073_741_824f
                val usedGb = totalGb - availGb

                // Swap/zram detail from /proc/meminfo (values in kB)
                val meminfo = runCatching {
                    java.io.File("/proc/meminfo").readLines()
                        .mapNotNull { line ->
                            val parts = line.split(':')
                            if (parts.size == 2) {
                                val kb = parts[1].trim().removeSuffix(" kB").toLongOrNull()
                                kb?.let { parts[0] to it }
                            } else null
                        }.toMap()
                }.getOrDefault(emptyMap())
                val swapTotal = meminfoGb(meminfo, "SwapTotal")
                val swapFree = meminfoGb(meminfo, "SwapFree")
                val swapUsed = (swapTotal - swapFree).coerceAtLeast(0f)

                ramInfo = RamInfo(
                    totalGb = totalGb,
                    usedGb = usedGb,
                    usagePercent = (usedGb / totalGb * 100f).coerceIn(0f, 100f),
                    swapTotalGb = swapTotal,
                    swapUsedGb = swapUsed,
                    swapPercent = if (swapTotal > 0f)
                        (swapUsed / swapTotal * 100f).coerceIn(0f, 100f) else 0f,
                )
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(3000L)
        }
    }

    return ramInfo
}

// ══════════════════════════════════════════════════════════════════
// Feature: Thermal Throttle Indicator
// ══════════════════════════════════════════════════════════════════

/** Returns true when CPU is thermally throttled (current freq < 90% of max freq). */
@Composable
fun rememberCpuThrottle(): State<Boolean> {
    val throttled = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                // Read current freq of big core (cpu7 on SM8735)
                val curFreq = java.io.File("/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq")
                    .readText().trim().toLongOrNull() ?: 0L
                val maxFreq = java.io.File("/sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq")
                    .readText().trim().toLongOrNull() ?: 0L
                throttled.value = maxFreq > 0 && curFreq < (maxFreq * 0.85)
            } catch (_: Exception) {
                throttled.value = false
            }
            kotlinx.coroutines.delay(2000L)
        }
    }

    return throttled
}

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

