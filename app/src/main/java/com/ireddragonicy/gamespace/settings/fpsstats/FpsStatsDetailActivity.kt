/*
 * Copyright (C) 2026 IRedDragonICY
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ireddragonicy.gamespace.settings.fpsstats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import dagger.hilt.android.AndroidEntryPoint
import com.ireddragonicy.gamespace.data.fpsstats.FpsStatsRepository
import com.ireddragonicy.gamespace.data.fpsstats.FpsStatsSession
import com.ireddragonicy.gamespace.data.fpsstats.ThreadSnapshot
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint(ComponentActivity::class)
class FpsStatsDetailActivity : Hilt_FpsStatsDetailActivity() {

    @Inject lateinit var repository: FpsStatsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionId = intent.getStringExtra("session_id") ?: run {
            finish()
            return
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0D0E1A),
                    surface = ChartCardBg,
                    primary = ChartCyan,
                ),
            ) {
                val session = remember { repository.loadSession(sessionId) }
                if (session != null) {
                    FpsStatsDetailScreen(
                        session = session,
                        onBack = { finish() },
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Session not found", color = ChartWhite)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsStatsDetailScreen(
    session: FpsStatsSession,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        containerColor = Color(0xFF0D0E1A),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        session.appName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D0E1A),
                    titleContentColor = ChartWhite,
                    navigationIconContentColor = ChartWhite,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Device Info ──
            DeviceInfoCard(
                platform = session.platform,
                model = session.model,
                osVersion = session.osVersion,
                profile = session.thermalProfile.take(8),
            )

            // ── Session Info ──
            Text(
                text = "${dateFormat.format(Date(session.startTimeMs))}   ${session.packageName}",
                color = ChartTextDim,
                fontSize = 11.sp,
            )

            // ── Summary Stats (Row 1): FPS ──
            ChartCard(title = "") {
                StatsSummaryCard(
                    stats = listOf(
                        "MAX" to ("%.1f".format(session.summary.fpsMax) to ChartCyan),
                        "MIN" to ("%.1f".format(session.summary.fpsMin) to ChartOrange),
                        "AVG" to ("%.1f".format(session.summary.fpsAvg) to ChartCyan),
                        "VARIANCE" to ("%.1f".format(session.summary.fpsVariance) to ChartMagenta),
                    ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                StatsSummaryCard(
                    stats = listOf(
                        ">=45FPS" to Pair("%.1f%%".format(session.summary.smoothnessPercent), ChartGreen),
                        "5% Low" to Pair(
                            if (session.summary.fpsLow5Percent > 0) "%.1f".format(session.summary.fpsLow5Percent) else "--",
                            ChartTextDim
                        ),
                        "MAX Temp" to Pair("%.1f".format(session.summary.tempMaxC), ChartOrange),
                        "AVG Power" to Pair("%.2f".format(session.summary.powerAvgW), ChartCyan),
                    ),
                )
            }

            // ── Chart 1: FPS + Temp + CPU% + GPU% (multi-line) ──
            ChartCard(
                title = "FPS",
                rightTitle = "Temperature(C)",
            ) {
                val fpsValues = session.samples.map { it.fps }
                val tempValues = session.samples.map { it.cpuTempC }
                val cpuValues = session.samples.map { it.cpuUsageTotal }
                val gpuValues = session.samples.map { it.gpuLoadPercent }

                val fpsMax = (fpsValues.maxOrNull() ?: 60f) * 1.1f

                TimeSeriesChart(
                    lines = listOf(
                        ChartLine(fpsValues, ChartWhite, "FPS"),
                        ChartLine(cpuValues, ChartMagenta, "CPU(%)"),
                        ChartLine(gpuValues, ChartBlue, "GPU(%)"),
                    ),
                    rightAxisLines = listOf(
                        ChartLine(tempValues, ChartOrange, "TEMP(C)", dashed = true),
                    ),
                    durationSec = session.durationSec,
                    yMin = 0f,
                    yMax = fpsMax.coerceAtLeast(65f),
                    rightYMin = tempValues.minOrNull()?.minus(5f) ?: 20f,
                    rightYMax = tempValues.maxOrNull()?.plus(5f) ?: 60f,
                    height = 200.dp,
                )
            }

            // ── Chart 2: JANK ──
            val frameTimes = session.samples.map { it.frameTimeMs }
            val targetFrameTime = 1000f / 60f  // ~16.67ms
            val jankValues = frameTimes.map { if (it > targetFrameTime * 2) 1f else 0f }

            if (frameTimes.isNotEmpty()) {
                ChartCard(title = "JANK") {
                    BarChart(
                        values = jankValues,
                        durationSec = session.durationSec,
                        yMax = 3f,
                        barColor = ChartCyan,
                        highlightColor = ChartRed,
                        highlightThreshold = 0.5f,
                        height = 100.dp,
                        bottomLabel = "JANK: ${session.summary.jankCount}    BIG JANK: ${session.summary.bigJankCount}",
                    )
                }
            }

            // ── Chart 3: Frame Time Histogram ──
            if (frameTimes.isNotEmpty()) {
                ChartCard(title = "Frame Time(ms)") {
                    FrameTimeHistogram(
                        frameTimes = frameTimes,
                        targetFps = 60f,
                        height = 160.dp,
                    )
                }
            }

            // ── Chart 4: CPU Usage (%) per cluster ──
            ChartCard(
                title = "CPU Usage(%)",
                rightTitle = "Chart Options",
                rightTitleColor = ChartBlue,
            ) {
                val totalCpu = session.samples.map { it.cpuUsageTotal }
                val cluster0 = session.samples.map { it.cpuUsagePerCluster.getOrNull(0) ?: 0f }
                val cluster1 = session.samples.map { it.cpuUsagePerCluster.getOrNull(1) ?: 0f }
                val cluster2 = session.samples.map { it.cpuUsagePerCluster.getOrNull(2) ?: 0f }
                val cluster3 = session.samples.map { it.cpuUsagePerCluster.getOrNull(3) ?: 0f }

                TimeSeriesChart(
                    lines = listOf(
                        ChartLine(totalCpu, ChartBlue, "Total"),
                        ChartLine(cluster0, ChartMagenta, "CPU 0~1"),
                        ChartLine(cluster1, ChartCyan, "CPU 2~4"),
                        ChartLine(cluster2, ChartGreen, "CPU 5~6"),
                        ChartLine(cluster3, ChartOrange, "CPU 7"),
                    ),
                    durationSec = session.durationSec,
                    yMin = 0f,
                    yMax = 100f,
                    height = 200.dp,
                )
            }

            // ── Chart 5: CPU Frequency (MHz) per cluster ──
            ChartCard(
                title = "CPU Frequency(MHz)",
                rightTitle = "Chart Options",
                rightTitleColor = ChartBlue,
            ) {
                val freq0 = session.samples.map { (it.cpuFreqPerCluster.getOrNull(0) ?: 0).toFloat() }
                val freq1 = session.samples.map { (it.cpuFreqPerCluster.getOrNull(1) ?: 0).toFloat() }
                val freq2 = session.samples.map { (it.cpuFreqPerCluster.getOrNull(2) ?: 0).toFloat() }
                val freq3 = session.samples.map { (it.cpuFreqPerCluster.getOrNull(3) ?: 0).toFloat() }

                val allFreqs = freq0 + freq1 + freq2 + freq3
                val maxFreq = (allFreqs.maxOrNull() ?: 2100f) * 1.1f

                TimeSeriesChart(
                    lines = listOf(
                        ChartLine(freq0, ChartMagenta, "CPU 0~1"),
                        ChartLine(freq1, ChartCyan, "CPU 2~4"),
                        ChartLine(freq2, ChartGreen, "CPU 5~6"),
                        ChartLine(freq3, ChartOrange, "CPU 7"),
                    ),
                    durationSec = session.durationSec,
                    yMin = 0f,
                    yMax = maxFreq.coerceAtLeast(2200f),
                    ySteps = 6,
                    height = 200.dp,
                )
            }

            // ── Chart 6: GPU Frequency + Usage (dual-axis) ──
            ChartCard(
                title = "GPU Frequency(MHz)",
                rightTitle = "Usage(%)",
                rightTitleColor = ChartCyan,
            ) {
                val gpuFreq = session.samples.map { it.gpuFreqMHz.toFloat() }
                val gpuLoad = session.samples.map { it.gpuLoadPercent }
                val maxGpuFreq = (gpuFreq.maxOrNull() ?: 1162f) * 1.1f

                TimeSeriesChart(
                    lines = listOf(
                        ChartLine(gpuFreq, ChartBlue, "Frequency(MHz)"),
                    ),
                    rightAxisLines = listOf(
                        ChartLine(gpuLoad, ChartCyan, "Usage(%)"),
                    ),
                    durationSec = session.durationSec,
                    yMin = 0f,
                    yMax = maxGpuFreq.coerceAtLeast(1200f),
                    rightYMin = 0f,
                    rightYMax = 100f,
                    height = 200.dp,
                )
            }

            // ── Chart 7: Power (W) + Capacity (%) ──
            ChartCard(
                title = "Power(W)",
                rightTitle = "Capacity %",
            ) {
                val power = session.samples.map { it.batteryPowerW }
                val capacity = session.samples.map { it.batteryCapacity.toFloat() }
                val maxPower = (power.maxOrNull() ?: 5f) * 1.2f

                TimeSeriesChart(
                    lines = listOf(
                        ChartLine(power, ChartCyan, "Power(W)"),
                    ),
                    rightAxisLines = listOf(
                        ChartLine(capacity, ChartBlue, "Capacity(%)"),
                    ),
                    durationSec = session.durationSec,
                    yMin = 0f,
                    yMax = maxPower.coerceAtLeast(6f),
                    rightYMin = 0f,
                    rightYMax = 100f,
                    height = 180.dp,
                )

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text(
                        "MAX: %.2fW".format(session.summary.powerMaxW),
                        color = ChartTextDim, fontSize = 11.sp,
                    )
                    Text(
                        "MIN: %.2fW".format(session.summary.powerMinW),
                        color = ChartTextDim, fontSize = 11.sp,
                    )
                    Text(
                        "AVG: %.2fW".format(session.summary.powerAvgW),
                        color = ChartTextDim, fontSize = 11.sp,
                    )
                }
            }

            // ── Chart 8: CPU Temperature ──
            ChartCard(title = "CPU Temperature(C)") {
                val cpuTemp = session.samples.map { it.cpuTempC }
                val maxTemp = (cpuTemp.maxOrNull() ?: 60f) + 5f
                val minTemp = (cpuTemp.minOrNull() ?: 30f) - 5f

                TimeSeriesChart(
                    lines = listOf(
                        ChartLine(cpuTemp, ChartCyan, "CPU Temp"),
                    ),
                    durationSec = session.durationSec,
                    yMin = minTemp.coerceAtLeast(0f),
                    yMax = maxTemp,
                    height = 160.dp,
                )

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text(
                        "MAX: %.1fC".format(session.summary.tempMaxC),
                        color = ChartTextDim, fontSize = 11.sp,
                    )
                    Text(
                        "MIN: %.1fC".format(session.summary.tempMinC),
                        color = ChartTextDim, fontSize = 11.sp,
                    )
                    Text(
                        "AVG: %.1fC".format(session.summary.tempAvgC),
                        color = ChartTextDim, fontSize = 11.sp,
                    )
                }
            }

            // ── Chart 9: GPU Temperature ──
            ChartCard(title = "GPU Temperature(C)") {
                val gpuTemp = session.samples.map { it.gpuTempC }
                val maxTemp = (gpuTemp.maxOrNull() ?: 60f) + 5f
                val minTemp = (gpuTemp.minOrNull() ?: 30f) - 5f

                TimeSeriesChart(
                    lines = listOf(
                        ChartLine(gpuTemp, ChartGreen, "GPU Temp"),
                    ),
                    durationSec = session.durationSec,
                    yMin = minTemp.coerceAtLeast(0f),
                    yMax = maxTemp,
                    height = 140.dp,
                    showLegend = false,
                )
            }

            // ── Chart 10: Thread Statistics ──
            if (session.threadSnapshots.isNotEmpty()) {
                ChartCard(title = "Thread statistics") {
                    // Aggregate thread data across all snapshots
                    val threadAggregates = aggregateThreadData(session.threadSnapshots)

                    // Show top 15 threads by average CPU usage
                    threadAggregates
                        .sortedByDescending { it.avgPercent }
                        .take(15)
                        .forEach { threadData ->
                            ThreadStatsRow(
                                threadName = threadData.name,
                                tid = threadData.tid,
                                avgPercent = threadData.avgPercent,
                                maxPercent = threadData.maxPercent,
                                history = threadData.history,
                                color = when {
                                    threadData.avgPercent > 30f -> ChartGreen
                                    threadData.avgPercent > 10f -> ChartCyan
                                    else -> ChartBlue
                                },
                            )
                            if (threadData != threadAggregates.sortedByDescending { it.avgPercent }.take(15).last()) {
                                HorizontalDivider(
                                    color = ChartCardBorder,
                                    thickness = 0.5.dp,
                                )
                            }
                        }
                }
            }

            // Bottom spacer
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

// ── Thread data aggregation ──

private data class AggregatedThread(
    val tid: Int,
    val name: String,
    val avgPercent: Float,
    val maxPercent: Float,
    val history: List<Float>,
)

private fun aggregateThreadData(snapshots: List<ThreadSnapshot>): List<AggregatedThread> {
    // Single-pass aggregation using HashMap — O(snapshots * threadsPerSnapshot)
    // Instead of O(uniqueTids * snapshots * threadsPerSnapshot)
    data class Accumulator(
        var name: String = "",
        val percentages: MutableList<Float> = mutableListOf(),
        val history: MutableList<Float> = mutableListOf(),
    )

    val accumulators = HashMap<Int, Accumulator>(64) // Pre-sized for typical thread count

    for (snapshot in snapshots) {
        // Track which TIDs appeared in this snapshot
        val seenTids = HashSet<Int>(snapshot.threads.size)

        for (thread in snapshot.threads) {
            seenTids.add(thread.tid)
            val acc = accumulators.getOrPut(thread.tid) { Accumulator() }
            acc.name = thread.name
            acc.percentages.add(thread.cpuPercent)
            acc.history.add(thread.cpuPercent)
        }

        // Fill 0 for TIDs not seen in this snapshot (needed for sparkline continuity)
        for ((tid, acc) in accumulators) {
            if (tid !in seenTids) {
                acc.history.add(0f)
            }
        }
    }

    return accumulators.map { (tid, acc) ->
        AggregatedThread(
            tid = tid,
            name = acc.name,
            avgPercent = if (acc.percentages.isNotEmpty()) acc.percentages.average().toFloat() else 0f,
            maxPercent = acc.percentages.maxOrNull() ?: 0f,
            history = acc.history,
        )
    }
}
