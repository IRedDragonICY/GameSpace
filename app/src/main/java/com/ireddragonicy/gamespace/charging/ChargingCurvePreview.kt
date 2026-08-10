/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.charging

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Static step-curve preview: X = temperature (°C), Y = max charge current (W).
 * Shows how the FCC ceiling falls as the battery warms up — the visual essence
 * of the profile. Pure Canvas, no state.
 */
@Composable
fun ChargingCurvePreview(
    tiers: List<ChargingTier>,
    suspendMc: Long,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val danger = MaterialTheme.colorScheme.error

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val padL = 8.dp.toPx(); val padR = 8.dp.toPx()
            val padT = 10.dp.toPx(); val padB = 18.dp.toPx()
            val plotW = size.width - padL - padR
            val plotH = size.height - padT - padB

            val xMinC = 25f
            val xMaxC = (ChargingProfiles.mcToC(suspendMc) + 3f).coerceAtLeast(50f)
            val yMaxW = (tiers.maxOfOrNull { it.maxUa }
                ?.let { ChargingProfiles.uaToWatt(it) } ?: 12f).coerceAtLeast(5f)

            fun tx(c: Float) = padL + (c - xMinC) / (xMaxC - xMinC) * plotW
            fun ty(w: Float) = padT + (1f - w / yMaxW) * plotH

            for (i in 0..4) {
                val y = padT + plotH * i / 4f
                drawLine(grid, Offset(padL, y), Offset(padL + plotW, y), 1f)
            }
            for (i in 0..5) {
                val x = padL + plotW * i / 5f
                drawLine(grid.copy(alpha = 0.5f), Offset(x, padT), Offset(x, padT + plotH), 1f)
            }

            if (tiers.isEmpty()) return@Canvas

            val fullW = ChargingProfiles.uaToWatt(tiers.first().maxUa)
            val path = Path()
            path.moveTo(tx(xMinC), ty(fullW))
            var prevW = fullW
            for (t in tiers) {
                val trigC = ChargingProfiles.mcToC(t.trigMc).coerceIn(xMinC, xMaxC)
                val w = ChargingProfiles.uaToWatt(t.maxUa).coerceIn(0f, yMaxW)
                path.lineTo(tx(trigC), ty(prevW))
                path.lineTo(tx(trigC), ty(w))
                prevW = w
            }
            path.lineTo(tx(xMaxC), ty(prevW))
            drawPath(path, primary, style = Stroke(width = 2.5.dp.toPx()))

            for (t in tiers) {
                val trigC = ChargingProfiles.mcToC(t.trigMc).coerceIn(xMinC, xMaxC)
                drawLine(primary.copy(alpha = 0.4f),
                    Offset(tx(trigC), padT), Offset(tx(trigC), padT + plotH), 1f)
            }
            val suspC = ChargingProfiles.mcToC(suspendMc).coerceIn(xMinC, xMaxC)
            drawLine(danger, Offset(tx(suspC), padT), Offset(tx(suspC), padT + plotH),
                strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f))
        }
    }
}
