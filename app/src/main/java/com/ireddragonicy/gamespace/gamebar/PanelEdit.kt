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
fun TileEditPanel(
    tileRepository: TileRepository,
    onClose: () -> Unit
) {
    val allTiles = remember { tileRepository.allAvailableTiles }
    val haptic = LocalHapticFeedback.current

    val quickToggleList = remember {
        tileRepository.quickToggles.map { DragTileData(it.id, it.label, it.icon, DragZone.Quick) }.toMutableStateList()
    }
    val toolTileList = remember {
        tileRepository.toolTiles.map { DragTileData(it.id, it.label, it.icon, DragZone.Tool) }.toMutableStateList()
    }
    val availableList = remember {
        val assignedIds = quickToggleList.map { it.id }.toSet() + toolTileList.map { it.id }.toSet()
        allTiles.filter { it.id !in assignedIds }
            .map { DragTileData(it.id, it.label, it.icon, DragZone.None) }
            .toMutableStateList()
    }

    val dragDropState = remember {
        UnifiedDragDropState { sourceId, targetZone, targetIndex ->
            val sourceInQuick = quickToggleList.firstOrNull { it.id == sourceId }
            val sourceInTool = toolTileList.firstOrNull { it.id == sourceId }
            val sourceInAvailable = availableList.firstOrNull { it.id == sourceId }
            val tile = sourceInQuick ?: sourceInTool ?: sourceInAvailable ?: return@UnifiedDragDropState

            if (sourceInQuick != null) quickToggleList.remove(sourceInQuick)
            if (sourceInTool != null) toolTileList.remove(sourceInTool)
            if (sourceInAvailable != null) availableList.remove(sourceInAvailable)

            if (targetZone == DragZone.Quick) {
                quickToggleList.add(targetIndex.coerceIn(0, quickToggleList.size), tile.copy(sourceZone = DragZone.Quick))
            } else if (targetZone == DragZone.Tool) {
                toolTileList.add(targetIndex.coerceIn(0, toolTileList.size), tile.copy(sourceZone = DragZone.Tool))
            } else if (targetZone == DragZone.None) {
                availableList.add(targetIndex.coerceIn(0, availableList.size), tile.copy(sourceZone = DragZone.None))
            }
        }
    }

    var boxWindowPosition by remember { mutableStateOf(Offset.Zero) }
    val localMaxHeight = (LocalConfiguration.current.screenHeightDp - 64).dp

    Box(modifier = Modifier.fillMaxWidth().onGloballyPositioned { boxWindowPosition = it.positionInWindow() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = localMaxHeight)
                .padding(top = 4.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            // Header with back + save
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        tileRepository.updateQuickToggles(quickToggleList.map { it.id })
                        tileRepository.updateTileSelection(toolTileList.map { it.id })
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalPanelAccent.current,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(R.string.save))
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {

                Spacer(modifier = Modifier.height(8.dp))

                // ── Panel Options ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalPanelAccent.current.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.panel_options), 
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Theme Color Selection
                    Text(
                        text = stringResource(R.string.panel_color_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).horizontalScroll(rememberScrollState())
                    ) {
                        // 0 = Material You
                        val isMSelected = tileRepository.panelColorMode.intValue == 0
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .border(
                                    width = if (isMSelected) 2.dp else 0.dp,
                                    color = if (isMSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { tileRepository.setPanelColorMode(0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("M", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        // 1-8 = Presets
                        ACCENT_PRESETS.forEachIndexed { index, color ->
                            val modeValue = index + 1
                            val isSelected = tileRepository.panelColorMode.intValue == modeValue
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 2.dp else 0.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { tileRepository.setPanelColorMode(modeValue) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingToggleRow(
                        title = stringResource(R.string.brightness_slider),
                        checked = tileRepository.isBrightnessVisible.value,
                        onCheckedChange = { tileRepository.setBrightnessEnabled(it) }
                    )

                    SettingToggleRow(
                        title = stringResource(R.string.fps_graph),
                        checked = tileRepository.isFpsGraphVisible.value,
                        onCheckedChange = { tileRepository.setFpsGraphEnabled(it) }
                    )

                    SettingToggleRow(
                        title = "Glassmorphism Blur",
                        checked = tileRepository.isBlurEnabled.value,
                        onCheckedChange = { tileRepository.setBlurEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════
                // ZONE 1: Quick Toggles (horizontal pill chips)
                // ═══════════════════════════════════════════════════════
                Text(
                    stringResource(R.string.quick_toggles_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            LocalPanelAccent.current.copy(alpha = 0.05f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(8.dp)
                ) {
                    if (quickToggleList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.no_quick_toggles),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        DraggableGrid(
                            columns = 4,
                            horizontalSpacing = 6,
                            verticalSpacing = 6,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            quickToggleList.forEachIndexed { index, tile ->
                                key(tile.id) {
                                    val isMoving = dragDropState.isMoving(tile.id)
                                    Box(
                                        modifier = Modifier
                                            .graphicsLayer { if (isMoving) alpha = 0f }
                                            .onGloballyPositioned { layoutCoordinates ->
                                                if (!isMoving) {
                                                    dragDropState.updateItemPosition(
                                                        zone = DragZone.Quick,
                                                        id = tile.id,
                                                        index = index,
                                                        offset = layoutCoordinates.positionInWindow().round(),
                                                        size = layoutCoordinates.size
                                                    )
                                                }
                                            }
                                            .dragAndDropTile(
                                                tileId = tile.id,
                                                label = tile.label,
                                                icon = tile.icon,
                                                zone = DragZone.Quick,
                                                dragDropState = dragDropState
                                            )
                                            .clip(RoundedCornerShape(50)) // Pill shape
                                            .background(LocalPanelAccent.current) 
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).height(20.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(id = tile.icon),
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = Color.White,
                                            )
                                            Text(
                                                text = tile.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════
                // ZONE 2: Tool Grid (4-column paged tiles)
                // ═══════════════════════════════════════════════════════
                Text(
                    stringResource(R.string.tool_grid_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            LocalPanelAccent.current.copy(alpha = 0.05f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (toolTileList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.no_tool_tiles),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        DraggableGrid(
                            columns = 4,
                            horizontalSpacing = 8,
                            verticalSpacing = 8,
                            pageBreakRows = 2,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            toolTileList.forEachIndexed { idx, tile ->
                                val globalIndex = idx
                                key(tile.id) {
                                    val isMoving = dragDropState.isMoving(tile.id)
                                    Box(
                                        modifier = Modifier
                                            .wrapContentHeight()
                                            .graphicsLayer { if (isMoving) alpha = 0f }
                                            .onGloballyPositioned { layoutCoordinates ->
                                                if (!isMoving) {
                                                    dragDropState.updateItemPosition(
                                                        zone = DragZone.Tool,
                                                        id = tile.id,
                                                        index = globalIndex,
                                                        offset = layoutCoordinates.positionInWindow().round(),
                                                        size = layoutCoordinates.size
                                                    )
                                                }
                                            }
                                            .dragAndDropTile(
                                                tileId = tile.id,
                                                label = tile.label,
                                                icon = tile.icon,
                                                zone = DragZone.Tool,
                                                dragDropState = dragDropState
                                            )
                                    ) {
                                        EditorGameTile(
                                            label = tile.label,
                                            icon = tile.icon,
                                            isAdded = true
                                        )
                                        }
                                    }
                                }
                            }
                        }

                }

                Spacer(modifier = Modifier.height(16.dp))

                // ═══════════════════════════════════════════════════════
                // ZONE 3: Available Tiles (not assigned)
                // ═══════════════════════════════════════════════════════
                Text(
                    stringResource(R.string.available_tiles),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { dragDropState.availableZoneRect = it.boundsInWindow() },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (availableList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.all_tiles_added),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        DraggableGrid(
                            columns = 4,
                            horizontalSpacing = 8,
                            verticalSpacing = 8,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            availableList.forEachIndexed { index, tile ->
                                key(tile.id) {
                                    val isMoving = dragDropState.isMoving(tile.id)
                                    Box(
                                        modifier = Modifier
                                            .wrapContentHeight()
                                            .graphicsLayer { if (isMoving) alpha = 0f }
                                            .onGloballyPositioned { layoutCoordinates ->
                                                if (!isMoving) {
                                                    dragDropState.updateItemPosition(
                                                        zone = DragZone.None,
                                                        id = tile.id,
                                                        index = index,
                                                        offset = layoutCoordinates.positionInWindow().round(),
                                                        size = layoutCoordinates.size
                                                    )
                                                }
                                            }
                                            .dragAndDropTile(
                                                tileId = tile.id,
                                                label = tile.label,
                                                icon = tile.icon,
                                                zone = DragZone.None,
                                                dragDropState = dragDropState
                                            )
                                    ) {
                                        EditorGameTile(
                                            label = tile.label,
                                            icon = tile.icon,
                                            isAdded = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Floating drag overlay for the editor panel
        if (dragDropState.draggedTile != null) {
            val dragged = dragDropState.draggedTile!!
            val offset = dragDropState.dragPosition - dragDropState.grabOffset - boxWindowPosition
            Box(
                modifier = Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .zIndex(100f)
                    .graphicsLayer {
                        scaleX = 1.1f
                        scaleY = 1.1f
                        shadowElevation = 16.dp.toPx()
                    }
            ) {
                EditorGameTile(
                    label = dragged.label,
                    icon = dragged.icon,
                    isAdded = true
                )
            }
        }
    }
}


@Composable
fun EditorGameTile(
    label: String,
    icon: Int,
    isAdded: Boolean
) {
    val bgColor = if (isAdded) LocalPanelAccent.current else LocalPanelAccent.current.copy(alpha = 0.2f)
    val fgColor = if (isAdded) Color.Black else PanelTextSecondary
    
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 0.dp, bottom = 0.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                tint = fgColor,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                color = fgColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                lineHeight = 9.sp,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
