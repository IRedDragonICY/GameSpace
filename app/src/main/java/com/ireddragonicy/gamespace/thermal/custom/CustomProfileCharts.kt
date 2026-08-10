/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.thermal.custom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * GRAPHICAL "ESTIMATED RESULT".
 *
 * Replaces the old plain-text cluster descriptions with a real thermal
 * dashboard, drawn in flat Material You (theme roles only — no neon palette):
 *
 *   [ stat tiles : ONSET / PEAK / DEEPEST CUT / STEPS ]   [ BEHAVIOR chip ]
 *   [ hero chart : X = temperature, Y = % of hw-max ]
 *       CPU = tonal envelope (solid primary = worst case, faint = best case)
 *       GPU = secondary dashed line
 *   [ legend ]
 *   [ per-cluster + GPU step bars — temperature axis ALIGNED with the hero ]
 */

// Shared horizontal inset for EVERY canvas (hero + bars) -> temperature aligns.
private const val PAD_L_DP = 16f // room for the hero's right-aligned % labels
private const val PAD_R_DP = 8f
private const val SAMPLES = 96

private data class CCurve(val hw: Long, val sorted: List<ThrottleLevel>)

/** Qualitative read of a curve — drives the BEHAVIOR chip. */
private fun behaviorLabel(peakC: Float?, hasAny: Boolean): String = when {
    !hasAny || peakC == null -> "UNRESTRICTED"
    peakC >= 47f -> "EMERGENCY ONLY"
    peakC >= 44f -> "PERFORMANCE"
    peakC >= 41f -> "BALANCED"
    else -> "COOL & QUIET"
}

@Composable
fun ThrottleMapCard(vm: CustomProfileEditorViewModel) {
    val scheme = MaterialTheme.colorScheme
    val clusters = vm.clusters
    val gpu = vm.perfTuner.gpuInfo

    // Not ready yet (hardware tables still probing on IO) -> keep the old hint.
    if (!vm.hardwareReady) {
        GroupCard {
            Text(
                "Reading kernel tables...",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        return
    }

    // -- build normalized curves (freq / hw-max) --------------------------
    // Indexed by daemon slot, so the bar under "Prime" really is Prime's curve.
    val cpuCurves = clusters.map { c ->
        CCurve(
            hw = c.hwMaxKhz.coerceAtLeast(1L),
            sorted = vm.draft.cpuAt(c.slot).sortedBy { it.trigMc },
        )
    }
    val gpuHwMhz = (gpu.hwMaxHz / 1_000_000L).coerceAtLeast(1L)
    val gpuSorted = vm.draft.gpu.sortedBy { it.trigMc }
    val gpuTablePresent = gpu.availableHz.isNotEmpty()

    val hasAny = cpuCurves.any { it.sorted.isNotEmpty() } || gpuSorted.isNotEmpty()

    // value-at-temperature helpers (step functions, 1f = unrestricted)
    fun cpuAt(c: CCurve, tC: Float): Float {
        if (c.sorted.isEmpty()) return 1f
        var v = 1f
        val tMc = (tC * 1000f).toLong()
        for (l in c.sorted) if (tMc >= l.trigMc) v = (l.freq.toFloat() / c.hw).coerceIn(0f, 1f)
        return v
    }
    fun gpuAt(tC: Float): Float {
        if (gpuSorted.isEmpty()) return 1f
        var v = 1f
        val tMc = (tC * 1000f).toLong()
        for (l in gpuSorted) if (tMc >= l.trigMc) v = (l.freq.toFloat() / gpuHwMhz).coerceIn(0f, 1f)
        return v
    }

    // -- global temperature window + markers (curve-accurate, never guessed) --
    val trigC = (cpuCurves.flatMap { it.sorted } + gpuSorted).map { it.trigMc / 1000f }
    val onsetC = trigC.minOrNull()
    val peakC = trigC.maxOrNull()
    val xMin = ((onsetC ?: 35f) - 4f).coerceAtLeast(25f)
    val xMax = ((peakC ?: 49f) + 4f).coerceAtMost(60f).coerceAtLeast(xMin + 6f)
    val ticks = remember(xMin, xMax) {
        (0 until 5).map { (xMin + (xMax - xMin) * it / 4f).roundToInt() }.distinct()
    }

    // -- sampled envelope (CPU only) --------------------------------------
    val xs = remember(xMin, xMax) {
        List(SAMPLES) { xMin + (xMax - xMin) * it / (SAMPLES - 1) }
    }
    val minEnv = remember(xs, cpuCurves) {
        xs.map { t -> cpuCurves.map { c -> cpuAt(c, t) }.minOrNull() ?: 1f }
    }
    val maxEnv = remember(xs, cpuCurves) {
        xs.map { t -> cpuCurves.map { c -> cpuAt(c, t) }.maxOrNull() ?: 1f }
    }
    val gpuEnv = remember(xs, gpuSorted) { xs.map { gpuAt(it) } }

    // -- per-cluster / gpu floor stats (for tiles + bar captions) ---------
    data class FloorStat(val pct: Int, val freqText: String, val fullText: String)
    fun cpuFloor(c: CCurve): FloorStat {
        val full = formatKhz(c.hw)
        return if (c.sorted.isEmpty()) FloorStat(100, full, full)
        else {
            val f = c.sorted.last().freq
            FloorStat((f * 100 / c.hw).toInt().coerceIn(0, 100), formatKhz(f), full)
        }
    }
    val cpuFloors = cpuCurves.map { cpuFloor(it) }
    val gpuFloor = if (gpuSorted.isEmpty()) FloorStat(100, formatMhz(gpuHwMhz), formatMhz(gpuHwMhz))
    else {
        val f = gpuSorted.last().freq
        FloorStat((f * 100 / gpuHwMhz).toInt().coerceIn(0, 100), formatMhz(f), formatMhz(gpuHwMhz))
    }
    val floors = cpuFloors.filter { it.pct < 100 } +
        if (gpuSorted.isNotEmpty()) listOf(gpuFloor) else emptyList()
    val deepest = floors.minOfOrNull { it.pct }
    val totalSteps = cpuCurves.sumOf { it.sorted.size } + gpuSorted.size

    val behavior = behaviorLabel(peakC, hasAny)
    val deepColor = if (deepest != null && deepest < 35) scheme.error else scheme.primary

    GroupCard {
        // -- header: reading guide + behavior chip --
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "How the frequency ceiling falls as the chip heats up.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(scheme.primaryContainer, RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(
                    text = behavior,
                    color = scheme.onPrimaryContainer,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // -- summary stat tiles --
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile("ONSET", onsetC?.let { "%.0f°".format(it) } ?: "—", scheme.primary)
            StatTile("PEAK", peakC?.let { "%.0f°".format(it) } ?: "—", scheme.primary)
            StatTile("FLOOR", deepest?.let { "$it%" } ?: "—", deepColor)
            StatTile("STEPS", if (hasAny) "$totalSteps" else "0", scheme.secondary)
        }

        Spacer(Modifier.height(12.dp))

        // -- hero chart --
        ThrottleHeroChart(
            xMin = xMin, xMax = xMax, ticks = ticks,
            onsetC = onsetC, peakC = peakC,
            minEnv = minEnv, maxEnv = maxEnv,
            gpuEnv = gpuEnv, hasGpu = gpuSorted.isNotEmpty(), hasAny = hasAny,
        )

        Spacer(Modifier.height(6.dp))

        // -- legend --
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(scheme.primary, dashed = false)
            Spacer(Modifier.width(4.dp))
            Text("CPU range", color = scheme.onSurfaceVariant, fontSize = 9.sp)
            if (gpuSorted.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Swatch(scheme.secondary, dashed = true)
                Spacer(Modifier.width(4.dp))
                Text("GPU", color = scheme.onSurfaceVariant, fontSize = 9.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "% of hardware max",
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 8.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(8.dp))

        // -- aligned per-cluster + GPU step bars --
        if (!hasAny) {
            Text(
                "No throttle levels yet — generate or import a curve to preview it here.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            clusters.forEachIndexed { i, c ->
                val fl = cpuFloors.getOrElse(i) { FloorStat(100, formatKhz(c.hwMaxKhz), formatKhz(c.hwMaxKhz)) }
                val right = if (fl.pct >= 100) "full ${fl.fullText}"
                else "floor ${fl.freqText} · ${fl.pct}%"
                ClusterStepBar(
                    name = c.name, rightText = right,
                    levels = cpuCurves.getOrElse(i) { CCurve(1, emptyList()) }.sorted,
                    hwNorm = c.hwMaxKhz.coerceAtLeast(1L).toFloat(),
                    color = scheme.primary,
                    xMin = xMin, xMax = xMax, onsetC = onsetC, peakC = peakC,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (gpuTablePresent) {
                val right = if (gpuFloor.pct >= 100) "full ${gpuFloor.fullText}"
                else "floor ${gpuFloor.freqText} · ${gpuFloor.pct}%"
                ClusterStepBar(
                    name = "GPU", rightText = right,
                    levels = gpuSorted, hwNorm = gpuHwMhz.toFloat(),
                    color = scheme.secondary,
                    xMin = xMin, xMax = xMax, onsetC = onsetC, peakC = peakC,
                )
            }
        }
    }
}

// -- stat tile -------------------------------------------------------------
@Composable
private fun RowScope.StatTile(label: String, value: String, valueColor: Color) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .background(scheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
    ) {
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            label, color = scheme.onSurfaceVariant,
            fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        )
    }
}

// -- legend swatch (solid / dashed) ----------------------------------------
@Composable
private fun Swatch(color: Color, dashed: Boolean) {
    Canvas(Modifier.width(12.dp).height(4.dp)) {
        if (dashed) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = size.height, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2f), 0f),
            )
        } else drawRect(color)
    }
}

// -- hero chart ------------------------------------------------------------
@Composable
private fun ThrottleHeroChart(
    xMin: Float, xMax: Float, ticks: List<Int>,
    onsetC: Float?, peakC: Float?,
    minEnv: List<Float>, maxEnv: List<Float>,
    gpuEnv: List<Float>, hasGpu: Boolean, hasAny: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val secondary = scheme.secondary
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(
        color = scheme.onSurfaceVariant, fontSize = 8.sp,
    )
    val markerStyle = androidx.compose.ui.text.TextStyle(
        color = primary, fontSize = 8.sp, fontWeight = FontWeight.Bold,
    )
    val onsetStyle = androidx.compose.ui.text.TextStyle(
        color = scheme.onSurfaceVariant, fontSize = 8.sp,
    )

    Canvas(Modifier.fillMaxWidth().height(172.dp)) {
        val padL = with(density) { PAD_L_DP.dp.toPx() }
        val padR = with(density) { PAD_R_DP.dp.toPx() }
        val topPad = with(density) { 12.dp.toPx() }
        val botPad = with(density) { 14.dp.toPx() }
        val plotW = size.width - padL - padR
        val plotH = size.height - topPad - botPad
        fun tx(t: Float) = padL + (t - xMin) / (xMax - xMin) * plotW
        fun ty(p: Float) = topPad + (1f - p) * plotH
        val span = xMax - xMin

        // plot backdrop
        drawRoundRect(
            scheme.surfaceContainerHighest,
            topLeft = Offset(padL, topPad), size = Size(plotW, plotH),
            cornerRadius = CornerRadius(with(density) { 6.dp.toPx() }),
        )

        // horizontal grid + % labels (right-aligned in the left gutter)
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
            val y = ty(p)
            drawLine(scheme.outlineVariant.copy(alpha = 0.6f), Offset(padL, y), Offset(padL + plotW, y), 1f)
            val r = measurer.measure("%.0f".format(p * 100), labelStyle)
            drawText(measurer, "%.0f".format(p * 100), Offset(padL - 3f - r.size.width, y - r.size.height / 2f), labelStyle)
        }
        // vertical grid + temperature labels
        ticks.forEach { t ->
            val x = tx(t.toFloat())
            if (x in padL..padL + plotW) {
                drawLine(scheme.outlineVariant.copy(alpha = 0.4f), Offset(x, topPad), Offset(x, topPad + plotH), 1f)
                val r = measurer.measure("$t°", labelStyle)
                drawText(measurer, "$t°", Offset(x - r.size.width / 2f, topPad + plotH + 2f), labelStyle)
            }
        }

        if (hasAny) {
            // ramp zone (onset -> peak)
            if (onsetC != null && peakC != null && peakC > onsetC) {
                drawRect(
                    primary.copy(alpha = 0.06f),
                    topLeft = Offset(tx(onsetC), topPad),
                    size = Size((tx(peakC) - tx(onsetC)).coerceAtLeast(0f), plotH),
                )
            }
            // CPU envelope fill (between worst & best case)
            val fill = Path()
            xs_indices(minEnv).forEachIndexed { i, _ ->
                val x = tx(xAt(i, xMin, span)); val y = ty(maxEnv[i])
                if (i == 0) fill.moveTo(x, y) else fill.lineTo(x, y)
            }
            for (i in minEnv.indices.reversed()) fill.lineTo(tx(xAt(i, xMin, span)), ty(minEnv[i]))
            fill.close()
            drawPath(fill, primary.copy(alpha = 0.12f))

            // onset / peak markers (these same X positions are reused by the bars)
            onsetC?.let { drawMarker(this, tx(it), topPad, plotH, scheme.onSurfaceVariant.copy(alpha = 0.4f), true) }
            peakC?.let { drawMarker(this, tx(it), topPad, plotH, primary.copy(alpha = 0.45f), true) }

            // best case (faint) + worst case (solid) CPU lines
            drawPolyline(this, maxEnv, primary.copy(alpha = 0.45f), 1.5f, false, ::tx, ::ty, xMin, span)
            drawPolyline(this, minEnv, primary, 2.5f, false, ::tx, ::ty, xMin, span)
            // GPU line (dashed secondary)
            if (hasGpu) drawPolyline(this, gpuEnv, secondary, 2f, true, ::tx, ::ty, xMin, span)

            // marker captions at the top
            peakC?.let {
                val s = "MAX %.0f°".format(it); val r = measurer.measure(s, markerStyle)
                drawText(measurer, s, Offset((tx(it) - r.size.width / 2f).coerceIn(padL, padL + plotW - r.size.width), 1f), markerStyle)
            }
            onsetC?.let {
                val s = "onset %.0f°".format(it); val r = measurer.measure(s, onsetStyle)
                drawText(measurer, s, Offset((tx(it) - r.size.width / 2f).coerceIn(padL, padL + plotW - r.size.width), 1f), onsetStyle)
            }
        } else {
            // nothing configured -> flat 100 % line + hint
            drawLine(primary, Offset(padL, ty(1f)), Offset(padL + plotW, ty(1f)), 2.5f)
            val hint = "No throttle levels — full performance at every temperature"
            val r = measurer.measure(hint, labelStyle)
            drawText(measurer, hint, Offset(padL + (plotW - r.size.width) / 2f, topPad + (plotH - r.size.height) / 2f), labelStyle)
        }
    }
}

// -- aligned per-cluster / GPU step bar ------------------------------------
@Composable
private fun ClusterStepBar(
    name: String, rightText: String,
    levels: List<ThrottleLevel>, hwNorm: Float,
    color: Color,
    xMin: Float, xMax: Float, onsetC: Float?, peakC: Float?,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name, color = color,
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = rightText, color = scheme.onSurfaceVariant,
            fontSize = 9.sp, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(2.dp))

    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val padL = with(density) { PAD_L_DP.dp.toPx() }
        val padR = with(density) { PAD_R_DP.dp.toPx() }
        val vPad = with(density) { 2.dp.toPx() }
        val plotW = size.width - padL - padR
        val h = size.height
        fun tx(t: Float) = padL + (t - xMin) / (xMax - xMin) * plotW
        fun ty(p: Float) = vPad + (1f - p) * (h - 2 * vPad)

        // track
        drawRoundRect(
            scheme.surfaceContainerHighest,
            topLeft = Offset(padL, 0f), size = Size(plotW, h),
            cornerRadius = CornerRadius(with(density) { 5.dp.toPx() }),
        )
        // aligned onset / peak cues
        onsetC?.let { drawLine(scheme.onSurfaceVariant.copy(alpha = 0.25f), Offset(tx(it), 0f), Offset(tx(it), h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f)) }
        peakC?.let { drawLine(color.copy(alpha = 0.35f), Offset(tx(it), 0f), Offset(tx(it), h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f)) }

        if (levels.isEmpty()) {
            // unrestricted -> flat full bar
            drawLine(color.copy(alpha = 0.8f), Offset(padL, ty(1f)), Offset(padL + plotW, ty(1f)), 2f)
            return@Canvas
        }

        // build the staircase top points (xMin is always below the first trigger)
        val top = mutableListOf<Offset>()
        top.add(Offset(tx(xMin), ty(1f)))
        var prev = 1f
        for (l in levels) {
            val tc = l.trigMc / 1000f
            if (tc > xMax) break
            if (tc >= xMin) {
                top.add(Offset(tx(tc), ty(prev)))
                prev = (l.freq / hwNorm).coerceIn(0f, 1f)
                top.add(Offset(tx(tc), ty(prev)))
                // notch at the trigger
                drawLine(scheme.outlineVariant, Offset(tx(tc), vPad), Offset(tx(tc), ty(prev)), 1f)
            } else {
                prev = (l.freq / hwNorm).coerceIn(0f, 1f)
            }
        }
        top.add(Offset(tx(xMax), ty(prev)))

        // filled area under the staircase
        val fill = Path()
        fill.moveTo(tx(xMin), ty(0f))
        top.forEach { fill.lineTo(it.x, it.y) }
        fill.lineTo(tx(xMax), ty(0f))
        fill.close()
        drawPath(fill, color.copy(alpha = 0.22f))

        // crisp top stroke
        val stroke = Path()
        top.forEachIndexed { i, o -> if (i == 0) stroke.moveTo(o.x, o.y) else stroke.lineTo(o.x, o.y) }
        drawPath(stroke, color, style = Stroke(width = with(density) { 2.dp.toPx() }))
    }
    Spacer(Modifier.height(2.dp))
}

// -- canvas helpers --------------------------------------------------------
private fun xs_indices(list: List<Float>) = list.indices
private fun xAt(i: Int, xMin: Float, span: Float) = xMin + span * i / (SAMPLES - 1)

private fun drawMarker(d: DrawScope, x: Float, top: Float, plotH: Float, color: Color, dashed: Boolean) {
    d.drawLine(
        color, Offset(x, top), Offset(x, top + plotH), 1.5f,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f) else null,
    )
}

private fun drawPolyline(
    d: DrawScope, values: List<Float>, color: Color, width: Float, dashed: Boolean,
    tx: (Float) -> Float, ty: (Float) -> Float, xMin: Float, span: Float,
) {
    if (values.size < 2) return
    val path = Path()
    values.forEachIndexed { i, v ->
        val x = tx(xMin + span * i / (values.size - 1)); val y = ty(v)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    d.drawPath(
        path, color,
        style = Stroke(
            width = with(d) { width.dp.toPx() },
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f) else null,
        ),
    )
}
