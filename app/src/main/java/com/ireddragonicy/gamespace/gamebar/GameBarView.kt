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

import kotlin.math.abs
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import com.ireddragonicy.gamespace.data.SidebarStyle

private const val DRAG_THRESHOLD_DP = 12

@Composable
fun GameBarView(
    style: SidebarStyle,
    showFps: Boolean,
    fpsText: String,
    isIdle: Boolean,
    idleAlpha: Float,
    dockedOnLeft: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onShowPanel: () -> Unit,
    onDragStart: () -> Pair<Int, Int>,
    onDragUpdate: (Int, Int) -> Unit,
    onDragEnd: (Int, Int) -> Unit,
) {
    val targetAlpha = when {
        !isIdle -> 1f
        style.handleAutoHide -> 0f
        else -> (style.handleIdleAlpha / 100f).coerceIn(0f, 1f)
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
        label = "handleAlpha",
    )

    var startWindowX by remember { mutableIntStateOf(0) }
    var startWindowY by remember { mutableIntStateOf(0) }
    var accDragX by remember { mutableFloatStateOf(0f) }
    var accDragY by remember { mutableFloatStateOf(0f) }
    var repositioning by remember { mutableStateOf(false) }

    val pointerModifier = Modifier.pointerInput(dockedOnLeft) {
        val dragThresholdPx = DRAG_THRESHOLD_DP.dp.toPx()
        detectDragGestures(
            onDragStart = {
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
                if (!repositioning && (abs(accDragY) > dragThresholdPx || abs(accDragX) > dragThresholdPx))
                    repositioning = true
                if (repositioning)
                    onDragUpdate(startWindowX + accDragX.toInt(), startWindowY + accDragY.toInt())
            },
            onDragEnd = {
                if (repositioning) onDragEnd(startWindowX + accDragX.toInt(), startWindowY + accDragY.toInt())
                else onDragEnd(startWindowX, startWindowY)
            },
            onDragCancel = { onDragEnd(startWindowX, startWindowY) },
        )
    }

    // SHARED component — identical in preview & real strip
    StripHandle(
        style = style,
        dockedOnLeft = dockedOnLeft,
        idleAlpha = animatedAlpha,
        accent = accent,
        showFps = showFps,
        fpsText = fpsText,
        onTap = onShowPanel,
        pointerModifier = pointerModifier,
    )
}

private val Int.dp: androidx.compose.ui.unit.Dp
    get() = androidx.compose.ui.unit.Dp(this.toFloat())