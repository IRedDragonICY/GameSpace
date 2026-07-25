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

// Thermal profile catalog — single source of truth lives in
// com.ireddragonicy.gamespace.thermal.ThermalProfiles (shared with the
// in-app ThermalController that replaced the framework MiThermalService).
internal val THERMAL_PROFILE_NAMES = ThermalProfiles.PROFILE_NAMES
internal val THERMAL_PROFILE_KEY = ThermalProfiles.KEY_APP_PROFILES
internal val THERMAL_CUSTOM_PROFILES_KEY = ThermalProfiles.KEY_CUSTOM_PROFILES
internal val CUSTOM_PROFILE_BASE = ThermalProfiles.CUSTOM_PROFILE_BASE

fun getThermalProfileColor(index: Int): Color = when {
    index == 0 -> Color(0xFF64B5F6)   // Auto — blue
    index == 1 -> Color(0xFF90A4AE)   // Default — grey
    index == 2 -> Color(0xFFEF5350)   // Performance — red
    index == 3 -> Color(0xFFFFA726)   // Balanced Gaming — orange
    index == 4 -> Color(0xFFFF7043)   // Heavy Gaming — deep orange
    index == 5 -> Color(0xFFAB47BC)   // Genshin — purple
    index == 6 -> Color(0xFF7E57C2)   // Honkai — deep purple
    index == 7 -> Color(0xFFEC407A)   // High FPS — pink
    index == 8 -> Color(0xFF26A69A)   // Camera — teal
    index == 9 -> Color(0xFF26A69A)   // 4K Recording — teal
    index == 10 -> Color(0xFF42A5F5)  // Video — light blue
    index == 11 -> Color(0xFF42A5F5)  // Video Chat — light blue
    index == 12 -> Color(0xFF66BB6A)  // Navigation — green
    index == 13 -> Color(0xFF78909C)  // Phone Call — blue grey
    index == 14 -> Color(0xFFAB47BC)  // AR/VR — purple
    index == 15 -> Color(0xFF8D6E63)  // Data Transfer — brown
    index == 16 -> Color(0xFF66BB6A)  // Battery Saver — green
    // Custom profiles: deterministic color from index hash
    index >= CUSTOM_PROFILE_BASE -> {
        val hue = ((index - CUSTOM_PROFILE_BASE) * 137.508f) % 360f
        Color.hsl(hue, 0.65f, 0.55f)
    }
    else -> Color(0xFF90A4AE)
}

// ── Dynamic Thermal Profile Selector (reads ALL profiles from MiThermalService) ──
// Built-in profiles from THERMAL_PROFILE_NAMES (indices 0..16)
// Custom profiles from Settings.System "mithermal_custom_profiles" (indices >= 1000)
// Fully dynamic — no hardcoded profile lists!

data class ThermalProfileEntry(
    val index: Int,
    val name: String,
    val isCustom: Boolean = false
)

/**
 * Reads all available thermal profiles dynamically:
 * 1. Built-in profiles from the THERMAL_PROFILE_NAMES constant
 * 2. Custom user-created profiles from Settings.System "mithermal_custom_profiles"
 *
 * Returns a live-updating list so adding/removing custom profiles
 * in Settings will be reflected immediately.
 */
@Composable
fun rememberThermalProfiles(): List<ThermalProfileEntry> {
    val context = LocalContext.current
    val profiles = remember { mutableStateListOf<ThermalProfileEntry>() }

    // Observe custom profiles key for live updates
    val customProfilesUri = remember {
        Settings.System.getUriFor(THERMAL_CUSTOM_PROFILES_KEY)
    }

    // Initial load + observe changes
    LaunchedEffect(Unit) {
        fun reload() {
            profiles.clear()
            // 1. Built-in profiles
            THERMAL_PROFILE_NAMES.forEachIndexed { i, name ->
                profiles.add(ThermalProfileEntry(i, name, isCustom = false))
            }
            // 2. Custom profiles from MiThermalService Settings key
            try {
                val json = Settings.System.getStringForUser(
                    context.contentResolver,
                    THERMAL_CUSTOM_PROFILES_KEY,
                    android.os.UserHandle.USER_CURRENT
                )
                if (!json.isNullOrEmpty()) {
                    val arr = org.json.JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val profile = arr.getJSONObject(i)
                        val name = profile.optString("name", "Custom ${i + 1}")
                        profiles.add(ThermalProfileEntry(
                            index = CUSTOM_PROFILE_BASE + i,
                            name = name,
                            isCustom = true
                        ))
                    }
                }
            } catch (_: Exception) {}
        }

        reload()

        // Observe Settings changes for live updates
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reload()
            }
        }
        context.contentResolver.registerContentObserver(customProfilesUri, false, observer)

        // Cleanup on dispose
        kotlinx.coroutines.awaitCancellation()
    }

    return profiles
}

@Composable
fun DynamicThermalProfileSelector(
    selectedIndex: Int,
    onProfileSelected: (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()
    val profiles = rememberThermalProfiles()

    // Filter to gaming-relevant profiles for the header (show all in expanded)
    // Gaming profiles: Auto(0), Performance(2), Balanced Gaming(3), Heavy Gaming(4),
    //                  Genshin(5), HSR(6), High FPS(7), Battery Saver(16) + ALL custom
    val gamingProfileIndices = setOf(0, 2, 3, 4, 5, 6, 7, 16)
    val relevantProfiles = profiles.filter { it.isCustom || it.index in gamingProfileIndices }

    // If current profile is not in the gaming set, show it anyway
    val visibleProfiles = if (relevantProfiles.any { it.index == selectedIndex }) {
        relevantProfiles
    } else {
        val extra = profiles.find { it.index == selectedIndex }
        if (extra != null) relevantProfiles + extra else relevantProfiles
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Section label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            Text(
                text = "Thermal Profile",
                color = PanelTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            val currentName = profiles.find { it.index == selectedIndex }?.name ?: "Auto"
            Text(
                text = currentName,
                color = LocalPanelAccent.current,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Horizontal scrolling chip row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visibleProfiles.forEach { entry ->
                val isSelected = entry.index == selectedIndex
                val profileColor = getThermalProfileColor(entry.index)

                val chipBg by animateColorAsState(
                    targetValue = if (isSelected) profileColor else Color.White.copy(alpha = 0.08f),
                    animationSpec = tween(200),
                    label = "thermal_bg_${entry.index}"
                )
                val chipText by animateColorAsState(
                    targetValue = if (isSelected) Color.Black else PanelTextSecondary,
                    animationSpec = tween(200),
                    label = "thermal_text_${entry.index}"
                )

                Surface(
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = chipBg,
                    onClick = {
                        if (!isSelected) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onProfileSelected(entry.index)
                        }
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        if (entry.isCustom) {
                            Icon(
                                painter = painterResource(R.drawable.materialsymbols_ic_bolt_rounded_filled),
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = chipText,
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                        }
                        Text(
                            text = entry.name,
                            color = chipText,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ── Game Color Mode (Display Styles using Xiaomi vendor color modes) ──
// 0=Original(269), 1=Vivid(258), 2=Saturated(256), 3=P3(268), 4=sRGB(267)
internal val GAME_COLOR_MODE_KEY = "game_color_mode"

data class GameColorMode(
    val id: Int,
    val label: String,
    val iconRes: Int // Material icon drawable resource
)

internal val colorModes = listOf(
    GameColorMode(0, "Original", R.drawable.ic_color_original),
    GameColorMode(1, "Vivid", R.drawable.ic_color_vivid),
    GameColorMode(2, "Saturated", R.drawable.ic_color_vibrant),
    GameColorMode(3, "P3", R.drawable.ic_color_bright),
    GameColorMode(4, "sRGB", R.drawable.ic_color_hdr),
)

@Composable
fun GameColorModeSelector(
    packageName: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Load saved color mode for this game
    val currentMode = remember(packageName) { mutableStateOf(0) }

    LaunchedEffect(packageName) {
        if (packageName != null) {
            try {
                val saved = Settings.System.getIntForUser(
                    context.contentResolver,
                    "${GAME_COLOR_MODE_KEY}_${packageName}",
                    0,
                    android.os.UserHandle.USER_CURRENT
                )
                currentMode.value = saved
            } catch (_: Exception) {
                currentMode.value = 0
            }
        }
    }

    Column(modifier = modifier) {
        // Section label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Text(
                text = "Display Style",
                color = PanelTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = colorModes[currentMode.value].label,
                color = LocalPanelAccent.current,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Horizontal scrollable chip row
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            colorModes.forEach { mode ->
                val isSelected = currentMode.value == mode.id
                val chipBg by animateColorAsState(
                    targetValue = if (isSelected) LocalPanelAccent.current else Color.White.copy(alpha = 0.08f),
                    animationSpec = tween(200),
                    label = "chip_bg_${mode.id}"
                )
                val chipText by animateColorAsState(
                    targetValue = if (isSelected) Color.Black else PanelTextSecondary,
                    animationSpec = tween(200),
                    label = "chip_text_${mode.id}"
                )

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = chipBg,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentMode.value = mode.id
                        // Persist per-game
                        if (packageName != null) {
                            try {
                                Settings.System.putIntForUser(
                                    context.contentResolver,
                                    "${GAME_COLOR_MODE_KEY}_${packageName}",
                                    mode.id,
                                    android.os.UserHandle.USER_CURRENT
                                )
                            } catch (_: Exception) {}
                        }
                        // Apply display enhancement
                        applyGameColorMode(context, mode.id)
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(mode.iconRes),
                            contentDescription = mode.label,
                            modifier = Modifier.size(14.dp),
                            tint = chipText,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = mode.label,
                            color = chipText,
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Deprecated(
    "Use PerAppSettingStore.setDisplayStyle()",
    replaceWith = ReplaceWith("PerAppSettingStore(context).setDisplayStyle(pkg, mode)")
)
internal fun applyGameColorMode(context: android.content.Context, mode: Int) {
    com.ireddragonicy.gamespace.display.DisplayColorManager
        .get(context)
        .applyStyle(mode)
}

// ── Compact Dropdown Selectors (replaces horizontal scroll chip rows) ──

/**
 * Thermal Profile dropdown — compact single-row selector.
 * Shows current profile name with colored dot, taps to expand dropdown menu.
 * Saves ~40dp vs horizontal chip row. Material You icon for custom profiles.
 */
@Composable
fun ThermalProfileDropdown(
    profiles: List<ThermalProfileEntry>,
    selectedIndex: Int,
    onProfileSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    val currentName = profiles.find { it.index == selectedIndex }?.name ?: "Auto"
    val profileColor = getThermalProfileColor(selectedIndex)

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.materialsymbols_ic_bolt_rounded_filled),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = PanelTextSecondary,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Thermal Profile",
                color = PanelTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
        }

        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.06f),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    expanded = true
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape).background(profileColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentName,
                        color = profileColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Icon(
                        painter = painterResource(R.drawable.materialsymbols_ic_chevron_right_rounded_filled),
                        contentDescription = "Expand",
                        modifier = Modifier.size(14.dp)
                            .graphicsLayer { rotationZ = if (expanded) -90f else 90f },
                        tint = PanelTextSecondary,
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(150.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = PanelCardBg
            ) {
                profiles.forEach { entry ->
                    val isSelected = entry.index == selectedIndex
                    val entryColor = getThermalProfileColor(entry.index)
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = getProfileIcon(entry.index, entry.name),
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isSelected) Color.Black else entryColor,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = entry.name,
                                    color = if (isSelected) Color.Black else PanelTextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onProfileSelected(entry.index)
                            expanded = false
                        },
                        modifier = Modifier.background(
                            if (isSelected) entryColor.copy(alpha = 0.9f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        ).height(30.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    )
                }
            }
        }
    }
}

/**
 * Display Style dropdown — compact single-row selector.
 */
@Composable
fun DisplayStyleDropdown(
    packageName: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }

    val currentMode = remember(packageName) { mutableStateOf(0) }
    LaunchedEffect(packageName) {
        if (packageName != null) {
            try {
                val saved = Settings.System.getIntForUser(
                    context.contentResolver,
                    "${GAME_COLOR_MODE_KEY}_${packageName}",
                    0, android.os.UserHandle.USER_CURRENT
                )
                currentMode.value = saved
            } catch (_: Exception) { currentMode.value = 0 }
        }
    }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.materialsymbols_ic_colors_rounded_filled),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = PanelTextSecondary,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Display Style",
                color = PanelTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
        }

        Box {
            val currentLabel = colorModes.find { it.id == currentMode.value }?.label ?: "Original"

            Surface(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.06f),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    expanded = true
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(
                            colorModes.find { it.id == currentMode.value }?.iconRes ?: R.drawable.materialsymbols_ic_circle_rounded_filled
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = LocalPanelAccent.current,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentLabel,
                        color = LocalPanelAccent.current,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Icon(
                        painter = painterResource(R.drawable.materialsymbols_ic_chevron_right_rounded_filled),
                        contentDescription = "Expand",
                        modifier = Modifier.size(14.dp)
                            .graphicsLayer { rotationZ = if (expanded) -90f else 90f },
                        tint = PanelTextSecondary,
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(140.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = PanelCardBg
            ) {
                colorModes.forEach { mode ->
                    val isSelected = currentMode.value == mode.id
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(mode.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = if (isSelected) Color.Black else PanelTextPrimary,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = mode.label,
                                    color = if (isSelected) Color.Black else PanelTextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentMode.value = mode.id
                            if (packageName != null) {
                                try {
                                    Settings.System.putIntForUser(
                                        context.contentResolver,
                                        "${GAME_COLOR_MODE_KEY}_${packageName}",
                                        mode.id, android.os.UserHandle.USER_CURRENT
                                    )
                                } catch (_: Exception) {}
                            }
                            applyGameColorMode(context, mode.id)
                            expanded = false
                        },
                        modifier = Modifier.background(
                            if (isSelected) LocalPanelAccent.current.copy(alpha = 0.9f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        ),
                    )
                }
            }
        }
    }
}
