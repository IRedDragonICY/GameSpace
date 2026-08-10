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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PanelContent(
    modifier: Modifier = Modifier,
    tileRepository: TileRepository,
    interactor: BrightnessInteractor
) {
    val quickToggles = tileRepository.quickToggles
    val toolTiles = tileRepository.toolTiles
    val haptic = LocalHapticFeedback.current

    val dragDropState = remember {
        UnifiedDragDropState { sourceId, targetZone, targetIndex ->
            val currentQuick = tileRepository.quickToggles.map { it.id }.toMutableList()
            val currentTool = tileRepository.toolTiles.map { it.id }.toMutableList()

            val wasQuick = currentQuick.remove(sourceId)
            val wasTool = currentTool.remove(sourceId)

            if (wasQuick || wasTool) {
                if (targetZone == DragZone.Quick) {
                    currentQuick.add(targetIndex.coerceIn(0, currentQuick.size), sourceId)
                } else if (targetZone == DragZone.Tool) {
                    currentTool.add(targetIndex.coerceIn(0, currentTool.size), sourceId)
                }
                tileRepository.updateQuickToggles(currentQuick)
                tileRepository.updateTileSelection(currentTool)
            }
        }
    }

    var boxWindowPosition by remember { mutableStateOf(Offset.Zero) }
    var boxWindowWidth by remember { mutableStateOf(0f) }

    val quickScrollState = rememberScrollState()
    val tilesPerPage = 8 
    val pages = toolTiles.chunked(tilesPerPage).ifEmpty { listOf(emptyList()) }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }
    val EDGE_HOLD_MS = 200L

    LaunchedEffect(dragDropState.draggedTile) {
        var fired = false
        var edgeEnterMs = 0L
        while (dragDropState.draggedTile != null && isActive) {
            val x = dragDropState.dragPosition.x
            val left = boxWindowWidth > 0f && x < boxWindowPosition.x + edgePx
            val right = boxWindowWidth > 0f && x > boxWindowPosition.x + boxWindowWidth - edgePx
            val inEdge = left || right
            val now = android.os.SystemClock.uptimeMillis()

            if (!inEdge) {
                fired = false
                edgeEnterMs = 0L
            } else {
                if (edgeEnterMs == 0L) edgeEnterMs = now
                val held = now - edgeEnterMs
                if (held >= EDGE_HOLD_MS) {
                    when (dragDropState.targetZone) {
                        DragZone.Quick -> {
                            if (left && quickScrollState.value > 0) {
                                quickScrollState.dispatchRawDelta(-18f)
                            } else if (right && quickScrollState.value < quickScrollState.maxValue) {
                                quickScrollState.dispatchRawDelta(18f)
                            }
                        }
                        DragZone.Tool -> {
                            if (!fired) {
                                val cur = pagerState.currentPage
                                val target = if (left) cur - 1 else cur + 1
                                if (target in 0 until pagerState.pageCount) {
                                    fired = true
                                    pagerState.animateScrollToPage(target)
                                } else {
                                    fired = true
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
            kotlinx.coroutines.delay(16)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { 
                boxWindowPosition = it.positionInWindow() 
                boxWindowWidth = it.size.width.toFloat()
            }
    ) {
        Column(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (tileRepository.isBrightnessVisible.value) {
                BrightnessSlider(interactor = interactor)
            }

            // Quick Toggles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { 
                        val pos = it.positionInWindow()
                        dragDropState.quickZoneRect = androidx.compose.ui.geometry.Rect(pos, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    }
                    .horizontalScroll(state = quickScrollState)
                    .fadeEdges()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (quickToggles.isEmpty()) {
                    Spacer(modifier = Modifier.height(30.dp).width(80.dp))
                } else {
                    quickToggles.forEachIndexed { index, actualTile ->
                        key(actualTile.id) {
                            val interaction = remember { MutableInteractionSource() }
                            val isMoving = dragDropState.isMoving(actualTile.id)
                            val isTarget = dragDropState.targetZone == DragZone.Quick &&
                                dragDropState.targetIndex == index && !isMoving
                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        if (isTarget) { scaleX = 0.85f; scaleY = 0.85f; alpha = 0.4f }
                                        else if (isMoving) { alpha = 0f }
                                    }
                                    .onGloballyPositioned { layoutCoordinates ->
                                        if (!isMoving) {
                                            dragDropState.updateItemPosition(
                                                zone = DragZone.Quick,
                                                id = actualTile.id,
                                                index = index,
                                                offset = layoutCoordinates.positionInWindow().round(),
                                                size = layoutCoordinates.size,
                                            )
                                        }
                                    }
                                    .dragAndDropTile(
                                        tileId = actualTile.id,
                                        label = actualTile.label,
                                        icon = actualTile.icon,
                                        zone = DragZone.Quick,
                                        dragDropState = dragDropState,
                                        interactionSource = interaction,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            actualTile.toggle()
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            actualTile.onLongClick()
                                        },
                                    )
                            ) {
                                QuickToggleChip(tile = actualTile, interactionSource = interaction)
                            }
                        }
                    }
                }
            }

            // Tool Grid
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        val pos = it.positionInWindow()
                        dragDropState.toolZoneRect = androidx.compose.ui.geometry.Rect(pos, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    }
            ) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = pages.size.coerceAtLeast(1),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    pageSpacing = 8.dp,
                ) { pageIndex ->
                    val pageTiles = pages[pageIndex]
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (pageTiles.isEmpty()) {
                            Spacer(modifier = Modifier.height(60.dp).fillMaxWidth())
                        } else {
                            pageTiles.chunked(4).forEachIndexed { rowIndex, rowTiles ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    rowTiles.forEachIndexed { colIndex, actualTile ->
                                        key(actualTile.id) {
                                            val interaction = remember { MutableInteractionSource() }
                                            val globalIndex = pageIndex * tilesPerPage + rowIndex * 4 + colIndex
                                            val isMoving = dragDropState.isMoving(actualTile.id)
                                            val isTarget = dragDropState.targetZone == DragZone.Tool &&
                                                dragDropState.targetIndex == globalIndex && !isMoving
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .graphicsLayer {
                                                        if (isTarget) { scaleX = 0.85f; scaleY = 0.85f; alpha = 0.4f }
                                                        else if (isMoving) { alpha = 0f }
                                                    }
                                                    .onGloballyPositioned { layoutCoordinates ->
                                                        if (!isMoving) {
                                                            dragDropState.updateItemPosition(
                                                                zone = DragZone.Tool,
                                                                id = actualTile.id,
                                                                index = globalIndex,
                                                                offset = layoutCoordinates.positionInWindow().round(),
                                                                size = layoutCoordinates.size,
                                                            )
                                                        }
                                                    }
                                                    .dragAndDropTile(
                                                        tileId = actualTile.id,
                                                        label = actualTile.label,
                                                        icon = actualTile.icon,
                                                        zone = DragZone.Tool,
                                                        dragDropState = dragDropState,
                                                        interactionSource = interaction,
                                                        onClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            actualTile.toggle()
                                                        },
                                                        onLongClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            actualTile.onLongClick()
                                                        },
                                                    )
                                            ) {
                                                TileButton(
                                                    tile = actualTile,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    interactionSource = interaction,
                                                )
                                            }
                                        }
                                    }
                                    repeat(4 - rowTiles.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                if (pages.size > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        repeat(pages.size) { i ->
                            val color = if (pagerState.currentPage == i) LocalPanelAccent.current else Color.White.copy(alpha = 0.2f)
                            Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(color))
                        }
                    }
                }
            }
        }
        
        // OVERLAY MENGAMBANG TILE YANG SEDANG DIDRAG
        if (dragDropState.draggedTile != null) {
            val dragged = dragDropState.draggedTile!!
            val actualTile = tileRepository.allAvailableTiles.find { it.id == dragged.id }

            if (actualTile != null) {
                val offset = dragDropState.dragPosition - dragDropState.grabOffset - boxWindowPosition
                Box(
                    modifier = Modifier
                        .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                        .zIndex(100f)
                        .graphicsLayer {
                            scaleX = 1.15f
                            scaleY = 1.15f
                            shadowElevation = 16.dp.toPx()
                        }
                ) {
                    if (dragged.sourceZone == DragZone.Quick) {
                        QuickToggleChip(
                            tile = actualTile,
                            interactionSource = remember { MutableInteractionSource() },
                        )
                    } else {
                        TileButton(
                            tile = actualTile,
                            modifier = Modifier.width(75.dp),
                            interactionSource = remember { MutableInteractionSource() },
                        )
                    }
                }
            }
        }
    }
}


/**
 * Pill-shaped quick toggle chip — compact, thumb-friendly.
 * ON = filled primary color with icon + label.
 * OFF = outlined surface variant with icon only.
 */
@Composable
fun QuickToggleChip(
    tile: TileAction,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource,
) {
    val enabled by tile.observeEnabled()
    val isRecordingTile = tile.id == "game_record"
    val containerColor by animateColorAsState(
        targetValue = if (enabled && isRecordingTile) Color(0xFFFF1744)
        else if (enabled) LocalPanelAccent.current
        else LocalPanelAccent.current.copy(alpha = 0.2f),
        animationSpec = tween(200),
        label = "chip_color",
    )
    // KUNCI FIX: teks/icon ikut terang-gelapnya accent, bukan hardcode White.
    val contentColor by animateColorAsState(
        targetValue = when {
            enabled && isRecordingTile -> Color.White
            enabled -> readableOn(LocalPanelAccent.current)
            isRecordingTile -> Color(0xFFFF1744).copy(alpha = 0.7f)
            else -> PanelTextSecondary
        },
        animationSpec = tween(200),
        label = "chip_content",
    )
    val chipShape = remember { chamferShape(bigCut = 9.dp, smallCut = 3.dp) }
    Box(
        modifier = modifier
            .clip(chipShape)
            .background(containerColor)
            .indication(interactionSource, ripple())
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = tilePainter(tile),
                contentDescription = tile.label,
                modifier = Modifier.size(16.dp),
                tint = contentColor,
            )
            AnimatedVisibility(
                visible = enabled,
                enter = fadeIn(tween(150)) + expandHorizontally(tween(200)),
                exit = fadeOut(tween(100)) + shrinkHorizontally(tween(150)),
            ) {
                Text(
                    text = tile.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.height(24.dp).width(40.dp), contentAlignment = Alignment.CenterEnd) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.scale(0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = LocalPanelAccent.current,
                ),
            )
        }
    }
}

@Composable
fun TileButton(
    tile: TileAction,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource,
) {
    val isEnabled by tile.observeEnabled()
    val bgColor = if (isEnabled) LocalPanelAccent.current else LocalPanelAccent.current.copy(alpha = 0.2f)
    val fgColor = if (isEnabled) Color.Black else PanelTextSecondary
    val tileShape = remember { chamferShape(bigCut = 8.dp, smallCut = 3.dp) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(26.dp)
            .clip(tileShape)
            .background(bgColor)
            .indication(interactionSource, ripple())
            .padding(horizontal = 6.dp),
    ) {
        Icon(
            painter = tilePainter(tile),
            contentDescription = tile.label,
            tint = fgColor,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = tile.label,
            color = fgColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            lineHeight = 9.sp,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BrightnessSlider(interactor: BrightnessInteractor) {
    val brightnessInfo by interactor.brightnessInfo.collectAsState()
    val isAuto by interactor.isAuto.collectAsState()

    var sliderPosition by remember { mutableFloatStateOf(0.5f) }
    var userIsAdjusting by remember { mutableStateOf(false) }

    val targetSliderPosition = remember(brightnessInfo) {
        brightnessInfo?.let { info ->
            val gamma = convertLinearToGammaFloat(
                info.brightness,
                info.brightnessMinimum,
                info.brightnessMaximum
            )
            getPercentage(
                gamma.toDouble(),
                GAMMA_SPACE_MIN.toFloat(),
                GAMMA_SPACE_MAX.toFloat()
            ).toFloat()
        } ?: 0.5f
    }

    LaunchedEffect(targetSliderPosition) {
        if (!userIsAdjusting) {
            sliderPosition = targetSliderPosition
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { sliderPosition }
            .distinctUntilChanged()
            .collectLatest { value ->
                if (userIsAdjusting) {
                    interactor.onUserInteracted()
                    interactor.setBrightness(value)
                    delay(100)
                }
            }
    }

    // Ultra-thin accent bar brightness slider
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp),
    ) {
        // Small sun icon (dim)
        Icon(
            painter = painterResource(R.drawable.materialsymbols_ic_brightness_7_rounded_filled),
            contentDescription = null,
            tint = PanelTextSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(10.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))

        // Custom thin-line slider via Canvas + pointer input
        Box(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            userIsAdjusting = true
                            val newVal = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            sliderPosition = newVal
                            interactor.onUserInteracted()
                            interactor.setBrightness(newVal)
                        },
                        onDragEnd = { userIsAdjusting = false },
                        onDragCancel = { userIsAdjusting = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val newVal = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            sliderPosition = newVal
                            interactor.onUserInteracted()
                            interactor.setBrightness(newVal)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        userIsAdjusting = true
                        val newVal = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        sliderPosition = newVal
                        interactor.onUserInteracted()
                        interactor.setBrightness(newVal)
                        userIsAdjusting = false
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Track background (thin line)
            val sliderAccent = LocalPanelAccent.current
            Canvas(
                modifier = Modifier.fillMaxWidth().height(2.dp)
            ) {
                // Inactive track
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.08f),
                    size = this.size.copy(height = 2.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                // Active track
                drawRoundRect(
                    color = sliderAccent.copy(alpha = 0.7f),
                    size = this.size.copy(
                        width = this.size.width * sliderPosition,
                        height = 2.dp.toPx()
                    ),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }
            // Thumb dot
            Box(
                modifier = Modifier
                    .fillMaxWidth(sliderPosition.coerceIn(0.01f, 0.99f))
                    .wrapContentWidth(Alignment.End)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(LocalPanelAccent.current)
                        .shadow(2.dp, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))
        // Bright sun icon
        Icon(
            painter = painterResource(
                if (isAuto == true) R.drawable.materialsymbols_ic_brightness_auto_rounded_filled
                else R.drawable.materialsymbols_ic_brightness_7_rounded_filled
            ),
            contentDescription = null,
            tint = if (isAuto == true) LocalPanelAccent.current else PanelTextSecondary.copy(alpha = 0.7f),
            modifier = Modifier
                .size(10.dp)
                .clickable { interactor.toggleAutoMode() },
        )
    }
}
