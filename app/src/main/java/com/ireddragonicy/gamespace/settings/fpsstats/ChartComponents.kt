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

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlin.math.*

// ── Color Palette (matching Scene app + gaming aesthetic) ──
val ChartWhite = Color(0xFFE0E0E0)
val ChartCyan = Color(0xFF00E5FF)
val ChartMagenta = Color(0xFFE040FB)
val ChartGreen = Color(0xFF00E676)
val ChartOrange = Color(0xFFFF9100)
val ChartYellow = Color(0xFFFFEA00)
val ChartRed = Color(0xFFFF5252)
val ChartBlue = Color(0xFF448AFF)
val ChartPurple = Color(0xFF7C4DFF)
val ChartCardBg = Color(0xFF1A1B2E)
val ChartCardBorder = Color(0xFF2A2B3E)
val ChartGridLine = Color(0xFF2A2B3E)
val ChartTextDim = Color(0xFF808090)

val ClusterColors = listOf(ChartMagenta, ChartCyan, ChartGreen, ChartOrange)

// ── Data classes for chart input ──

data class ChartLine(
    val values: List<Float>,
    val color: Color,
    val label: String,
    val dashed: Boolean = false,
)

// ── Chart Card Wrapper ──

@Composable
fun ChartCard(
    title: String,
    rightTitle: String? = null,
    rightTitleColor: Color = ChartCyan,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ChartCardBg, RoundedCornerShape(12.dp))
            .border(0.5.dp, ChartCardBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = ChartTextDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (rightTitle != null) {
                Text(
                    text = rightTitle,
                    color = rightTitleColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

// ── Multi-Line Time Series Chart ──

@Composable
fun TimeSeriesChart(
    lines: List<ChartLine>,
    durationSec: Int,
    yMin: Float = 0f,
    yMax: Float = 100f,
    ySteps: Int = 5,
    rightAxisLines: List<ChartLine> = emptyList(),
    rightYMin: Float = 0f,
    rightYMax: Float = 100f,
    height: Dp = 180.dp,
    showLegend: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val leftMargin = 40.dp.toPx()
            val rightMargin = if (rightAxisLines.isNotEmpty()) 40.dp.toPx() else 16.dp.toPx()
            val topMargin = 8.dp.toPx()
            val bottomMargin = 24.dp.toPx()

            val chartWidth = size.width - leftMargin - rightMargin
            val chartHeight = size.height - topMargin - bottomMargin

            // Grid lines (horizontal)
            val yRange = yMax - yMin
            for (i in 0..ySteps) {
                val y = topMargin + chartHeight * (1f - i.toFloat() / ySteps)
                val value = yMin + yRange * i / ySteps

                // Dashed grid line
                drawLine(
                    color = ChartGridLine,
                    start = Offset(leftMargin, y),
                    end = Offset(leftMargin + chartWidth, y),
                    strokeWidth = 0.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )

                // Left Y-axis label
                val label = if (yMax >= 1000) "${value.toInt()}" else "%.0f".format(value)
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = Offset(2.dp.toPx(), y - 6.dp.toPx()),
                    style = TextStyle(
                        color = ChartTextDim,
                        fontSize = 9.sp,
                    ),
                )
            }

            // Right Y-axis labels (if dual-axis)
            if (rightAxisLines.isNotEmpty()) {
                val rRange = rightYMax - rightYMin
                for (i in 0..ySteps) {
                    val y = topMargin + chartHeight * (1f - i.toFloat() / ySteps)
                    val value = rightYMin + rRange * i / ySteps
                    val label = "%.0f".format(value)
                    drawText(
                        textMeasurer = textMeasurer,
                        text = label,
                        topLeft = Offset(leftMargin + chartWidth + 4.dp.toPx(), y - 6.dp.toPx()),
                        style = TextStyle(
                            color = ChartTextDim,
                            fontSize = 9.sp,
                        ),
                    )
                }
            }

            // Time axis labels
            val timeSteps = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
            for (frac in timeSteps) {
                val x = leftMargin + chartWidth * frac
                val timeSec = (durationSec * frac).toInt()
                val label = "${timeSec}s"
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    topLeft = Offset(x - 8.dp.toPx(), size.height - 14.dp.toPx()),
                    style = TextStyle(
                        color = ChartTextDim,
                        fontSize = 9.sp,
                    ),
                )
                // Vertical grid line
                drawLine(
                    color = ChartGridLine,
                    start = Offset(x, topMargin),
                    end = Offset(x, topMargin + chartHeight),
                    strokeWidth = 0.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )
            }

            // Draw left-axis lines
            for (line in lines) {
                drawChartLine(line, leftMargin, topMargin, chartWidth, chartHeight, yMin, yMax)
            }

            // Draw right-axis lines
            for (line in rightAxisLines) {
                drawChartLine(line, leftMargin, topMargin, chartWidth, chartHeight, rightYMin, rightYMax)
            }
        }

        // Legend
        if (showLegend) {
            Spacer(modifier = Modifier.height(6.dp))
            val allLines = lines + rightAxisLines
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                allLines.forEach { line ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(line.color, RoundedCornerShape(2.dp)),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = line.label,
                            color = line.color,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawChartLine(
    line: ChartLine,
    leftMargin: Float,
    topMargin: Float,
    chartWidth: Float,
    chartHeight: Float,
    yMin: Float,
    yMax: Float,
) {
    if (line.values.size < 2) return

    val path = Path()
    val yRange = (yMax - yMin).coerceAtLeast(0.01f)

    for (i in line.values.indices) {
        val x = leftMargin + chartWidth * i / (line.values.size - 1).coerceAtLeast(1)
        val normalized = ((line.values[i] - yMin) / yRange).coerceIn(0f, 1f)
        val y = topMargin + chartHeight * (1f - normalized)

        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    val pathEffect = if (line.dashed) {
        PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
    } else null

    drawPath(
        path = path,
        color = line.color,
        style = Stroke(
            width = 1.5f,
            pathEffect = pathEffect,
        ),
    )
}

// ── Bar Chart (for Jank / Frame Time Histogram) ──

@Composable
fun BarChart(
    values: List<Float>,
    durationSec: Int,
    yMax: Float = 0f,
    barColor: Color = ChartCyan,
    highlightColor: Color = ChartRed,
    highlightThreshold: Float = Float.MAX_VALUE,
    height: Dp = 140.dp,
    bottomLabel: String = "",
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val actualYMax = if (yMax > 0f) yMax else (values.maxOrNull() ?: 1f) * 1.2f

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val leftMargin = 32.dp.toPx()
            val rightMargin = 16.dp.toPx()
            val topMargin = 8.dp.toPx()
            val bottomMargin = 24.dp.toPx()

            val chartWidth = size.width - leftMargin - rightMargin
            val chartHeight = size.height - topMargin - bottomMargin

            if (values.isEmpty()) return@Canvas

            val barWidth = (chartWidth / values.size) * 0.7f
            val barSpacing = chartWidth / values.size

            // Grid + Y labels
            for (i in 0..4) {
                val y = topMargin + chartHeight * (1f - i / 4f)
                val value = actualYMax * i / 4f
                drawLine(
                    color = ChartGridLine,
                    start = Offset(leftMargin, y),
                    end = Offset(leftMargin + chartWidth, y),
                    strokeWidth = 0.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = "%.0f".format(value),
                    topLeft = Offset(2.dp.toPx(), y - 6.dp.toPx()),
                    style = TextStyle(color = ChartTextDim, fontSize = 9.sp),
                )
            }

            // Bars
            for (i in values.indices) {
                val x = leftMargin + barSpacing * i + (barSpacing - barWidth) / 2
                val barHeight = (values[i] / actualYMax).coerceIn(0f, 1f) * chartHeight
                val y = topMargin + chartHeight - barHeight
                val color = if (values[i] > highlightThreshold) highlightColor else barColor

                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(barWidth.coerceAtLeast(2f), barHeight.coerceAtLeast(1f)),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }

            // Time labels
            val timeSteps = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
            for (frac in timeSteps) {
                val x = leftMargin + chartWidth * frac
                val timeSec = (durationSec * frac).toInt()
                drawText(
                    textMeasurer = textMeasurer,
                    text = "${timeSec}s",
                    topLeft = Offset(x - 8.dp.toPx(), size.height - 14.dp.toPx()),
                    style = TextStyle(color = ChartTextDim, fontSize = 9.sp),
                )
            }
        }

        if (bottomLabel.isNotEmpty()) {
            Text(
                text = bottomLabel,
                color = ChartTextDim,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

// ── Stats Summary Card ──

@Composable
fun StatsSummaryCard(
    stats: List<Pair<String, Pair<String, Color>>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        stats.forEach { (label, valuePair) ->
            val (value, color) = valuePair
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    color = ChartTextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    color = color,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = label.substringAfterLast(" ").uppercase(),
                    color = ChartTextDim,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

// ── Thread Stats Mini Sparkline ──

@Composable
fun ThreadStatsRow(
    threadName: String,
    tid: Int,
    avgPercent: Float,
    maxPercent: Float,
    history: List<Float>,
    color: Color = ChartCyan,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = threadName,
                    color = ChartWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$tid",
                    color = ChartTextDim,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = "AVG:%.1f%%  MAX:%.1f%%".format(avgPercent, maxPercent),
                color = ChartTextDim,
                fontSize = 11.sp,
            )
        }

        // Mini sparkline
        if (history.size > 1) {
            Spacer(modifier = Modifier.height(2.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
            ) {
                val maxVal = history.max().coerceAtLeast(1f)
                val path = Path()

                for (i in history.indices) {
                    val x = size.width * i / (history.size - 1).coerceAtLeast(1)
                    val y = size.height * (1f - (history[i] / maxVal).coerceIn(0f, 1f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(path, color, style = Stroke(width = 1.5f))

                // Fill under line
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fillPath, color.copy(alpha = 0.1f))
            }
        }
    }
}

// ── Device Info Card (Platform / Model / OS / Profile) ──

@Composable
fun DeviceInfoCard(
    platform: String,
    model: String,
    osVersion: String,
    profile: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ChartCardBg, RoundedCornerShape(12.dp))
            .border(0.5.dp, ChartCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        DeviceInfoItem("Platform", platform)
        DeviceInfoItem("Model", model)
        DeviceInfoItem("OS", osVersion)
        DeviceInfoItem("Profile", profile)
    }
}

@Composable
private fun DeviceInfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = ChartTextDim,
            fontSize = 10.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = ChartWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Frame Time Histogram ──

@Composable
fun FrameTimeHistogram(
    frameTimes: List<Float>,
    targetFps: Float = 60f,
    height: Dp = 160.dp,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val targetFrameTimeMs = 1000f / targetFps

    // Build histogram buckets
    val bucketBoundaries = listOf(8f, 16f, 25f, 33f, 41f, 50f, 58f, 66f, 75f, 83f, 91f, 100f, 150f, 200f, 250f)
    val buckets = IntArray(bucketBoundaries.size + 1)

    for (ft in frameTimes) {
        val idx = bucketBoundaries.indexOfFirst { ft < it }
        if (idx >= 0) buckets[idx]++ else buckets[buckets.size - 1]++
    }

    val maxCount = buckets.max().coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val leftMargin = 32.dp.toPx()
            val rightMargin = 16.dp.toPx()
            val topMargin = 8.dp.toPx()
            val bottomMargin = 24.dp.toPx()

            val chartWidth = size.width - leftMargin - rightMargin
            val chartHeight = size.height - topMargin - bottomMargin
            val barWidth = (chartWidth / buckets.size) * 0.75f
            val barSpacing = chartWidth / buckets.size

            // Y-axis grid
            for (i in 0..4) {
                val y = topMargin + chartHeight * (1f - i / 4f)
                val value = maxCount * i / 4
                drawLine(
                    color = ChartGridLine,
                    start = Offset(leftMargin, y),
                    end = Offset(leftMargin + chartWidth, y),
                    strokeWidth = 0.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = "$value",
                    topLeft = Offset(2.dp.toPx(), y - 6.dp.toPx()),
                    style = TextStyle(color = ChartTextDim, fontSize = 9.sp),
                )
            }

            // Bars
            for (i in buckets.indices) {
                val x = leftMargin + barSpacing * i + (barSpacing - barWidth) / 2
                val barHeight = (buckets[i].toFloat() / maxCount) * chartHeight
                val y = topMargin + chartHeight - barHeight

                // Color: cyan for normal, red for > 2× target, orange for > target
                val color = when {
                    i < bucketBoundaries.size && bucketBoundaries[i] > targetFrameTimeMs * 2 -> ChartRed
                    i < bucketBoundaries.size && bucketBoundaries[i] > targetFrameTimeMs -> ChartOrange
                    else -> ChartCyan
                }

                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(barWidth.coerceAtLeast(2f), barHeight.coerceAtLeast(1f)),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }

            // X labels (frame time ms)
            val labelIndices = listOf(0, 3, 6, 9, 12)
            for (idx in labelIndices) {
                if (idx < bucketBoundaries.size) {
                    val x = leftMargin + barSpacing * idx
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "${bucketBoundaries[idx].toInt()}ms",
                        topLeft = Offset(x - 4.dp.toPx(), size.height - 14.dp.toPx()),
                        style = TextStyle(color = ChartTextDim, fontSize = 8.sp),
                    )
                }
            }
        }

        Text(
            text = "MAX: %.0fms".format(frameTimes.maxOrNull() ?: 0f),
            color = ChartTextDim,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
