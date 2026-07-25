/*
 * Copyright (C) 2025-2026 AxionOS
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

import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R

private const val NOTCH_WIDTH_DP = 14
private const val NOTCH_HEIGHT_DP = 36
private const val NOTCH_TOUCH_WIDTH_DP = 28
private const val FPS_CIRCLE_SIZE_DP = 28
private const val DRAG_THRESHOLD_DP = 12

/**
 * Simplified GameBarView: the pill/notch is the only visible element.
 * Tapping it opens the main GamePanel directly (no intermediate VerticalPill).
 * Dragging repositions the pill on screen.
 */
@Composable
fun GameBarView(
    showFps: Boolean,
    fpsText: String,
    isIdle: Boolean,
    idleAlpha: Float,
    dockedOnLeft: Boolean,
    onShowPanel: () -> Unit,
    onDragStart: () -> Pair<Int, Int>,
    onDragUpdate: (Int, Int) -> Unit,
    onDragEnd: (Int, Int) -> Unit,
) {
    val animatedPillAlpha by animateFloatAsState(
        targetValue = if (isIdle) idleAlpha else 1f,
        label = "pillAlpha",
    )

    var startWindowX by remember { mutableIntStateOf(0) }
    var startWindowY by remember { mutableIntStateOf(0) }
    var accDragX by remember { mutableFloatStateOf(0f) }
    var accDragY by remember { mutableFloatStateOf(0f) }
    var repositioning by remember { mutableStateOf(false) }

    val pointerModifier = Modifier
        .pointerInput(dockedOnLeft) {
            val dragThresholdPx = DRAG_THRESHOLD_DP.dp.toPx()
            detectDragGestures(
                onDragStart = { _ ->
                    val pos = onDragStart()
                    startWindowX = pos.first
                    startWindowY = pos.second
                    accDragX = 0f
                    accDragY = 0f
                    repositioning = false
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    accDragX += dragAmount.x
                    accDragY += dragAmount.y
                    if (!repositioning &&
                        (abs(accDragY) > dragThresholdPx || abs(accDragX) > dragThresholdPx)
                    ) {
                        repositioning = true
                    }
                    if (repositioning) {
                        onDragUpdate(
                            startWindowX + accDragX.toInt(),
                            startWindowY + accDragY.toInt(),
                        )
                    }
                },
                onDragEnd = {
                    if (repositioning) {
                        onDragEnd(
                            startWindowX + accDragX.toInt(),
                            startWindowY + accDragY.toInt(),
                        )
                    } else {
                        onDragEnd(startWindowX, startWindowY)
                    }
                },
                onDragCancel = {
                    onDragEnd(startWindowX, startWindowY)
                },
            )
        }

    PillTab(
        dockedOnLeft = dockedOnLeft,
        showFps = showFps,
        fpsText = fpsText,
        idleAlpha = animatedPillAlpha,
        pointerModifier = pointerModifier,
        onTap = onShowPanel,
    )
}

@Composable
private fun PillTab(
    dockedOnLeft: Boolean,
    showFps: Boolean,
    fpsText: String,
    idleAlpha: Float,
    pointerModifier: Modifier,
    onTap: () -> Unit,
) {
    if (showFps) {
        val thermalReduced = rememberTelemetry().thermalHeadroomReduced
        
        Box(
            modifier = Modifier
                .size(FPS_CIRCLE_SIZE_DP.dp)
                .alpha(idleAlpha)
                .then(pointerModifier)
                .pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }
                .border(0.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                .clip(CircleShape)
                .background(Color(0xCC1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = fpsText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (thermalReduced) {
                    Text(
                        text = "THR",
                        color = Color(0xFFFF9800),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 7.sp,
                    )
                }
            }
        }
    } else {
        val notchShape = if (dockedOnLeft) {
            RoundedCornerShape(topEndPercent = 100, bottomEndPercent = 100)
        } else {
            RoundedCornerShape(topStartPercent = 100, bottomStartPercent = 100)
        }
        Box(
            modifier = Modifier
                .width(NOTCH_TOUCH_WIDTH_DP.dp)
                .height(NOTCH_HEIGHT_DP.dp)
                .then(pointerModifier)
                .pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) },
            contentAlignment = if (dockedOnLeft) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .width(NOTCH_WIDTH_DP.dp)
                    .height(NOTCH_HEIGHT_DP.dp)
                    .alpha(idleAlpha)
                    .border(0.5.dp, Color.White.copy(alpha = 0.35f), notchShape)
                    .clip(notchShape)
                    .background(Color(0xCC1A1A1A)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (dockedOnLeft) R.drawable.materialsymbols_ic_chevron_left_rounded_filled
                        else R.drawable.materialsymbols_ic_chevron_right_rounded_filled
                    ),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}
