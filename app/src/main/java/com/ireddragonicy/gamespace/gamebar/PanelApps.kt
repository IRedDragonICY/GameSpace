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
import androidx.compose.material.icons.rounded.PushPin
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
import com.ireddragonicy.gamespace.gamebar.SidebarMode
import com.ireddragonicy.gamespace.R

import com.ireddragonicy.gamespace.gamebar.brightness.*
import com.ireddragonicy.gamespace.gamebar.fps.*
import com.ireddragonicy.gamespace.gamebar.tiles.*
import com.ireddragonicy.gamespace.settings.PerAppSettingsActivity
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.data.model.AppRef
import com.ireddragonicy.gamespace.utils.rememberInstalledAppsRepository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale


import com.ireddragonicy.gamespace.data.LabelMode
import com.ireddragonicy.gamespace.data.SidebarStyle
import com.ireddragonicy.gamespace.data.StripButtonSpec
import com.ireddragonicy.gamespace.data.StripLayout
import com.ireddragonicy.gamespace.data.StripPosition
import com.ireddragonicy.gamespace.settings.sidebarstudio.containerShape
import com.ireddragonicy.gamespace.settings.sidebarstudio.iconShapeShape
import com.ireddragonicy.gamespace.settings.sidebarstudio.resolveSidebarAccent
import com.ireddragonicy.gamespace.utils.rememberDrawablePainter

@Composable
fun VerticalAppSidebar(
    items: List<DockItem>,
    tileRepository: TileRepository,
    style: SidebarStyle = SidebarStyle.DEFAULT,
    maxHeight: Dp = 520.dp,
    sidebarMode: Int = SidebarMode.MODE_GAME,
    isRecording: Boolean = false,
    onRecordClick: () -> Unit = {},
    onDockEdited: ((pinned: List<String>, newlyHidden: Set<String>) -> Unit)? = null,
    onAddApp: ((String) -> Unit)? = null,
    isHiddenApp: (String) -> Boolean = { false },
    onKeyboardFocusChanged: (Boolean) -> Unit = {},
    isBubbleMode: Boolean = false,
    onToggleBubbleMode: (Boolean) -> Unit = {},
) {
    // Session timer and network ping are gaming telemetry: the ping monitor is not even
    // started outside a game session, so showing the badge elsewhere would pin it at "--".
    val showGameStats = sidebarMode == SidebarMode.MODE_GAME
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    // Local editable mirror of the dock. Synced from the provider flow on
    // every emission — EXCEPT while the user is editing or picking, so a
    // background refresh never yanks an icon out of their hand.
    val sidebarApps = remember { mutableStateListOf<DockItem>().apply { addAll(items) } }
    LaunchedEffect(items) {
        if (isEditing || showAppPicker) return@LaunchedEffect
        val same = items.size == sidebarApps.size && items.indices.all {
            items[it].info.packageName == sidebarApps[it].info.packageName &&
                items[it].pinned == sidebarApps[it].pinned
        }
        if (!same) {
            sidebarApps.clear()
            sidebarApps.addAll(items)
        }
    }

    val haptic = LocalHapticFeedback.current
    val panelAccent = rememberPanelAccent(
        tileRepository.panelColorMode.intValue,
        tileRepository.panelCustomColor.intValue
    )
    val stripAccent = resolveSidebarAccent(style)
    val visible = StripButtonSpec.visible(style)

    // Apps dragged out during this edit session — committed with every change.
    val pendingHidden = remember { mutableStateListOf<String>() }

    fun commitDock() {
        onDockEdited?.invoke(
            sidebarApps.filter { it.pinned }.map { it.info.packageName },
            pendingHidden.toSet(),
        )
        pendingHidden.clear()
    }

    when (style.layout) {
        StripLayout.BOTTOM -> {
            HorizontalAppStrip(
                items = items, sidebarApps = sidebarApps, style = style,
                accent = stripAccent, tileRepository = tileRepository,
                showGameStats = showGameStats, isRecording = isRecording,
                onRecordClick = onRecordClick, isBubbleMode = isBubbleMode,
                onToggleBubbleMode = onToggleBubbleMode,
                isEditing = isEditing, onToggleEdit = { isEditing = !isEditing },
                onShowAppPicker = { showAppPicker = true },
                onDockEdited = onDockEdited, pendingHidden = pendingHidden,
            )
            return
        }
        StripLayout.VERTICAL -> { }
    }

    // Animated transition between sidebar and app picker
    AnimatedContent(
        targetState = showAppPicker,
        modifier = Modifier.fillMaxHeight(),
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally { it / 3 } + fadeIn(tween(250)))
                    .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(200)))
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn(tween(250)))
                    .togetherWith(slideOutHorizontally { it / 3 } + fadeOut(tween(200)))
            }
        },
        label = "sidebar_picker_transition",
    ) { isPicker ->
        if (isPicker) {
            InlineAppPicker(
                currentApps = sidebarApps.map { it.info.packageName }.toSet(),
                maxHeight = maxHeight,
                isHiddenApp = isHiddenApp,
                onAppSelected = { pkg ->
                    onAddApp?.invoke(pkg)
                },
                onDismiss = {
                    onKeyboardFocusChanged(false)
                    showAppPicker = false
                },
                onKeyboardFocusChanged = onKeyboardFocusChanged,
            )
        } else {
            Box(
                modifier = Modifier
                    .width(style.stripWidthDp.dp)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .width(style.stripWidthDp.dp)
                        .fillMaxHeight()
                        .clip(style.containerShape())
                        .background(
                            stripAccent.copy(alpha = style.bgAlpha / 100f),
                            style.containerShape(),
                        )
                        .padding(vertical = style.verticalPaddingDp.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(style.itemSpacingDp.dp),
                ) {
                if (showGameStats && visible.any { it.id == StripButtonSpec.ID_TIMER }) {
                    // Session timer
                    val sessionElapsed = rememberSessionTimer(tileRepository.sessionStartTimeMs)
                    Text(
                        text = sessionElapsed,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                if (showGameStats && visible.any { it.id == StripButtonSpec.ID_PING }) {
                    // Network ping badge
                    val pingMs = tileRepository.pingLatencyMs.value
                    val pingColor = when {
                        pingMs < 0 -> Color.Gray
                        pingMs < 50 -> Color(0xFF00E676)
                        pingMs < 100 -> Color(0xFFFFEB3B)
                        pingMs < 200 -> Color(0xFFFF9800)
                        else -> Color(0xFFFF1744)
                    }

                    Text(
                        text = if (pingMs < 0) "--" else "${pingMs}ms",
                        color = pingColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { tileRepository.showPingDetails.value = !tileRepository.showPingDetails.value }
                            .padding(vertical = 4.dp)
                    )
                }

                if (visible.any { it.id == StripButtonSpec.ID_RECORD }) {
                    // Record button capsule
                    val recordStartMs = tileRepository.screenRecordingStartElapsedMs.value
                    var recordingElapsed by remember { mutableIntStateOf(0) }
                    LaunchedEffect(isRecording, recordStartMs) {
                        if (isRecording && recordStartMs > 0L) {
                            while (true) {
                                recordingElapsed =
                                    ((android.os.SystemClock.elapsedRealtime() - recordStartMs) / 1000L)
                                        .coerceAtLeast(0L).toInt()
                                kotlinx.coroutines.delay(250)
                            }
                        } else {
                            recordingElapsed = 0
                        }
                    }

                    Column(
                        modifier = Modifier
                            .width(44.dp)
                            .clip(RoundedCornerShape(50)) // Pill shape vertically
                            .background(
                                if (isRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                else Color.White.copy(alpha = 0.1f)
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onRecordClick()
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Top half: Icon
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.materialsymbols_ic_videocam_rounded_filled),
                                contentDescription = "Record",
                                tint = if (isRecording) MaterialTheme.colorScheme.onError else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        // Bottom half: Duration (only if recording)
                        if (isRecording) {
                            val m = recordingElapsed / 60
                            val s = recordingElapsed % 60
                            Text(
                                text = String.format("%02d:%02d", m, s),
                                color = MaterialTheme.colorScheme.onError,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }

                if (visible.any { it.id == StripButtonSpec.ID_BUBBLE }) {
                    // Bubble vs Freeform Toggle Button
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (isBubbleMode) Color(0xFF00E676).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f))
                            .clickable { onToggleBubbleMode(!isBubbleMode) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = if (isBubbleMode) R.drawable.materialsymbols_ic_bubble_rounded_filled else R.drawable.materialsymbols_ic_select_window_rounded_filled),
                            contentDescription = if (isBubbleMode) "Launch in Bubble" else "Launch in Freeform",
                            tint = if (isBubbleMode) Color(0xFF00E676) else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    var draggingIndex by remember { mutableStateOf<Int?>(null) }
                    var dragOffset by remember { mutableStateOf(0f) }
                    var dragStartOffset by remember { mutableStateOf(0f) }
                    val itemHeight = with(LocalDensity.current) { 46.dp.toPx() } // 40dp icon + 6dp spacing

                    sidebarApps.forEachIndexed { index, item ->
                        val isDragging = draggingIndex == index
                        val offset = if (isDragging) dragOffset else 0f
                        val offsetX = if (isDragging) dragStartOffset else 0f

                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = offset
                                    translationX = offsetX
                                    alpha = if (isDragging && kotlin.math.abs(offsetX) > 100f) 0.3f else 1f
                                }
                                .pointerInput(isEditing) {
                                    if (isEditing) {
                                        detectDragGestures(
                                            onDragStart = { draggingIndex = index },
                                            onDragEnd = {
                                                val dragged = draggingIndex
                                                if (dragged != null) {
                                                    if (kotlin.math.abs(dragStartOffset) > 100f) {
                                                        pendingHidden += sidebarApps[dragged].info.packageName
                                                        sidebarApps.removeAt(dragged)
                                                    } else {
                                                        sidebarApps[dragged] =
                                                            sidebarApps[dragged].copy(pinned = true)
                                                    }
                                                    commitDock()
                                                }
                                                draggingIndex = null
                                                dragOffset = 0f
                                                dragStartOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggingIndex = null
                                                dragOffset = 0f
                                                dragStartOffset = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount.y
                                                dragStartOffset += dragAmount.x
                                                val currentIndex = draggingIndex ?: return@detectDragGestures
                                                val nextIndex = currentIndex + 1
                                                val prevIndex = currentIndex - 1
                                                if (dragOffset > itemHeight * 0.5f && nextIndex < sidebarApps.size) {
                                                    java.util.Collections.swap(sidebarApps, currentIndex, nextIndex)
                                                    draggingIndex = nextIndex
                                                    dragOffset -= itemHeight
                                                } else if (dragOffset < -itemHeight * 0.5f && prevIndex >= 0) {
                                                    java.util.Collections.swap(sidebarApps, currentIndex, prevIndex)
                                                    draggingIndex = prevIndex
                                                    dragOffset += itemHeight
                                                }
                                            }
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            QuickStartAppIcon(
                                appInfo = item.info,
                                iconShape = style.iconShapeShape(),
                                iconSize = style.iconSizeDp.dp,
                                showLabel = style.labelMode == LabelMode.INLINE,
                                labelSize = style.labelSizeSp.sp,
                                idleAlpha = style.idleAlpha / 100f,
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (isEditing) {
                                            sidebarApps[index] = item.copy(pinned = !item.pinned)
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            commitDock()
                                        } else {
                                            if (isBubbleMode) {
                                                launchAppInBubbleMode(context, item.info.packageName)
                                            } else {
                                                launchAppInFreeformMode(context, item.info.packageName)
                                            }
                                        }
                                    },
                                    onLongClick = { isEditing = !isEditing }
                                )
                            )
                            if (isEditing) {
                                if (item.pinned) {
                                    Icon(
                                        imageVector = Icons.Rounded.PushPin,
                                        contentDescription = "Pinned",
                                        tint = Color.Black,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(panelAccent)
                                            .padding(2.5.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .border(1.dp, panelAccent.copy(alpha = 0.55f), CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }

                if (visible.any { it.id == StripButtonSpec.ID_ADD }) {
                    // Add button with haptic + scale animation
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(LocalPanelAccent.current.copy(alpha = 0.2f))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showAppPicker = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.sidebar_add_app),
                            tint = LocalPanelAccent.current,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            EdgeChrome(
                style = style,
                accent = stripAccent,
                shape = style.containerShape(),
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
}


/**
 * Inline app picker — renders directly in the overlay panel (no Dialog).
 * This avoids BadTokenException since GameSpace runs as a Service overlay without an Activity.
 *
 * Features:
 * - Search bar with keyboard support (FocusRequester + SoftwareKeyboardController)
 * - System/User app filter chips
 * - Material You dark gaming design
 * - Smooth entry animations for list items
 */
@Composable
fun InlineAppPicker(
    currentApps: Set<String>,
    maxHeight: Dp,
    onAppSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit = {},
    isHiddenApp: (String) -> Boolean = { false },
) {
    val context = LocalContext.current
    val repo = rememberInstalledAppsRepository()
    val haptic = LocalHapticFeedback.current
    // Filter state: 0 = All, 1 = User, 2 = System
    var filterMode by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // ── Load OFF the main thread via the shared cached repository ──
    var allApps by remember { mutableStateOf<List<AppRef>>(emptyList()) }
    LaunchedEffect(Unit) { allApps = repo.launchableApps() }

    // Filter by search, exclude already-added, and apply system/user filter
    val filteredApps = remember(searchQuery, currentApps, filterMode, allApps) {
        allApps.filter { app ->
            app.packageName !in currentApps &&
            app.packageName != context.packageName &&
            (searchQuery.isEmpty() || app.label.contains(searchQuery, ignoreCase = true)) &&
            when (filterMode) {
                1 -> !app.isSystem
                2 -> app.isSystem
                else -> true
            }
        }
    }

    Surface(
        modifier = Modifier
            .width(280.dp)
            .heightIn(max = maxHeight),
        shape = RoundedCornerShape(20.dp),
        color = PanelBackground.copy(alpha = 0.96f),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            // Header with back button + title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = PanelTextPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.sidebar_add_app),
                    style = MaterialTheme.typography.titleSmall,
                    color = PanelTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                // App count badge
                Text(
                    text = "${filteredApps.size}",
                    color = LocalPanelAccent.current,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(LocalPanelAccent.current.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search bar — styled for gaming overlay with keyboard support
            val keyboardController = LocalSoftwareKeyboardController.current
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default.copy(
                    imeAction = ImeAction.Search,
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = PanelTextPrimary,
                    fontSize = 13.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            // First make the overlay window focusable, then show keyboard
                            onKeyboardFocusChanged(true)
                            keyboardController?.show()
                        }
                    },
                decorationBox = { innerTextField ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.materialsymbols_ic_search_rounded_filled),
                            contentDescription = null,
                            tint = PanelTextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.sidebar_search_apps),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelTextSecondary.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                )
                            }
                            innerTextField()
                        }
                        // Clear button
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                painter = painterResource(R.drawable.materialsymbols_ic_close_rounded_filled),
                                contentDescription = null,
                                tint = PanelTextSecondary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { searchQuery = "" },
                            )
                        }
                    }
                },
                cursorBrush = SolidColor(LocalPanelAccent.current),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Filter chips: All / User / System
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("All" to 0, "User" to 1, "System" to 2).forEach { (label, mode) ->
                    val isSelected = filterMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) LocalPanelAccent.current.copy(alpha = 0.15f)
                                else Color.White.copy(alpha = 0.04f)
                            )
                            .border(
                                width = if (isSelected) 1.dp else 0.5.dp,
                                color = if (isSelected) LocalPanelAccent.current.copy(alpha = 0.5f)
                                else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                filterMode = mode
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) LocalPanelAccent.current else PanelTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // App list with LazyColumn for performance
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                if (filteredApps.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No apps found"
                                   else "All apps added",
                            color = PanelTextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    filteredApps.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onAppSelected(app.packageName)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // App icon with subtle shadow
                            Image(
                                painter = rememberDrawablePainter(app.icon),
                                contentDescription = app.label,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            // App name
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelTextPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // System/User label (+ hidden hint)
                                val hidden = isHiddenApp(app.packageName)
                                Text(
                                    text = buildString {
                                        append(if (app.isSystem) "System" else "User")
                                        if (hidden) append(" · hidden — adding unpins it back")
                                    },
                                    color = if (hidden) Color(0xFFFFA726)
                                            else if (app.isSystem) PanelTextSecondary.copy(alpha = 0.5f)
                                            else LocalPanelAccent.current.copy(alpha = 0.5f),
                                    fontSize = 9.sp,
                                )
                            }
                            // Add button
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(LocalPanelAccent.current.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = LocalPanelAccent.current,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}



@Composable
fun QuickStartAppIcon(
    appInfo: AppRef,
    iconShape: Shape = CircleShape,
    iconSize: Dp = 40.dp,
    showLabel: Boolean = false,
    labelSize: TextUnit = 9.sp,
    idleAlpha: Float = 1.0f,
    modifier: Modifier = Modifier
) {
    val painter = rememberDrawablePainter(appInfo.icon)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Image(
            painter = painter,
            contentDescription = appInfo.label,
            modifier = Modifier
                .size(iconSize)
                .alpha(idleAlpha)
                .clip(iconShape),
        )
        if (showLabel) {
            Spacer(Modifier.height(2.dp))
            Text(
                appInfo.label,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = labelSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(iconSize + 8.dp),
            )
        }
    }
}

@Composable
fun HorizontalAppStrip(
    items: List<DockItem>,
    sidebarApps: List<DockItem>,
    style: SidebarStyle,
    accent: Color,
    tileRepository: TileRepository,
    showGameStats: Boolean,
    isRecording: Boolean,
    onRecordClick: () -> Unit,
    isBubbleMode: Boolean,
    onToggleBubbleMode: (Boolean) -> Unit,
    isEditing: Boolean,
    onToggleEdit: () -> Unit,
    onShowAppPicker: () -> Unit,
    onDockEdited: ((List<String>, Set<String>) -> Unit)?,
    pendingHidden: List<String>,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val visible = StripButtonSpec.visible(style)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(style.stripWidthDp.dp)
                .clip(style.containerShape())
                .background(accent.copy(alpha = style.bgAlpha / 100f))
                .padding(horizontal = style.verticalPaddingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(style.itemSpacingDp.dp),
        ) {
            if (showGameStats && visible.any { it.id == StripButtonSpec.ID_TIMER }) {
                Text(rememberSessionTimer(tileRepository.sessionStartTimeMs),
                    color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            if (showGameStats && visible.any { it.id == StripButtonSpec.ID_PING }) {
                val pingMs = tileRepository.pingLatencyMs.value
                Text(if (pingMs < 0) "--" else "${pingMs}ms",
                    color = if (pingMs < 0) Color.Gray else Color(0xFF00E676),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        tileRepository.showPingDetails.value =
                            !tileRepository.showPingDetails.value
                    })
            }
            if (visible.any { it.id == StripButtonSpec.ID_RECORD }) {
                StripCircleButton(
                    icon = R.drawable.materialsymbols_ic_videocam_rounded_filled,
                    active = isRecording, accent = accent, onClick = onRecordClick,
                    onLongClick = { tileRepository.showScreenRecordChooser.value = true },
                )
            }
            if (visible.any { it.id == StripButtonSpec.ID_BUBBLE }) {
                StripCircleButton(
                    icon = if (isBubbleMode)
                        R.drawable.materialsymbols_ic_bubble_rounded_filled
                    else R.drawable.materialsymbols_ic_select_window_rounded_filled,
                    active = isBubbleMode, accent = accent,
                    onClick = { onToggleBubbleMode(!isBubbleMode) },
                )
            }

            Row(
                modifier = Modifier.weight(1f).horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(style.itemSpacingDp.dp),
            ) {
                sidebarApps.forEach { item ->
                    QuickStartAppIcon(
                        appInfo = item.info,
                        iconShape = style.iconShapeShape(),
                        iconSize = style.iconSizeDp.dp,
                        showLabel = style.labelMode == LabelMode.INLINE,
                        labelSize = style.labelSizeSp.sp,
                        idleAlpha = style.idleAlpha / 100f,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (isBubbleMode) {
                                    launchAppInBubbleMode(context, item.info.packageName)
                                } else {
                                    launchAppInFreeformMode(context, item.info.packageName)
                                }
                            },
                            onLongClick = onToggleEdit,
                        ),
                    )
                }
            }

            if (visible.any { it.id == StripButtonSpec.ID_ADD }) {
                StripCircleButton(
                    icon = R.drawable.materialsymbols_ic_add_rounded_filled,
                    active = false, accent = accent, onClick = onShowAppPicker,
                )
            }
        }
        EdgeChrome(
            style = style,
            accent = accent,
            shape = style.containerShape(),
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun StripCircleButton(
    icon: Int, active: Boolean, accent: Color, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                else Color.White.copy(alpha = 0.10f)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null,
            tint = if (active) MaterialTheme.colorScheme.onError else accent,
            modifier = Modifier.size(20.dp))
    }
}

fun launchAppInBubbleMode(context: Context, packageName: String) {
    try {
        val intent = android.content.Intent("com.ireddragonicy.gamespace.ACTION_SHOW_APP_BUBBLE")
        intent.putExtra("package_name", packageName)
        // Send to SystemUI
        intent.setPackage("com.android.systemui")
        context.sendBroadcast(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}


fun launchAppInFreeformMode(context: Context, packageName: String) {
    try {
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        val tasks = am.getRunningTasks(10)
        val taskId = tasks.firstOrNull { it.topActivity?.packageName == packageName }?.taskId ?: -1

        // Infinity X HyperOS freeform: the task's logical bounds are ALWAYS the portrait display
        // size — the app is configured exactly like fullscreen and WM Shell scales the surface
        // down (zoom out). Passing the bounds here means the window never opens oversized and
        // core WM never restores stale, cramped bounds.
        val metrics = context.resources.displayMetrics
        val portraitW = minOf(metrics.widthPixels, metrics.heightPixels)
        val portraitH = maxOf(metrics.widthPixels, metrics.heightPixels)
        val options = android.app.ActivityOptions.makeCustomAnimation(context, 0, 0)
        options.launchWindowingMode = android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
        options.launchBounds = android.graphics.Rect(0, 0, portraitW, portraitH)

        if (taskId != -1) {
            // Resize the existing task to the portrait-display bounds BEFORE it becomes
            // visible in freeform. Otherwise it appears with its stale (smaller) bounds for a
            // few frames and the content shows cropped while WM Shell normalizes it.
            try {
                android.app.ActivityTaskManager.getService().resizeTask(
                    taskId,
                    android.graphics.Rect(0, 0, portraitW, portraitH),
                    android.app.ActivityTaskManager.RESIZE_MODE_SYSTEM
                )
            } catch (e: Exception) {
                Log.w(TAG, "resizeTask before freeform launch failed", e)
            }
            android.app.ActivityTaskManager.getService().startActivityFromRecents(taskId, options.toBundle())
            Log.i(TAG, "Launched $packageName into native AOSP Freeform mode via Recents")
        } else {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            context.startActivity(launchIntent, options.toBundle())
            Log.i(TAG, "Started $packageName in native AOSP freeform mode via Intent")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to launch in freeform", e)
    }
}



private const val TAG = "GamePanelCard"
