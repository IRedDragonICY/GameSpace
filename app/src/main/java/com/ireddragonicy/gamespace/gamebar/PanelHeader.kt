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
fun HeaderInfoBar(
    modifier: Modifier = Modifier,
    headerExpanded: Boolean = true,
    time: String,
    fpsInteractor: FpsInteractor,
    onToggleExpand: () -> Unit,
    onEditClick: () -> Unit,
    tileRepository: TileRepository,
    currentGamePackage: String? = null,
) {
    val batteryInfo = rememberBatteryInfo()
    val thermalProfile = rememberThermalProfile(currentGamePackage)
    val context = LocalContext.current

    val profileName = remember(thermalProfile.value) {
        val idx = thermalProfile.value
        if (idx < THERMAL_PROFILE_NAMES.size) {
            THERMAL_PROFILE_NAMES[idx]
        } else if (idx >= CUSTOM_PROFILE_BASE) {
            // Read custom profile name from Settings
            try {
                val json = Settings.System.getStringForUser(
                    context.contentResolver,
                    THERMAL_CUSTOM_PROFILES_KEY,
                    UserHandle.USER_CURRENT
                )
                if (!json.isNullOrEmpty()) {
                    val arr = org.json.JSONArray(json)
                    val ci = idx - CUSTOM_PROFILE_BASE
                    if (ci in 0 until arr.length()) {
                        arr.getJSONObject(ci).optString("name", "Custom")
                    } else "Custom"
                } else "Custom"
            } catch (_: Exception) { "Custom" }
        } else "Auto"
    }
    val profileColor = getThermalProfileColor(thermalProfile.value)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()
            )
            .background(
                color = LocalPanelAccent.current.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp)
            )
            .border(
                width = 0.5.dp,
                color = PanelBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(8.dp)
    ) {
        // ── Game Turbo Header: Time + Battery + Settings ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = time,
                style = MaterialTheme.typography.bodyMedium,
                color = PanelTextSecondary,
                fontSize = 13.sp
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            // Compact Rx/Tx speed badge (Status bar style)
            val rxSpeed = tileRepository.rxSpeedKbps.value
            val txSpeed = tileRepository.txSpeedKbps.value
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "▼ ${rxSpeed.toInt()}K",
                    color = Color(0xFF64B5F6),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    lineHeight = 7.sp
                )
                Text(
                    text = "▲ ${txSpeed.toInt()}K",
                    color = Color(0xFFFF8A65),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    lineHeight = 7.sp
                )
            }

            // Session timer and Ping moved to VerticalAppSidebar

            // FPS Stats recording indicator
            val isRecording = tileRepository.fpsStatsRecordingState.value
            if (isRecording) {
                val elapsed = tileRepository.fpsStatsElapsedSeconds.value
                val minutes = elapsed / 60
                val seconds = elapsed % 60
                val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
                val dotAlpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "rec_dot",
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFFFF1744).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .graphicsLayer { alpha = dotAlpha }
                            .background(Color(0xFFFF1744), CircleShape),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "REC %02d:%02d".format(minutes, seconds),
                        color = Color(0xFFFF1744),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Screen Recording indicator (game record)
            val isScreenRecording = tileRepository.screenRecordingActive.value
            if (isScreenRecording) {
                val screenRecTransition = rememberInfiniteTransition(label = "screen_rec_blink")
                val screenRecDotAlpha by screenRecTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "screen_rec_dot",
                )
                Spacer(modifier = Modifier.width(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFFFF1744).copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .graphicsLayer { alpha = screenRecDotAlpha }
                            .background(Color(0xFFFF1744), CircleShape),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "REC",
                        color = Color(0xFFFF1744),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            // Battery with charging bolt, wattage, temp — TAP to toggle bypass charging
            val batteryInfo = rememberBatteryInfo()
            val bypassEnabled = remember { mutableStateOf(false) }

            // Read bypass charging state (uses mithermal key — MiThermalService writes smart_night sysfs)
            LaunchedEffect(Unit) {
                while (true) {
                    try {
                        bypassEnabled.value = Settings.System.getIntForUser(
                            context.contentResolver, "mithermal_bypass_charging",
                            0, UserHandle.USER_CURRENT
                        ) == 1
                    } catch (_: Exception) {}
                    kotlinx.coroutines.delay(2000L)
                }
            }

            val bypassBorderColor by animateColorAsState(
                targetValue = if (bypassEnabled.value) Color(0xFFFF9800) else Color.Transparent,
                animationSpec = tween(300), label = "bypass_border"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        if (bypassEnabled.value) Color(0xFFFF9800).copy(alpha = 0.08f)
                        else Color.Transparent,
                        RoundedCornerShape(6.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = bypassBorderColor.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable {
                        val newState = !bypassEnabled.value
                        bypassEnabled.value = newState
                        try {
                            // Write mithermal_bypass_charging — MiThermalService observes this
                            // and writes to /sys/.../smart_night to actually cut/restore power
                            Settings.System.putIntForUser(
                                context.contentResolver, "mithermal_bypass_charging",
                                if (newState) 1 else 0, UserHandle.USER_CURRENT
                            )
                        } catch (_: Exception) {}
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .height(20.dp),  // Fixed height prevents layout shift when BP badge appears
            ) {
                // Charging bolt — always rendered (reserves space) to prevent layout shift
                val showBolt = batteryInfo.isCharging || bypassEnabled.value
                val infiniteTransition = rememberInfiniteTransition(label = "bolt_pulse")
                val boltAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "bolt_alpha"
                )
                val boltColor = if (bypassEnabled.value) Color(0xFFFF9800) else Color(0xFF4CAF50)
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_bolt_rounded_filled),
                    contentDescription = if (bypassEnabled.value) "Bypass Charging" else "Charging",
                    modifier = Modifier
                        .size(12.dp)
                        .graphicsLayer { alpha = if (showBolt) boltAlpha else 0f },
                    tint = boltColor,
                )
                Spacer(modifier = Modifier.width(2.dp))
                val batteryIcon = when {
                    batteryInfo.level >= 90 -> painterResource(R.drawable.materialsymbols_ic_battery_android_full_rounded_filled)
                    batteryInfo.level >= 70 -> painterResource(R.drawable.materialsymbols_ic_battery_android_4_rounded_filled)
                    batteryInfo.level >= 50 -> painterResource(R.drawable.materialsymbols_ic_battery_android_3_rounded_filled)
                    batteryInfo.level >= 30 -> painterResource(R.drawable.materialsymbols_ic_battery_android_2_rounded_filled)
                    batteryInfo.level >= 10 -> painterResource(R.drawable.materialsymbols_ic_battery_android_1_rounded_filled)
                    else -> painterResource(R.drawable.materialsymbols_ic_battery_android_0_rounded_filled)
                }
                Icon(
                    painter = batteryIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = when {
                        bypassEnabled.value -> Color(0xFFFF9800)
                        batteryInfo.level > 20 -> LocalPanelAccent.current
                        else -> PanelRed
                    },
                )
                Text(
                    text = "${batteryInfo.level}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelTextPrimary,
                    fontSize = 12.sp
                )
                // Wattage display: +X.XW (charging) / -X.XW (discharging)
                if (batteryInfo.wattage != 0f) {
                    val wattText = if (batteryInfo.isCharging)
                        "+%.1fW".format(batteryInfo.wattage)
                    else
                        "%.1fW".format(batteryInfo.wattage)
                    val wattColor = when {
                        bypassEnabled.value -> Color(0xFFFF9800)
                        batteryInfo.isCharging -> Color(0xFF4CAF50)
                        else -> LocalPanelAccent.current.copy(alpha = 0.7f)
                    }
                    Text(
                        text = " $wattText",
                        style = MaterialTheme.typography.bodySmall,
                        color = wattColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                // Battery temp with battery-specific thresholds
                if (batteryInfo.temperatureC > 0f) {
                    val battTempColor by animateColorAsState(
                        targetValue = batteryTempColor(batteryInfo.temperatureC),
                        animationSpec = tween(500),
                        label = "batt_temp_color"
                    )
                    Text(
                        text = " %.0f°C".format(batteryInfo.temperatureC),
                        style = MaterialTheme.typography.bodySmall,
                        color = battTempColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                // BYPASS badge when active
                if (bypassEnabled.value) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "BP",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF9800),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .background(Color(0xFFFF9800).copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 3.dp, vertical = (0.5f).dp),
                    )
                }
            }

            // Edit Tiles Icon
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "Edit tiles",
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onEditClick() },
                tint = PanelTextSecondary,
            )

            // Per-app Settings gear
            if (currentGamePackage != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled),
                    contentDescription = "Per-app settings",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable {
                            val intent = Intent(context,
                                PerAppSettingsActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                putExtra(PerAppSettingsActivity.EXTRA_PACKAGE, currentGamePackage)
                            }
                            context.startActivity(intent)
                        },
                    tint = PanelTextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── FPS Circle Gauge + CPU/GPU (tap to expand individual graphs) ──
        val realHistory by fpsInteractor.realFpsHistory.collectAsState(initial = emptyList())
        val currentFps = realHistory.lastOrNull()?.toInt() ?: 0
        val avgFps = if (realHistory.isNotEmpty()) realHistory.average().toInt() else 0

        var selectedMetric by remember { mutableStateOf<String?>(null) }
        val selectMetric: (String?) -> Unit = { selectedMetric = it }

        // GPU state — hoisted to parent level for shared access between display and graph
        val gpuUsage = rememberGpuUsage()
        val gpuFreq = rememberGpuFrequency()
        val gpuTemp = rememberSysfsTemp("/sys/class/thermal/thermal_zone24/temp")

        // GPU history — hoisted to parent level so it persists across AnimatedVisibility show/hide
        // (same pattern as FPS: FpsInteractor holds history at service level)
        val gpuHistory = remember { mutableStateListOf<Int>() }
        val gpuMaxSamples = 30

        // Collect GPU history continuously (not inside AnimatedVisibility)
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000L)
                val current = gpuUsage.value
                if (gpuHistory.size >= gpuMaxSamples) {
                    gpuHistory.removeAt(0)
                }
                gpuHistory.add(current)
            }
        }

        // ── ROG telemetry strip: CPU cell · FPS core · GPU cell ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val cpuUsage = rememberCpuUsage()
            val cpuFreq = rememberCpuFrequency()
            val cpuTemp = rememberSysfsTemp("/sys/class/thermal/thermal_zone9/temp")
            val cpuTColor by animateColorAsState(
                targetValue = cpuTempColor(cpuTemp.value),
                animationSpec = tween(500), label = "cpu_tc"
            )
            TelemetryCell(
                label = "CPU",
                valueText = "${cpuUsage.value}%",
                ratio = cpuUsage.value / 100f,
                accent = LocalPanelAccent.current,
                detail1 = cpuFreq.value,
                detail2 = "%.1f°C".format(cpuTemp.value),
                detail2Color = cpuTColor,
                selected = selectedMetric == "cpu",
                onClick = { selectMetric(if (selectedMetric == "cpu") null else "cpu") },
            )

            val maxFps by fpsInteractor.dynamicMaxFps.collectAsState()
            val isCpuThrottled by rememberCpuThrottle()
            FpsGauge(
                fps = currentFps,
                maxFps = maxFps,
                labelText = if (selectedMetric == "frametime") "FT" else "FPS",
                throttled = isCpuThrottled,
                accent = LocalPanelAccent.current,
                onClick = {
                    selectMetric(
                        when (selectedMetric) {
                            null -> "fps"
                            "fps" -> "frametime"
                            "frametime" -> null
                            else -> "fps"
                        }
                    )
                },
            )

            val gpuTColor by animateColorAsState(
                targetValue = gpuTempColor(gpuTemp.value),
                animationSpec = tween(500), label = "gpu_tc"
            )
            TelemetryCell(
                label = "GPU",
                valueText = "${gpuUsage.value}%",
                ratio = gpuUsage.value / 100f,
                accent = LocalPanelAccent.current,
                detail1 = gpuFreq.value,
                detail2 = "%.1f°C".format(gpuTemp.value),
                detail2Color = gpuTColor,
                selected = selectedMetric == "gpu",
                onClick = { selectMetric(if (selectedMetric == "gpu") null else "gpu") },
            )
        }

        // ── RAM Usage — ultra compact inline bar ──
        val accentColor = MaterialTheme.colorScheme.primary
        val ramInfo = rememberRamUsage()
        val ramRatio by animateFloatAsState(
            targetValue = ramInfo.usagePercent / 100f,
            animationSpec = tween(500), label = "ram_bar"
        )
        val ramBarColor by animateColorAsState(
            targetValue = when {
                ramInfo.usagePercent > 90f -> Color(0xFFD32F2F)
                ramInfo.usagePercent > 75f -> Color(0xFFFFA000)
                else -> accentColor
            },
            animationSpec = tween(500), label = "ram_color"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.materialsymbols_ic_memory_rounded_filled),
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = ramBarColor.copy(alpha = 0.6f),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ramRatio.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(1.dp))
                        .background(ramBarColor)
                )
            }
            Text(
                text = if (ramInfo.swapTotalGb > 0.1f)
                    "%.1f/%.0fG · SWAP %.1f/%.0fG".format(
                        ramInfo.usedGb, ramInfo.totalGb,
                        ramInfo.swapUsedGb, ramInfo.swapTotalGb,
                    )
                else "%.1f/%.0fG".format(ramInfo.usedGb, ramInfo.totalGb),
                color = PanelTextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // ── Thermal Profile + Display Style — 1 row side-by-side (compact) ──
        val thermalProfile = rememberThermalProfile(currentGamePackage)
        val profiles = rememberThermalProfiles()
        val gamingProfileIndices = setOf(0, 2, 3, 4, 5, 6, 7, 16)
        val relevantProfiles = profiles.filter { it.isCustom || it.index in gamingProfileIndices }
        val currentProfileName = relevantProfiles.find { it.index == thermalProfile.value }?.name ?: "Auto"
        val profileColor = getThermalProfileColor(thermalProfile.value)

        // Display style state
        val displayMode = remember(currentGamePackage) { mutableStateOf(0) }
        LaunchedEffect(currentGamePackage) {
            if (currentGamePackage != null) {
                try {
                    val saved = Settings.System.getIntForUser(
                        context.contentResolver,
                        "${GAME_COLOR_MODE_KEY}_${currentGamePackage}",
                        0, android.os.UserHandle.USER_CURRENT
                    )
                    displayMode.value = saved
                } catch (_: Exception) { displayMode.value = 0 }
            }
        }
        val currentDisplayLabel = colorModes.find { it.id == displayMode.value }?.label ?: "Original"
        val currentDisplayIcon = colorModes.find { it.id == displayMode.value }?.iconRes ?: R.drawable.materialsymbols_ic_circle_rounded_filled

        var thermalExpanded by remember { mutableStateOf(false) }
        var displayExpanded by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ── Left: Thermal Profile compact pill ──
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            thermalExpanded = true
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(5.dp).clip(CircleShape).background(profileColor)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = currentProfileName,
                        color = profileColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.materialsymbols_ic_chevron_right_rounded_filled),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp)
                            .graphicsLayer { rotationZ = if (thermalExpanded) -90f else 90f },
                        tint = PanelTextSecondary.copy(alpha = 0.5f),
                    )
                }

                DropdownMenu(
                    expanded = thermalExpanded,
                    onDismissRequest = { thermalExpanded = false },
                    modifier = Modifier.background(PanelCardBg, RoundedCornerShape(12.dp)).width(180.dp)
                ) {
                    relevantProfiles.forEach { entry ->
                        val isSelected = entry.index == thermalProfile.value
                        val entryColor = getThermalProfileColor(entry.index)
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(entryColor))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = entry.name,
                                        color = if (isSelected) Color.Black else PanelTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                thermalProfile.value = entry.index
                                if (currentGamePackage != null) {
                                    try {
                                        val json = Settings.System.getStringForUser(
                                            context.contentResolver, THERMAL_PROFILE_KEY, UserHandle.USER_CURRENT
                                        ) ?: "{}"
                                        val obj = org.json.JSONObject(json)
                                        if (entry.index == 0) obj.remove(currentGamePackage)
                                        else obj.put(currentGamePackage, entry.index)
                                        Settings.System.putStringForUser(
                                            context.contentResolver, THERMAL_PROFILE_KEY,
                                            obj.toString(), UserHandle.USER_CURRENT
                                        )
                                    } catch (_: Exception) {}
                                }
                                thermalExpanded = false
                            },
                            modifier = Modifier.background(
                                if (isSelected) entryColor.copy(alpha = 0.9f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            ),
                        )
                    }
                }
            }

            // ── Right: Display Style compact pill ──
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            displayExpanded = true
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(currentDisplayIcon),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = LocalPanelAccent.current,
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = currentDisplayLabel,
                        color = LocalPanelAccent.current,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.materialsymbols_ic_chevron_right_rounded_filled),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp)
                            .graphicsLayer { rotationZ = if (displayExpanded) -90f else 90f },
                        tint = PanelTextSecondary.copy(alpha = 0.5f),
                    )
                }

                DropdownMenu(
                    expanded = displayExpanded,
                    onDismissRequest = { displayExpanded = false },
                    modifier = Modifier.background(PanelCardBg, RoundedCornerShape(12.dp)).width(180.dp)
                ) {
                    colorModes.forEach { mode ->
                        val isSelected = displayMode.value == mode.id
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(mode.iconRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isSelected) Color.Black else PanelTextPrimary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = mode.label,
                                        color = if (isSelected) Color.Black else PanelTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                displayMode.value = mode.id
                                if (currentGamePackage != null) {
                                    try {
                                        Settings.System.putIntForUser(
                                            context.contentResolver,
                                            "${GAME_COLOR_MODE_KEY}_${currentGamePackage}",
                                            mode.id, android.os.UserHandle.USER_CURRENT
                                        )
                                    } catch (_: Exception) {}
                                }
                                applyGameColorMode(context, mode.id)
                                displayExpanded = false
                            },
                            modifier = Modifier.background(
                                if (isSelected) LocalPanelAccent.current.copy(alpha = 0.9f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            ),
                        )
                    }
                }
            }

            // ── Tuner entry pill: opens as a full panel page (never causes scrolling) ──
            Box(modifier = Modifier.weight(1f)) {
                TunerEntryRow(
                    onOpen = { tileRepository.showPerfTuner.value = true },
                )
            }
        }

        // ── Expanded: Per-metric graphs (tap CPU/GPU/FPS to show) ──
        AnimatedVisibility(
            visible = selectedMetric != null,
            enter = fadeIn(
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()
            ) + expandVertically(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()
            ),
            exit = fadeOut(
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()
            ) + shrinkVertically(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()
            )
        ) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                when (selectedMetric) {
                    "cpu" -> CpuClusterGraph()
                    "gpu" -> {
                        GpuLoadGraph(
                            gpuUsage = gpuUsage,
                            gpuFreq = gpuFreq,
                            gpuTemp = gpuTemp,
                            history = gpuHistory,
                        )
                    }
                    "fps" -> {
                        FpsGraph(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            interactor = fpsInteractor
                        )
                    }
                    "frametime" -> {
                        FrametimeGraph(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            interactor = fpsInteractor
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun InfoRow(
    batteryInfo: BatteryInfo,
    profileName: String,
    profileColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val temp = batteryInfo.temperatureC.toInt()
        BatteryIndicator(batteryLevel = batteryInfo.level)
        Spacer(modifier = Modifier.width(4.dp))
        InfoItem(
            icon = painterResource(R.drawable.materialsymbols_ic_device_thermostat_rounded_filled),
            value = "$temp"
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = profileName,
            style = MaterialTheme.typography.bodyLarge,
            color = profileColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun InfoItem(
    icon: Any?,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        if (icon != null) {
            when (icon) {
                is ImageVector -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                is Painter -> Icon(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(1.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun BatteryIndicator(
    batteryLevel: Int,
    modifier: Modifier = Modifier
) {
    val icon = when {
        batteryLevel >= 90 -> painterResource(R.drawable.materialsymbols_ic_battery_android_full_rounded_filled)
        batteryLevel >= 70 -> painterResource(R.drawable.materialsymbols_ic_battery_android_4_rounded_filled)
        batteryLevel >= 50 -> painterResource(R.drawable.materialsymbols_ic_battery_android_3_rounded_filled)
        batteryLevel >= 30 -> painterResource(R.drawable.materialsymbols_ic_battery_android_2_rounded_filled)
        batteryLevel >= 10 -> painterResource(R.drawable.materialsymbols_ic_battery_android_1_rounded_filled)
        else -> painterResource(R.drawable.materialsymbols_ic_battery_android_0_rounded_filled)
    }

    val batteryText = "$batteryLevel%"

    InfoItem(
        icon = icon,
        value = batteryText
    )
}

@Composable
fun rememberCurrentTime(): String {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeState = remember { mutableStateOf(timeFormat.format(Date())) }

    DisposableEffect(Unit) {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Main.immediate + job)

        scope.launch {
            while (isActive) {
                val now = Date()
                val newTime = timeFormat.format(now)
                if (newTime != timeState.value) {
                    timeState.value = newTime
                }
                val millisUntilNextMinute = 60000L - (now.time % 60000L)
                delay(millisUntilNextMinute)
            }
        }

        onDispose {
            job.cancel()
        }
    }

    return timeState.value
}

@Composable
fun rememberBatteryInfo(): BatteryInfo {
    val context = LocalContext.current
    val batteryInfo = remember { mutableStateOf(BatteryInfo()) }
    val batteryManager = remember {
        context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
    }
    // Cached voltage from broadcast (mV → µV) — updated by receiver, read by polling loop
    val cachedVoltageUv = remember { mutableStateOf(0L) }

    // Helper: compute wattage from BatteryManager current + cached voltage
    fun computeWattage(): Pair<Float, Boolean> {
        val currentUa = batteryManager.getIntProperty(
            android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
        ).toLong()
        val isCharging = batteryManager.isCharging
        val voltageUv = cachedVoltageUv.value
        if (voltageUv == 0L) return Pair(0f, isCharging)
        val powerW = (kotlin.math.abs(currentUa).toDouble() * voltageUv.toDouble() / 1_000_000_000_000.0).toFloat()
        return Pair(if (isCharging) powerW else -powerW, isCharging)
    }

    // Broadcast receiver: level + temp + voltage (fires on battery state changes)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    val tempCelsius = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                    val voltageMv = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                    cachedVoltageUv.value = voltageMv.toLong() * 1000L

                    // Compute wattage inline — no separate polling needed for this event
                    val (watt, charging) = computeWattage()
                    batteryInfo.value = BatteryInfo(
                        level = percentage,
                        temperatureC = tempCelsius,
                        wattage = watt,
                        isCharging = charging,
                    )
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)

        // Seed initial values from sticky intent
        val sticky = context.registerReceiver(null, filter)
        sticky?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val tempCelsius = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            val voltageMv = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            cachedVoltageUv.value = voltageMv.toLong() * 1000L

            val (watt, charging) = computeWattage()
            batteryInfo.value = BatteryInfo(
                level = percentage,
                temperatureC = tempCelsius,
                wattage = watt,
                isCharging = charging,
            )
        }

        onDispose { context.unregisterReceiver(receiver) }
    }

    // Lightweight polling: only updates wattage between battery broadcasts (every 2s)
    // BatteryManager.getIntProperty is fast — no sysfs, no registerReceiver
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000L)
            try {
                val (watt, charging) = computeWattage()
                batteryInfo.value = batteryInfo.value.copy(
                    wattage = watt,
                    isCharging = charging,
                )
            } catch (_: Exception) {}
        }
    }

    return batteryInfo.value
}

data class BatteryInfo(
    val level: Int = -1,
    val temperatureC: Float = 0f,
    val wattage: Float = 0f,     // ±W: positive = charging, negative = discharging
    val isCharging: Boolean = false,
)

// ══════════════════════════════════════════════════════════════════
// Feature: Game Session Timer
// ══════════════════════════════════════════════════════════════════

/** Tracks elapsed session time from a persistent start time (set by SessionService). */
@Composable
fun rememberSessionTimer(startTimeMs: Long): String {
    var elapsed by remember { mutableStateOf(0L) }

    LaunchedEffect(startTimeMs) {
        if (startTimeMs <= 0L) return@LaunchedEffect
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - startTimeMs
            kotlinx.coroutines.delay(1000L)
        }
    }

    return remember(elapsed) {
        if (startTimeMs <= 0L) return@remember "00:00"
        val totalSec = elapsed / 1000
        val hours = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        if (hours > 0) "%d:%02d:%02d".format(hours, mins, secs)
        else "%02d:%02d".format(mins, secs)
    }
}
