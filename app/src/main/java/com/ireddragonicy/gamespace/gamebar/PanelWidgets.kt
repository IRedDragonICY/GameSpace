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
package com.ireddragonicy.gamespace.gamebar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Neon chrome overlay: edge stroke + soft glow + top accent rail and corner
 * blade details. Draw ABOVE the content box (borders only touch edges).
 */
@Composable
fun PanelChromeOverlay(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val big = minOf(18.dp.toPx(), size.minDimension / 4f)
        val small = minOf(7.dp.toPx(), size.minDimension / 8f)

        fun edgePath(inset: Float) = Path().apply {
            moveTo(small + inset, inset)
            lineTo(w - big, inset)
            lineTo(w - inset, big)
            lineTo(w - inset, h - small - inset)
            lineTo(w - small - inset, h - inset)
            lineTo(big, h - inset)
            lineTo(inset, h - big)
            lineTo(inset, small + inset)
            close()
        }

        // Outer glow (two cheap strokes, no blur shader)
        drawPath(edgePath(0f), accent.copy(alpha = 0.10f), style = Stroke(width = 7f))
        drawPath(edgePath(0f), accent.copy(alpha = 0.22f), style = Stroke(width = 3.5f))
        // Crisp edge
        drawPath(edgePath(1f), accent.copy(alpha = 0.65f), style = Stroke(width = 1.6f))

        // Top accent rail — the "energized" line under the chamfer
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(accent.copy(alpha = 0.0f), accent, accent.copy(alpha = 0.0f))
            ),
            start = Offset(w * 0.12f, 2.5f),
            end = Offset(w * 0.62f, 2.5f),
            strokeWidth = 2.5f,
        )

        // Corner blades (bottom-start chamfer detail)
        drawLine(
            color = accent.copy(alpha = 0.85f),
            start = Offset(4f, h - big + 6f),
            end = Offset(big - 6f, h - 4f),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = accent.copy(alpha = 0.35f),
            start = Offset(12f, h - big + 12f),
            end = Offset(big, h - 12f),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
    }
}

/** Shared gauge-ring painter: 270° track + glowing sweep + tick segments. */
private fun DrawScope.drawGaugeRing(
    accent: Color,
    ratio: Float,
    trackAlpha: Float = 0.10f,
    strokePx: Float,
) {
    val inset = strokePx * 1.6f
    val arcSize = size.minDimension - inset * 2f
    val topLeft = Offset((size.width - arcSize) / 2f, (size.height - arcSize) / 2f)
    val sweep = ratio.coerceIn(0f, 1f) * 270f

    // Track
    drawArc(
        color = Color.White.copy(alpha = trackAlpha),
        startAngle = 135f, sweepAngle = 270f, useCenter = false,
        topLeft = topLeft, size = Size(arcSize, arcSize),
        style = Stroke(width = strokePx, cap = StrokeCap.Butt),
    )
    // Glow + core sweep
    drawArc(
        color = accent.copy(alpha = 0.22f),
        startAngle = 135f, sweepAngle = sweep, useCenter = false,
        topLeft = topLeft, size = Size(arcSize, arcSize),
        style = Stroke(width = strokePx * 2.4f, cap = StrokeCap.Round),
    )
    drawArc(
        color = accent,
        startAngle = 135f, sweepAngle = sweep, useCenter = false,
        topLeft = topLeft, size = Size(arcSize, arcSize),
        style = Stroke(width = strokePx, cap = StrokeCap.Round),
    )

    // Segment ticks every 27° (10 segments) — the Armoury Crate signature
    val center = Offset(size.width / 2f, size.height / 2f)
    val rOut = arcSize / 2f + inset * 0.72f
    val rIn = arcSize / 2f + inset * 0.30f
    for (i in 0..10) {
        val angleDeg = 135f + i * 27f
        val rad = Math.toRadians(angleDeg.toDouble())
        val dirX = cos(rad).toFloat()
        val dirY = sin(rad).toFloat()
        val lit = sweep >= i * 27f - 0.5f && ratio > 0f
        drawLine(
            color = if (lit) accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.14f),
            start = Offset(center.x + dirX * rIn, center.y + dirY * rIn),
            end = Offset(center.x + dirX * rOut, center.y + dirY * rOut),
            strokeWidth = if (lit) 2.4f else 1.6f,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Modular telemetry cell — ring gauge + value, with label above and up to two
 * detail lines (frequency, temperature) below. Used for CPU / GPU / any
 * percentage metric.
 */
@Composable
fun TelemetryCell(
    label: String,
    valueText: String,
    ratio: Float,
    accent: Color,
    detail1: String? = null,
    detail2: String? = null,
    detail2Color: Color = PanelTheme.TextDim,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val animatedRatio by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        animationSpec = tween(400), label = "rog_cell_$label",
    )
    val bg by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = tween(200), label = "rog_cell_bg_$label",
    )

    val cellShape = remember { chamferShape(bigCut = 10.dp, smallCut = 4.dp) }
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(bg, cellShape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent.copy(alpha = 0.85f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
            Canvas(modifier = Modifier.size(46.dp)) {
                drawGaugeRing(accent = accent, ratio = animatedRatio, strokePx = 3.5f)
            }
            Text(
                text = valueText,
                color = PanelTheme.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 34.dp),
            )
        }
        detail1?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.7f),
                fontSize = 8.sp,
            )
        }
        detail2?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = detail2Color,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// ROG chart engine — shared by every telemetry graph in the overlay.
// One Canvas pass per chart: scanline grid, glow series, endpoint pulse.
// ════════════════════════════════════════════════════════════════════════

/** Tactical grid: horizontal scanlines + faint time ticks + energized baseline. */
fun DrawScope.drawChartFrame(accent: Color) {
    listOf(0.25f, 0.5f, 0.75f).forEach { pct ->
        val y = size.height * (1f - pct)
        drawLine(
            color = Color.White.copy(alpha = 0.06f),
            start = Offset(0f, y), end = Offset(size.width, y),
            strokeWidth = 1f,
        )
    }
    for (i in 1..5) {
        val x = size.width * i / 6f
        drawLine(
            color = Color.White.copy(alpha = 0.04f),
            start = Offset(x, 0f), end = Offset(x, size.height),
            strokeWidth = 1f,
        )
    }
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(accent.copy(alpha = 0.0f), accent.copy(alpha = 0.45f), accent.copy(alpha = 0.0f))
        ),
        start = Offset(0f, size.height - 1f),
        end = Offset(size.width, size.height - 1f),
        strokeWidth = 2f,
    )
}

/** Smooth bezier path through normalized [0..1] points (1 = top). */
private fun seriesPath(
    points: List<Float>,
    stepX: Float,
    height: Float,
    startX: Float,
    closed: Boolean,
): Path = Path().apply {
    val x0 = startX
    val y0 = height - points[0] * height
    if (closed) {
        moveTo(x0, height); lineTo(x0, y0)
    } else {
        moveTo(x0, y0)
    }
    for (i in 1 until points.size) {
        val cx = startX + i * stepX
        val cy = height - points[i] * height
        if (i == 1) {
            lineTo(cx, cy)
        } else {
            val px = startX + (i - 1) * stepX
            val py = height - points[i - 1] * height
            quadraticTo((px + cx) / 2f, (py + cy) / 2f, cx, cy)
        }
    }
    if (closed) {
        lineTo(startX + (points.size - 1) * stepX, height)
        close()
    }
}

/**
 * Draw one data series: gradient area fill, neon glow line, endpoint pulse.
 * [points] are normalized 0..1; series is right-aligned when startX > 0.
 */
fun DrawScope.drawSeries(
    points: List<Float>,
    color: Color,
    stepX: Float,
    startX: Float = 0f,
    fill: Boolean = true,
    dashed: Boolean = false,
    endDot: Boolean = true,
) {
    if (points.size < 2) return
    val h = size.height

    if (fill) {
        drawPath(
            path = seriesPath(points, stepX, h, startX, closed = true),
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f)),
                startY = 0f, endY = h,
            ),
        )
    }

    val line = seriesPath(points, stepX, h, startX, closed = false)
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 5f), 0f) else null
    // Glow underlay + crisp core
    drawPath(
        path = line, color = color.copy(alpha = 0.25f),
        style = Stroke(width = 6.dp.toPx() / 2f, cap = StrokeCap.Round,
            join = StrokeJoin.Round, pathEffect = effect),
    )
    drawPath(
        path = line, color = color,
        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round,
            join = StrokeJoin.Round, pathEffect = effect),
    )

    if (endDot) {
        val ex = startX + (points.size - 1) * stepX
        val ey = h - points.last() * h
        drawCircle(color.copy(alpha = 0.30f), radius = 5.dp.toPx() / 2f, center = Offset(ex, ey))
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(ex, ey))
    }
}

/** Compact ROG stat chip — "MIN 59", "AVG 60" — for chart legends. */
@Composable
fun StatChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val chipShape = remember { chamferShape(bigCut = 5.dp, smallCut = 2.dp) }
    Text(
        text = "$label $value",
        color = color,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), chipShape)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * Center FPS core — the hero gauge. Larger segmented ring, color shifts with
 * frame-rate health, glowing sweep, big numeral.
 */
@Composable
fun FpsGauge(
    fps: Int,
    maxFps: Float,
    labelText: String,
    throttled: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clampedMax = maxFps.takeIf { it > 0f } ?: 60f
    val ratio = (fps / clampedMax).coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(
        targetValue = ratio, animationSpec = tween(300), label = "rog_fps_ratio",
    )
    val ringColor by animateColorAsState(
        targetValue = when {
            fps >= clampedMax * 0.7f -> accent
            fps >= clampedMax * 0.4f -> PanelTheme.Warn
            else -> PanelTheme.Danger
        },
        animationSpec = tween(400), label = "rog_fps_color",
    )

    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(66.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Canvas(modifier = Modifier.size(66.dp)) {
            drawGaugeRing(accent = ringColor, ratio = animatedRatio, strokePx = 5f)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$fps",
                color = PanelTheme.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 22.sp,
            )
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelSmall,
                color = ringColor.copy(alpha = 0.9f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            if (throttled) {
                Text(
                    text = "THROTTLED",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelTheme.Danger,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}
