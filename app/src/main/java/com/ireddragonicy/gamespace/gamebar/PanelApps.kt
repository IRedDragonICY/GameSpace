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

private val TAG = "GamePanelCard"


@Composable
fun VerticalAppSidebar(
    apps: List<AppInfo>,
    tileRepository: TileRepository,
    maxHeight: Dp = 520.dp,
    isRecording: Boolean = false,
    onRecordClick: () -> Unit = {},
    onAppsChanged: ((List<String>) -> Unit)? = null,
    onKeyboardFocusChanged: (Boolean) -> Unit = {},
    isBubbleMode: Boolean = false,
    onToggleBubbleMode: (Boolean) -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    val sidebarApps = remember { mutableStateListOf<AppInfo>().apply { addAll(apps) } }
    val haptic = LocalHapticFeedback.current
    val panelAccent = rememberPanelAccent(tileRepository.panelColorMode.intValue)

    // Log sidebar state for debugging
    LaunchedEffect(apps.size) {
        android.util.Log.d("GameSidebar", "VerticalAppSidebar: received ${apps.size} apps")
    }

    // Animated transition between sidebar and app picker
    AnimatedContent(
        targetState = showAppPicker,
        modifier = Modifier.fillMaxHeight(),
        transitionSpec = {
            if (targetState) {
                // Sidebar → Picker: slide in from right + fade
                (slideInHorizontally { it / 3 } + fadeIn(tween(250)))
                    .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(200)))
            } else {
                // Picker → Sidebar: slide back from left + fade
                (slideInHorizontally { -it / 3 } + fadeIn(tween(250)))
                    .togetherWith(slideOutHorizontally { it / 3 } + fadeOut(tween(200)))
            }
        },
        label = "sidebar_picker_transition",
    ) { isPicker ->
        if (isPicker) {
            InlineAppPicker(
                currentApps = sidebarApps.map { it.packageName }.toSet(),
                maxHeight = maxHeight,
                onAppSelected = { pkg ->
                    try {
                        val pm = context.packageManager
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        sidebarApps.add(AppInfo(name = name, icon = icon, packageName = pkg))
                        onAppsChanged?.invoke(sidebarApps.map { it.packageName })
                    } catch (_: Exception) {}
                },
                onDismiss = {
                    onKeyboardFocusChanged(false)
                    showAppPicker = false
                },
                onKeyboardFocusChanged = onKeyboardFocusChanged,
            )
        } else {
            Column(
                modifier = Modifier
                    .width(52.dp)
                    .fillMaxHeight()
                    .background(
                        panelAccent.copy(alpha = 0.06f),
                        chamferShape(bigCut = 12.dp, smallCut = 5.dp),
                    )
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Session timer
                val sessionElapsed = rememberSessionTimer(tileRepository.sessionStartTimeMs)
                Text(
                    text = sessionElapsed,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

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

                // Record button capsule — the timer is derived from the SYSTEM
                // recorder's start timestamp (broadcast by SystemUI over AxPlatform),
                // not a local counter: it cannot drift, and survives panel reopen.
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

                    sidebarApps.forEachIndexed { index, app ->
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
                                                if (kotlin.math.abs(dragStartOffset) > 100f) {
                                                    sidebarApps.removeAt(index)
                                                    onAppsChanged?.invoke(sidebarApps.map { it.packageName })
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
                                                    onAppsChanged?.invoke(sidebarApps.map { it.packageName })
                                                } else if (dragOffset < -itemHeight * 0.5f && prevIndex >= 0) {
                                                    java.util.Collections.swap(sidebarApps, currentIndex, prevIndex)
                                                    draggingIndex = prevIndex
                                                    dragOffset += itemHeight
                                                    onAppsChanged?.invoke(sidebarApps.map { it.packageName })
                                                }
                                            }
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            QuickStartAppIcon(
                                appInfo = app,
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (!isEditing) {
                                            if (isBubbleMode) {
                                                launchAppInBubbleMode(context, app.packageName)
                                            } else {
                                                launchAppInFreeformMode(context, app.packageName)
                                            }
                                        }
                                    },
                                    onLongClick = { isEditing = !isEditing }
                                )
                            )
                        }
                    }

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
                } // End of scrollable column
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
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current

    // Filter state: 0 = All, 1 = User, 2 = System
    var filterMode by remember { mutableIntStateOf(0) }

    // Load all launchable apps with system/user flag
    data class PickerApp(
        val info: AppInfo,
        val isSystemApp: Boolean,
    )

    val allApps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        resolveInfos
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString()
                val icon = ri.loadIcon(pm)
                val isSystem = (ri.activityInfo.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                PickerApp(
                    info = AppInfo(name = label, icon = icon, packageName = pkg),
                    isSystemApp = isSystem
                )
            }
            .distinctBy { it.info.packageName }
            .sortedBy { it.info.name.lowercase() }
    }

    // Filter by search, exclude already-added, and apply system/user filter
    val filteredApps = remember(searchQuery, currentApps, filterMode) {
        allApps.filter { app ->
            app.info.packageName !in currentApps &&
            app.info.packageName != context.packageName &&
            (searchQuery.isEmpty() || app.info.name.contains(searchQuery, ignoreCase = true)) &&
            when (filterMode) {
                1 -> !app.isSystemApp  // User apps only
                2 -> app.isSystemApp   // System apps only
                else -> true           // All
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
                                    onAppSelected(app.info.packageName)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // App icon with subtle shadow
                            Image(
                                painter = rememberDrawablePainter(app.info.icon),
                                contentDescription = app.info.name,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            // App name
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.info.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PanelTextPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // System/User label
                                Text(
                                    text = if (app.isSystemApp) "System" else "User",
                                    color = if (app.isSystemApp) PanelTextSecondary.copy(alpha = 0.5f)
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

private val drawablePainterCache = LruCache<Int, Painter>(100)

@Composable
fun rememberDrawablePainter(drawable: Drawable?): Painter {
    return remember(drawable?.hashCode()) {
        drawable?.let {
            val key = it.hashCode()
            drawablePainterCache.get(key) ?: run {
                val painter = it.toPainter()
                drawablePainterCache.put(key, painter)
                painter
            }
        } ?: ColorPainter(Color.Gray)
    }
}

fun Drawable.toPainter(): Painter {
    return try {
        BitmapPainter(this.toBitmap(width = 96, height = 96).asImageBitmap())
    } catch (_: Exception) {
        ColorPainter(Color.Gray)
    }
}

@Composable
fun QuickStartAppIcon(
    appInfo: AppInfo,
    modifier: Modifier = Modifier
) {
    val painter = rememberDrawablePainter(appInfo.icon)
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painter,
            contentDescription = appInfo.name,
        )
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



data class AppInfo(
    val name: String,
    val icon: Drawable,
    val packageName: String
)
