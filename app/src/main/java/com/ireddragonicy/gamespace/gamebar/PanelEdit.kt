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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight

import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
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
import com.ireddragonicy.gamespace.gamebar.monitor.*
import com.ireddragonicy.gamespace.settings.PerAppSettingsActivity
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import com.ireddragonicy.gamespace.utils.rememberDrawablePainter
import dagger.hilt.android.EntryPointAccessors

import android.graphics.Color as AndroidColor
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
    scrollState: ScrollState? = null,
    viewportTop: Float = 0f,
    viewportHeight: Float = 0f,
    onClose: () -> Unit
) {
    val allTiles = remember { tileRepository.allAvailableTiles }
    val haptic = LocalHapticFeedback.current
    val quickToggleList = remember {
        tileRepository.quickToggles.map { DragTileData(it.id, it.label, it.icon, it.iconDrawable, it.group, DragZone.Quick) }.toMutableStateList()
    }
    val toolTileList = remember {
        tileRepository.toolTiles.map { DragTileData(it.id, it.label, it.icon, it.iconDrawable, it.group, DragZone.Tool) }.toMutableStateList()
    }
    val availableList = remember {
        val assignedIds = quickToggleList.map { it.id }.toSet() + toolTileList.map { it.id }.toSet()
        allTiles.filter { it.id !in assignedIds }
            .map { DragTileData(it.id, it.label, it.icon, it.iconDrawable, it.group, DragZone.None) }
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
                val insertion = availableList.indexOfLast { it.group == tile.group } + 1
                availableList.add(insertion, tile.copy(sourceZone = DragZone.None))
            }
            tileRepository.updateQuickToggles(quickToggleList.map { it.id })
            tileRepository.updateTileSelection(toolTileList.map { it.id })
        }
    }

    // Provider scroll untuk modifier drag (kompensasi posisi stale).
    val getScroll: () -> Int = { scrollState?.value ?: 0 }

    var boxWindowPosition by remember { mutableStateOf(Offset.Zero) }
    var boxMeasuredScroll by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableStateOf(SettingsTab.GENERAL) }
    val accent = LocalPanelAccent.current
    val edgePx = with(LocalDensity.current) { 54.dp.toPx() }

    // AUTO-SCROLL VERTIKAL SAAT DRAG (Settings / PanelEdit).
    LaunchedEffect(dragDropState.draggedTile) {
        while (dragDropState.draggedTile != null && isActive) {
            if (scrollState != null && viewportHeight > 0f) {
                val y = dragDropState.dragPosition.y
                val topThreshold = viewportTop + edgePx
                val bottomThreshold = (viewportTop + viewportHeight) - edgePx
                val currentScroll = scrollState.value
                if (y < topThreshold && currentScroll > 0) {
                    val depth = ((topThreshold - y) / edgePx).coerceIn(0.1f, 1.0f)
                    val step = (24f * depth).coerceAtLeast(2f)
                    scrollState.dispatchRawDelta(-step)
                    dragDropState.onDragTo(dragDropState.dragPosition, scrollState.value)
                } else if (y > bottomThreshold && currentScroll < scrollState.maxValue) {
                    val depth = ((y - bottomThreshold) / edgePx).coerceIn(0.1f, 1.0f)
                    val step = (24f * depth).coerceAtLeast(2f)
                    scrollState.dispatchRawDelta(step)
                    dragDropState.onDragTo(dragDropState.dragPosition, scrollState.value)
                } else {
                    dragDropState.onDragTo(dragDropState.dragPosition, currentScroll)
                }
            }
            delay(16)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                boxWindowPosition = it.positionInWindow()
                boxMeasuredScroll = scrollState?.value ?: 0
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, start = 10.dp, end = 10.dp, bottom = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = PanelTheme.TextPrimary,
                        modifier = Modifier.size(24.dp).clickable(onClick = onClose),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SETTINGS",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                    )
                }
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tileRepository.resetToDefault()
                        quickToggleList.clear()
                        quickToggleList.addAll(tileRepository.quickToggles.map { DragTileData(it.id, it.label, it.icon, it.iconDrawable, it.group, DragZone.Quick) })
                        toolTileList.clear()
                        toolTileList.addAll(tileRepository.toolTiles.map { DragTileData(it.id, it.label, it.icon, it.iconDrawable, it.group, DragZone.Tool) })
                        availableList.clear()
                        val assigned = quickToggleList.map { it.id }.toSet() + toolTileList.map { it.id }.toSet()
                        availableList.addAll(allTiles.filter { it.id !in assigned }.map { DragTileData(it.id, it.label, it.icon, it.iconDrawable, it.group, DragZone.None) })
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "Reset", tint = accent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Reset Default", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            SettingsTabRow(selected = selectedTab, accent = accent, onSelect = { selectedTab = it })
            Spacer(modifier = Modifier.height(6.dp))

            when (selectedTab) {
                SettingsTab.GENERAL -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        // ── Panel Options (compact + custom accent) ──
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LocalPanelAccent.current.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.panel_options),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.panel_color_title),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            PanelColorRow(tileRepository)
                            Spacer(modifier = Modifier.height(6.dp))
                            SettingToggleRow(stringResource(R.string.brightness_slider), tileRepository.isBrightnessVisible.value, onCheckedChange = { tileRepository.setBrightnessEnabled(it) })
                            SettingToggleRow(stringResource(R.string.fps_graph), tileRepository.isFpsGraphVisible.value, onCheckedChange = { tileRepository.setFpsGraphEnabled(it) })
                            SettingToggleRow("Glassmorphism Blur", tileRepository.isBlurEnabled.value, onCheckedChange = { tileRepository.setBlurEnabled(it) })
                            if (tileRepository.isBlurEnabled.value) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Radius", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                                    Slider(
                                        value = tileRepository.blurRadius.value.toFloat(),
                                        onValueChange = { tileRepository.setBlurRadius(it.toInt()) },
                                        valueRange = 10f..100f,
                                        modifier = Modifier.weight(1f).height(20.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = LocalPanelAccent.current,
                                            activeTrackColor = LocalPanelAccent.current.copy(alpha = 0.8f),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.08f),
                                        ),
                                    )
                                    Text(
                                        "${tileRepository.blurRadius.value}", color = LocalPanelAccent.current,
                                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.width(26.dp), textAlign = TextAlign.End,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        // ═══ SCREEN RECORD DEFAULTS ═══
                        val editContext = LocalContext.current
                        val appSettings = remember {
                            EntryPointAccessors.fromApplication(
                                editContext.applicationContext,
                                ServiceViewEntryPoint::class.java
                            ).appSettings()
                        }

                        var screenRecordTarget by remember { mutableStateOf(appSettings.screenRecordTargetMode) }
                        var screenRecordAudio by remember { mutableStateOf(appSettings.screenRecordAudioSource) }
                        var screenRecordHEVC by remember { mutableStateOf(appSettings.screenRecordHEVC) }
                        var screenRecordTaps by remember { mutableStateOf(appSettings.screenRecordShowTaps) }
                        var screenRecordLowQuality by remember { mutableStateOf(appSettings.screenRecordLowQuality) }
                        var screenRecordLongerDuration by remember { mutableStateOf(appSettings.screenRecordLongerDuration) }

                        Text("SCREEN RECORD DEFAULTS", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LocalPanelAccent.current.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            val targetModes = listOf("One App (Game)" to 0, "Full Screen" to 1)
                            val currentTarget = targetModes.find { it.second == screenRecordTarget } ?: targetModes[0]
                            var targetDropdownExpanded by remember { mutableStateOf(false) }

                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { targetDropdownExpanded = true }.padding(vertical = 4.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Default Target", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(currentTarget.first, color = LocalPanelAccent.current, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = LocalPanelAccent.current, modifier = Modifier.size(16.dp))
                                    }
                                }
                                DropdownMenu(expanded = targetDropdownExpanded, onDismissRequest = { targetDropdownExpanded = false }) {
                                    targetModes.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.first) },
                                            onClick = {
                                                appSettings.screenRecordTargetMode = mode.second
                                                screenRecordTarget = mode.second
                                                targetDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            val audioSources = listOf("None" to 0, "Internal Audio" to 1, "Microphone" to 2, "Mic + Internal" to 3)
                            val currentAudio = audioSources.find { it.second == screenRecordAudio } ?: audioSources[1]
                            var audioDropdownExpanded by remember { mutableStateOf(false) }

                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { audioDropdownExpanded = true }.padding(vertical = 4.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Audio Source", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(currentAudio.first, color = LocalPanelAccent.current, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = LocalPanelAccent.current, modifier = Modifier.size(16.dp))
                                    }
                                }
                                DropdownMenu(expanded = audioDropdownExpanded, onDismissRequest = { audioDropdownExpanded = false }) {
                                    audioSources.forEach { source ->
                                        DropdownMenuItem(
                                            text = { Text(source.first) },
                                            onClick = {
                                                appSettings.screenRecordAudioSource = source.second
                                                screenRecordAudio = source.second
                                                audioDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            SettingToggleRow("Use HEVC (H.265)", screenRecordHEVC, onCheckedChange = {
                                appSettings.screenRecordHEVC = it
                                screenRecordHEVC = it
                            })
                            SettingToggleRow("Show Taps", screenRecordTaps, onCheckedChange = {
                                appSettings.screenRecordShowTaps = it
                                screenRecordTaps = it
                            })
                            SettingToggleRow("Low Quality", screenRecordLowQuality, onCheckedChange = {
                                appSettings.screenRecordLowQuality = it
                                screenRecordLowQuality = it
                            })
                            SettingToggleRow("Longer Duration", screenRecordLongerDuration, onCheckedChange = {
                                appSettings.screenRecordLongerDuration = it
                                screenRecordLongerDuration = it
                            })
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // ═══ ZONE 1: Quick Toggles ═══
                        Text(stringResource(R.string.quick_toggles_section), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned {
                                    dragDropState.quickZoneRect = it.boundsInWindow()
                                    dragDropState.quickZoneScroll = scrollState?.value ?: 0
                                }
                                .background(LocalPanelAccent.current.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            if (quickToggleList.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.no_quick_toggles), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                DraggableGrid(columns = 4, horizontalSpacing = 4, verticalSpacing = 4, modifier = Modifier.fillMaxWidth()) {
                                    quickToggleList.forEachIndexed { index, tile ->
                                        key(tile.id) {
                                            val isMoving = dragDropState.isMoving(tile.id)
                                            val isTarget = dragDropState.targetZone == DragZone.Quick && dragDropState.targetIndex == index && !isMoving
                                            Box(
                                                modifier = Modifier
                                                    .graphicsLayer {
                                                        if (isTarget) { scaleX = 0.85f; scaleY = 0.85f; alpha = 0.4f }
                                                        else if (isMoving) { alpha = 0f }
                                                    }
                                                    .onGloballyPositioned { layoutCoordinates ->
                                                        if (!isMoving && !isTarget) {
                                                            dragDropState.updateItemPosition(
                                                                zone = DragZone.Quick, id = tile.id, index = index,
                                                                offset = layoutCoordinates.positionInWindow().round(),
                                                                size = layoutCoordinates.size,
                                                                scrollValue = scrollState?.value ?: 0
                                                            )
                                                        }
                                                    }
                                                    .dragAndDropTile(
                                                        tileId = tile.id, label = tile.label, icon = tile.icon,
                                                        zone = DragZone.Quick, dragDropState = dragDropState, drawable = tile.drawable, group = tile.group,
                                                        getScroll = getScroll,
                                                    )
                                                    .clip(RoundedCornerShape(50))
                                                    .background(LocalPanelAccent.current)
                                            ) {
                                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).height(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(painter = painterResource(id = tile.icon), contentDescription = null, modifier = Modifier.size(13.dp), tint = readableOn(LocalPanelAccent.current))
                                                    Text(text = tile.label, color = readableOn(LocalPanelAccent.current), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // ═══ ZONE 2: Tool Grid ═══
                        Text(stringResource(R.string.tool_grid_section), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned {
                                    dragDropState.toolZoneRect = it.boundsInWindow()
                                    dragDropState.toolZoneScroll = scrollState?.value ?: 0
                                }
                                .background(LocalPanelAccent.current.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (toolTileList.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.no_tool_tiles), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                DraggableGrid(columns = 4, horizontalSpacing = 5, verticalSpacing = 4, pageBreakRows = 0, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                    toolTileList.forEachIndexed { idx, tile ->
                                        val globalIndex = idx
                                        key(tile.id) {
                                            val isMoving = dragDropState.isMoving(tile.id)
                                            val isTarget = dragDropState.targetZone == DragZone.Tool && dragDropState.targetIndex == globalIndex && !isMoving
                                            Box(
                                                modifier = Modifier
                                                    .wrapContentHeight()
                                                    .graphicsLayer {
                                                        if (isTarget) { scaleX = 0.85f; scaleY = 0.85f; alpha = 0.4f }
                                                        else if (isMoving) { alpha = 0f }
                                                    }
                                                    .onGloballyPositioned { layoutCoordinates ->
                                                        if (!isMoving && !isTarget) {
                                                            dragDropState.updateItemPosition(
                                                                zone = DragZone.Tool, id = tile.id, index = globalIndex,
                                                                offset = layoutCoordinates.positionInWindow().round(),
                                                                size = layoutCoordinates.size,
                                                                scrollValue = scrollState?.value ?: 0
                                                            )
                                                        }
                                                    }
                                                    .dragAndDropTile(
                                                        tileId = tile.id, label = tile.label, icon = tile.icon,
                                                        zone = DragZone.Tool, dragDropState = dragDropState, drawable = tile.drawable, group = tile.group,
                                                        getScroll = getScroll,
                                                    )
                                            ) {
                                                EditorGameTile(label = tile.label, icon = tile.icon, isAdded = true, drawable = tile.drawable)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // ═══ ZONE 3: Available Tiles ═══
                        Text(stringResource(R.string.available_tiles), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Drag a tile here to remove it from the panel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 9.sp,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        var searchQuery by remember { mutableStateOf("") }
                        Box(
                            modifier = Modifier.fillMaxWidth().height(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.06f)),
                        ) {
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = PanelTextPrimary, fontSize = 11.sp),
                                cursorBrush = SolidColor(LocalPanelAccent.current),
                                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                "Search tiles by name or source",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                fontSize = 10.sp,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        val query = searchQuery.trim()
                        val groupedAvailable = (if (query.isEmpty()) availableList else availableList.filter {
                            it.label.contains(query, ignoreCase = true) || it.group.contains(query, ignoreCase = true)
                        }).sortedBy { it.group }
                        val availableGroups = groupedAvailable.map { it.group }.distinct()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned {
                                    dragDropState.availableZoneRect = it.boundsInWindow()
                                    dragDropState.availableZoneScroll = scrollState?.value ?: 0
                                },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (availableList.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.all_tiles_added), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else if (groupedAvailable.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                    Text("No tiles match your search", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                availableGroups.forEach { group ->
                                    Text(
                                        text = group,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                                    )
                                    DraggableGrid(columns = 4, horizontalSpacing = 5, verticalSpacing = 4, modifier = Modifier.fillMaxWidth()) {
                                        groupedAvailable.filter { it.group == group }.forEachIndexed { index, tile ->
                                            key(tile.id) {
                                                val isMoving = dragDropState.isMoving(tile.id)
                                                val isTarget = dragDropState.targetZone == DragZone.None && dragDropState.targetIndex == index && !isMoving
                                                Box(
                                                    modifier = Modifier
                                                        .wrapContentHeight()
                                                        .graphicsLayer {
                                                            if (isTarget) { scaleX = 0.85f; scaleY = 0.85f; alpha = 0.4f }
                                                            else if (isMoving) { alpha = 0f }
                                                        }
                                                        .onGloballyPositioned { layoutCoordinates ->
                                                            if (!isMoving && !isTarget) {
                                                                dragDropState.updateItemPosition(
                                                                    zone = DragZone.None, id = tile.id, index = index,
                                                                    offset = layoutCoordinates.positionInWindow().round(),
                                                                    size = layoutCoordinates.size,
                                                                    scrollValue = scrollState?.value ?: 0
                                                                )
                                                            }
                                                        }
                                                        .dragAndDropTile(
                                                            tileId = tile.id, label = tile.label, icon = tile.icon,
                                                            zone = DragZone.None, dragDropState = dragDropState, drawable = tile.drawable, group = tile.group,
                                                            getScroll = getScroll,
                                                        )
                                                ) {
                                                    EditorGameTile(label = tile.label, icon = tile.icon, isAdded = false, drawable = tile.drawable)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    } // End scrollable column (GENERAL)
                }
                SettingsTab.MONITORS -> {
                    MonitorsTab(tileRepository)
                }
            }
        }

        // ── Floating drag overlay ──
        if (dragDropState.draggedTile != null) {
            val dragged = dragDropState.draggedTile!!
            Box(
                modifier = Modifier
                    .offset {
                        val curScroll = scrollState?.value ?: 0
                        val dy = (curScroll - boxMeasuredScroll).toFloat()
                        val liveBoxX = boxWindowPosition.x
                        val liveBoxY = boxWindowPosition.y - dy
                        val ox = dragDropState.dragPosition.x - dragDropState.grabOffset.x - liveBoxX
                        val oy = dragDropState.dragPosition.y - dragDropState.grabOffset.y - liveBoxY
                        IntOffset(ox.roundToInt(), oy.roundToInt())
                    }
                    .zIndex(100f)
                    .graphicsLayer {
                        scaleX = 1.15f
                        scaleY = 1.15f
                        shadowElevation = 24.dp.toPx()
                        spotShadowColor = Color.Black
                        ambientShadowColor = Color.Black
                    }
            ) {
                if (dragged.sourceZone == DragZone.Quick) {
                    Box(modifier = Modifier.clip(RoundedCornerShape(50)).background(LocalPanelAccent.current)) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).height(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(painter = if (dragged.drawable != null) rememberDrawablePainter(dragged.drawable) else painterResource(id = dragged.icon), contentDescription = null, modifier = Modifier.size(13.dp), tint = readableOn(LocalPanelAccent.current))
                            Text(text = dragged.label, color = readableOn(LocalPanelAccent.current), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                } else {
                    EditorGameTile(label = dragged.label, icon = dragged.icon, isAdded = dragged.sourceZone != DragZone.None, drawable = dragged.drawable)
                }
            }
        }
    }
}

@Composable
fun EditorGameTile(
    label: String,
    icon: Int,
    isAdded: Boolean,
    drawable: android.graphics.drawable.Drawable? = null,
) {
    val bgColor = if (isAdded) LocalPanelAccent.current else LocalPanelAccent.current.copy(alpha = 0.2f)
    val fgColor = if (isAdded) readableOn(LocalPanelAccent.current) else PanelTextSecondary
    Box(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 0.dp, bottom = 0.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = if (drawable != null) rememberDrawablePainter(drawable) else painterResource(icon),
                contentDescription = label,
                tint = fgColor,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = label,
                color = fgColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                lineHeight = 8.sp,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MonitorsTab(tileRepository: TileRepository) {
    val settings = tileRepository.monitorSettings
    if (settings == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Monitor service unavailable", color = PanelTheme.TextDim, fontSize = 12.sp)
        }
        return
    }

    var scenario by remember { mutableStateOf(DemoScenario.GAMING) }
    var backdrop by remember { mutableIntStateOf(0) } // 0 Dark, 1 Light, 2 Game-like
    val demoSource = rememberDemoSource(scenario)

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // ── Opacity & Global Appearance Slider ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalPanelAccent.current.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("GLOBAL MONITOR APPEARANCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Opacity", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = settings.opacity,
                    onValueChange = { settings.opacity = it },
                    valueRange = 0.1f..1.0f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text("%.2f".format(settings.opacity), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Scenario Selector & Backdrop Selector ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalPanelAccent.current.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text("PREVIEW STATE & BACKDROP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            
            // Scenario Chips (Idle, Gaming, Thermal, Charging)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                DemoScenario.values().forEach { sc ->
                    val isSel = scenario == sc
                    FilterChip(
                        selected = isSel,
                        onClick = { scenario = sc },
                        label = { Text(sc.name, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        shape = RoundedCornerShape(50)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // Backdrop Chips (Dark, Light, Game Scene)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val backdrops = listOf("Dark Game", "Light Game", "Gradient Scene")
                backdrops.forEachIndexed { idx, name ->
                    val isSel = backdrop == idx
                    FilterChip(
                        selected = isSel,
                        onClick = { backdrop = idx },
                        label = { Text(name, fontSize = 10.sp) },
                        shape = RoundedCornerShape(50)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 1. CLASSICAL MONITOR PREVIEW & TOGGLES ──
        Text("CLASSICAL MONITOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        SectionPreview(backdrop = backdrop) {
            ClassicalMonitorWidget(demoSource, settings)
        }
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.background(LocalPanelAccent.current.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(12.dp)) {
            SettingToggleRow("Show CPU", settings.classicalShowCpu, onCheckedChange = { settings.classicalShowCpu = it })
            SettingToggleRow("Show GPU", settings.classicalShowGpu, onCheckedChange = { settings.classicalShowGpu = it })
            SettingToggleRow("Show RAM", settings.classicalShowRam, onCheckedChange = { settings.classicalShowRam = it })
            SettingToggleRow("Show FPS", settings.classicalShowFps, onCheckedChange = { settings.classicalShowFps = it })
            SettingToggleRow("Show Temperature & Power", settings.classicalShowTemp, onCheckedChange = { settings.classicalShowTemp = it })
        }

        Spacer(Modifier.height(14.dp))

        // ── 2. MINI MONITOR PREVIEW & TOGGLES ──
        Text("MINI MONITOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        SectionPreview(backdrop = backdrop) {
            MiniMonitorWidget(demoSource, settings)
        }
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.background(LocalPanelAccent.current.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(12.dp)) {
            SettingToggleRow("Show CPU", settings.miniShowCpu, onCheckedChange = { settings.miniShowCpu = it })
            SettingToggleRow("Show GPU", settings.miniShowGpu, onCheckedChange = { settings.miniShowGpu = it })
            SettingToggleRow("Show Battery/FPS", settings.miniShowFps, onCheckedChange = { settings.miniShowFps = it })
            SettingToggleRow("Show Temperature", settings.miniShowRam, onCheckedChange = { settings.miniShowRam = it })
        }

        Spacer(Modifier.height(14.dp))

        // ── 3. TEMPERATURE MONITOR PREVIEW & TOGGLES ──
        Text("TEMPERATURE MONITOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        SectionPreview(backdrop = backdrop) {
            TempMonitorWidget(demoSource, settings)
        }
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.background(LocalPanelAccent.current.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(12.dp)) {
            SettingToggleRow("Show CPU Temperature", settings.tempShowCpu, onCheckedChange = { settings.tempShowCpu = it })
            SettingToggleRow("Show GPU Temperature", settings.tempShowGpu, onCheckedChange = { settings.tempShowGpu = it })
            SettingToggleRow("Show Battery Temperature", settings.tempShowBattery, onCheckedChange = { settings.tempShowBattery = it })
        }

        Spacer(Modifier.height(14.dp))

        // ── 4. PROCESSES MONITOR PREVIEW & TOGGLES ──
        Text("PROCESSES MONITOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        SectionPreview(backdrop = backdrop) {
            ProcessesMonitorWidget(demoSource, settings)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Frame yang meniru LATAR game untuk menguji opacity & kontras widget 1:1 */
@Composable
private fun SectionPreview(backdrop: Int, content: @Composable () -> Unit) {
    val bgModifier = when (backdrop) {
        1 -> Modifier.background(Color(0xFFD7DEE6))
        2 -> Modifier.background(
            Brush.linearGradient(
                colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
            )
        )
        else -> Modifier.background(Color(0xFF0B0E14))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(bgModifier)
            .padding(12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        content()
    }
}

// ══════════════════════════════════════════════════════════════════
// Compact accent picker: presets + custom (inline HSV, no dialog)
// ══════════════════════════════════════════════════════════════════
@Composable
private fun PanelColorRow(tileRepository: TileRepository) {
    val mode = tileRepository.panelColorMode.intValue
    val customArgb = tileRepository.panelCustomColor.intValue
    var pickerOpen by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        // Material You
        AccentDot(
            fill = MaterialTheme.colorScheme.primary, selected = mode == 0, label = "M",
            onClick = { tileRepository.setPanelColorMode(0); pickerOpen = false },
        )
        // Presets
        ACCENT_PRESETS.forEachIndexed { index, color ->
            val m = index + 1
            AccentDot(
                fill = color, selected = mode == m,
                onClick = { tileRepository.setPanelColorMode(m); pickerOpen = false },
            )
        }
        // Custom — rainbow ring = "pick any color"
        CustomAccentDot(
            argb = customArgb, selected = mode == PANEL_COLOR_MODE_CUSTOM,
            onClick = {
                tileRepository.setPanelColorMode(PANEL_COLOR_MODE_CUSTOM)
                pickerOpen = !pickerOpen
            },
        )
    }

    AnimatedVisibility(
        visible = pickerOpen && mode == PANEL_COLOR_MODE_CUSTOM,
        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(140)) + fadeOut(tween(140)),
    ) {
        CustomColorPicker(
            initialArgb = customArgb,
            onColor = { argb, persist -> tileRepository.setPanelCustomColor(argb, persist) },
        )
    }
}

@Composable
private fun AccentDot(fill: Color, selected: Boolean, label: String? = null, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp).clip(CircleShape).background(fill)
            .border(
                width = if (selected) 2.dp else 0.5.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.18f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CustomAccentDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp).clip(CircleShape)
            .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
            .border(
                width = if (selected) 2.dp else 0.5.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.18f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(15.dp).clip(CircleShape)
                .background(Color(argb))
                .border(0.5.dp, Color.Black.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.materialsymbols_ic_palette_rounded_filled),
                contentDescription = null, tint = Color.White, modifier = Modifier.size(9.dp),
            )
        }
    }
}

@Composable
private fun CustomColorPicker(initialArgb: Int, onColor: (Int, Boolean) -> Unit) {
    val initHsv = remember { FloatArray(3).also { AndroidColor.colorToHSV(initialArgb, it) } }
    var h by remember { mutableFloatStateOf(initHsv[0]) }
    var s by remember { mutableFloatStateOf(initHsv[1]) }
    var v by remember { mutableFloatStateOf(initHsv[2]) }
    var hex by remember { mutableStateOf("#%06X".format(0xFFFFFF and initialArgb)) }

    fun apply(persist: Boolean) {
        val argb = AndroidColor.HSVToColor(floatArrayOf(h, s, v))
        hex = "#%06X".format(0xFFFFFF and argb)
        onColor(argb, persist) // live recolor; persist on release
    }

    val hueRainbow = remember {
        Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
    }
    val satBrush = Brush.horizontalGradient(listOf(
        Color(AndroidColor.HSVToColor(floatArrayOf(h, 0f, v))),
        Color(AndroidColor.HSVToColor(floatArrayOf(h, 1f, v))),
    ))
    val valBrush = Brush.horizontalGradient(listOf(
        Color(0xFF000000),
        Color(AndroidColor.HSVToColor(floatArrayOf(h, s, 1f))),
    ))

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Preview + hex
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Box(
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(AndroidColor.HSVToColor(floatArrayOf(h, s, v))))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = hex,
                onValueChange = { new ->
                    hex = new
                    runCatching { AndroidColor.parseColor(if (new.startsWith("#")) new else "#$new") }
                        .getOrNull()?.let { c ->
                            val t = FloatArray(3); AndroidColor.colorToHSV(c, t)
                            h = t[0]; s = t[1]; v = t[2]; onColor(c, true)
                        }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = PanelTextPrimary, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                ),
                modifier = Modifier.weight(1f).height(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 8.dp),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { inner() } },
            )
        }
        GradientSlider(h, 0f..360f, hueRainbow, "H", { h = it; apply(false) }) { apply(true) }
        GradientSlider(s, 0f..1f, satBrush, "S", { s = it; apply(false) }) { apply(true) }
        GradientSlider(v, 0f..1f, valBrush, "V", { v = it; apply(false) }) { apply(true) }
    }
}

@Composable
private fun GradientSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, track: Brush, letter: String,
    onChange: (Float) -> Unit, onChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(letter, color = PanelTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f).height(18.dp)) {
            Box(modifier = Modifier.matchParentSize().padding(vertical = 6.dp).clip(RoundedCornerShape(3.dp)).background(track))
            Slider(
                value = value, onValueChange = onChange, valueRange = range,
                onValueChangeFinished = onChangeFinished,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth().height(18.dp),
            )
        }
    }
}
