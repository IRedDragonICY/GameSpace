/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Edge-fade for a horizontally-scrolling row whose last item is clipped
 * off-screen. Uses offscreen + DstOut so it reveals the glass behind
 * instead of painting a solid bar.
 */
fun Modifier.fadeEdges(width: Dp = 14.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        if (w > 0f && size.width > w * 2f) {
            // Left edge: full erase at x=0 → transparent at x=w
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = 0f,
                    endX = w,
                ),
                blendMode = BlendMode.DstOut,
            )
            // Right edge: transparent at x=(width-w) → full erase at x=width
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = size.width - w,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstOut,
            )
        }
    }
