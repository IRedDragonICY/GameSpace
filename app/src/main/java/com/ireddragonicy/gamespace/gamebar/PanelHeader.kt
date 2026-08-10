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
import com.ireddragonicy.gamespace.thermal.KernelNodeIO
import com.ireddragonicy.gamespace.thermal.ThermalNodes
import com.ireddragonicy.gamespace.thermal.ThermalProfiles

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

import com.ireddragonicy.gamespace.data.PerAppSettingStore

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
    val t = rememberTelemetry()
    val thermalProfile = rememberThermalProfile(currentGamePackage)
    val context = LocalContext.current

    val profileName = remember(thermalProfile.value) {
        val idx = thermalProfile.value
        if (idx < THERMAL_PROFILE_NAMES.size) {
            THERMAL_PROFILE_NAMES[idx]
        } else if (idx >= CUSTOM_PROFILE_BASE) {
            val json = Settings.System.getStringForUser(
                context.contentResolver,
                THERMAL_CUSTOM_PROFILES_KEY,
                UserHandle.USER_CURRENT
            )
            com.ireddragonicy.gamespace.thermal.ThermalProfiles.parseCustomProfiles(json)
                .find { it.index == idx }?.name ?: "Custom"
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



            Spacer(modifier = Modifier.weight(1f))
            // Battery with charging bolt, wattage, temp — TAP to toggle bypass charging
            val t = rememberTelemetry()
            val bypassEnabled = remember { mutableStateOf(false) }

            // Kernel-reported bypass state, not just the request. On a heavy
            // title the adapter can lose to the load and the pack starts
            // covering the difference — DEFICIT is worth showing, since the
            // toggle looks identical either way.
            val bypassState = remember { mutableStateOf(ThermalNodes.BYPASS_ST_IDLE) }

            LaunchedEffect(Unit) {
                while (true) {
                    try {
                        bypassEnabled.value = Settings.System.getIntForUser(
                            context.contentResolver, "mithermal_bypass_charging",
                            0, UserHandle.USER_CURRENT
                        ) == 1
                        bypassState.value =
                            KernelNodeIO.read(ThermalNodes.BYPASS_STATE)
                                ?.substringBefore(' ')?.toIntOrNull()
                                ?: ThermalNodes.BYPASS_ST_IDLE
                    } catch (_: Exception) {}
                    kotlinx.coroutines.delay(2000L)
                }
            }

            // Amber while the pack is genuinely out of the loop, red once it is
            // paying for the session anyway.
            val bypassAccent = when (bypassState.value) {
                ThermalNodes.BYPASS_ST_DEFICIT -> Color(0xFFE53935)
                else -> Color(0xFFFF9800)
            }

            val bypassBorderColor by animateColorAsState(
                targetValue = if (bypassEnabled.value) bypassAccent else Color.Transparent,
                animationSpec = tween(300), label = "bypass_border"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        if (bypassEnabled.value) bypassAccent.copy(alpha = 0.08f)
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
                val showBolt = t.batteryCharging || bypassEnabled.value
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
                val boltColor = if (bypassEnabled.value) bypassAccent else Color(0xFF4CAF50)
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
                    t.batteryCapacity >= 90 -> painterResource(R.drawable.materialsymbols_ic_battery_android_full_rounded_filled)
                    t.batteryCapacity >= 70 -> painterResource(R.drawable.materialsymbols_ic_battery_android_4_rounded_filled)
                    t.batteryCapacity >= 50 -> painterResource(R.drawable.materialsymbols_ic_battery_android_3_rounded_filled)
                    t.batteryCapacity >= 30 -> painterResource(R.drawable.materialsymbols_ic_battery_android_2_rounded_filled)
                    t.batteryCapacity >= 10 -> painterResource(R.drawable.materialsymbols_ic_battery_android_1_rounded_filled)
                    else -> painterResource(R.drawable.materialsymbols_ic_battery_android_0_rounded_filled)
                }
                Icon(
                    painter = batteryIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = when {
                        bypassEnabled.value -> bypassAccent
                        t.batteryCapacity > 20 -> LocalPanelAccent.current
                        else -> PanelRed
                    },
                )
                Text(
                    text = "${t.batteryCapacity}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelTextPrimary,
                    fontSize = 12.sp
                )
                // Sign convention = Scene / "battery-as-source":
                // discharging (battery delivering power) → positive,
                // charging    (power flowing into cell)  → negative.
                // batteryPowerW is an unsigned magnitude (TelemetryBus
                // takes abs()), so the sign is applied purely here.
                if (t.batteryPowerW != 0f) {
                    val wattText = if (t.batteryCharging)
                        "-%.1fW".format(t.batteryPowerW)
                    else
                        "+%.1fW".format(t.batteryPowerW)
                    val wattColor = when {
                        bypassEnabled.value -> bypassAccent
                        t.batteryCharging -> Color(0xFF4CAF50)
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
                if (t.batteryTempC > 0f) {
                    val battTempColor by animateColorAsState(
                        targetValue = batteryTempColor(t.batteryTempC),
                        animationSpec = tween(500),
                        label = "batt_temp_color"
                    )
                    Text(
                        text = " %.0f°C".format(t.batteryTempC),
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

        var selectedMetric by remember { mutableStateOf<String?>(null) }
        val selectMetric: (String?) -> Unit = { selectedMetric = it }

        // ── ROG telemetry strip: CPU cell · FPS core · GPU cell ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cpuTColor by animateColorAsState(heatColor(t.cpuHeat), tween(500), "cpu_tc")
            TelemetryCell(
                label = "CPU",
                valueText = "${t.cpuUsageTotal}%",
                ratio = t.cpuUsageTotal / 100f,
                accent = LocalPanelAccent.current,
                detail1 = run {
                    val k = t.cpuFreqMaxKhz
                    if (k >= 1_000_000) "%.1f GHz".format(k / 1_000_000.0) else "${k / 1000} MHz"
                },
                detail2 = "%.1f°C".format(t.cpuTempC),
                detail2Color = cpuTColor,
                selected = selectedMetric == "cpu",
                onClick = { selectMetric(if (selectedMetric == "cpu") null else "cpu") },
            )
            val maxFps by fpsInteractor.dynamicMaxFps.collectAsState()
            val thermalReduced = t.thermalHeadroomReduced
            FpsGauge(
                fps = currentFps,
                maxFps = maxFps,
                labelText = if (selectedMetric == "frametime") "FT" else "FPS",
                throttled = thermalReduced && currentFps < maxFps * 0.9f,
                accent = LocalPanelAccent.current,
                onClick = {
                    selectMetric(when (selectedMetric) {
                        null -> "fps"; "fps" -> "frametime"; "frametime" -> null; else -> "fps"
                    })
                },
            )
            val gpuTColor by animateColorAsState(heatColor(t.gpuHeat), tween(500), "gpu_tc")
            TelemetryCell(
                label = "GPU",
                valueText = "${t.gpuUsage}%",
                ratio = t.gpuUsage / 100f,
                accent = LocalPanelAccent.current,
                detail1 = "${t.gpuFreqMhz} MHz",
                detail2 = "%.1f°C".format(t.gpuTempC),
                detail2Color = gpuTColor,
                selected = selectedMetric == "gpu",
                onClick = { selectMetric(if (selectedMetric == "gpu") null else "gpu") },
            )
        }

        // ── RAM Usage — ultra compact inline bar ──
        val accentColor = MaterialTheme.colorScheme.primary
        val ramRatio by animateFloatAsState(
            targetValue = t.ramPct / 100f,
            animationSpec = tween(500), label = "ram_bar",
        )
        val ramBarColor by animateColorAsState(
            targetValue = when {
                t.ramPct > 90f -> Color(0xFFD32F2F)
                t.ramPct > 75f -> Color(0xFFFFA000)
                else -> accentColor
            },
            animationSpec = tween(500), label = "ram_color",
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
                modifier = Modifier.weight(1f).height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
            ) {
                Box(
                    modifier = Modifier.fillMaxHeight()
                        .fillMaxWidth(ramRatio.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(1.dp))
                        .background(ramBarColor),
                )
            }
            Text(
                text = if (t.swapTotalGb > 0.1f)
                    "%.1f/%.0fG · SWAP %.1f/%.0fG".format(
                        t.ramUsedGb, t.ramTotalGb, t.swapUsedGb, t.swapTotalGb,
                    )
                else "%.1f/%.0fG".format(t.ramUsedGb, t.ramTotalGb),
                color = PanelTextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // ── Thermal Profile + Display Style — 1 row side-by-side (compact) ──
        val thermalProfile = rememberThermalProfile(currentGamePackage)
        val profiles = rememberThermalProfiles()
        val relevantProfiles = profiles.filter {
            it.isCustom || it.index in com.ireddragonicy.gamespace.thermal.ThermalProfiles.GAMING_INDICES
        }
        val currentProfileName = relevantProfiles.find { it.index == thermalProfile.value }?.name ?: "Auto"
        val profileColor = getThermalProfileColor(thermalProfile.value)

        // Display style state
        val store = com.ireddragonicy.gamespace.utils.rememberPerAppStore()
        val displayMode = remember(currentGamePackage) {
            mutableIntStateOf(currentGamePackage?.let { store.displayStyle(it) } ?: 0)
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
                    Icon(
                        imageVector = getProfileIcon(thermalProfile.value, currentProfileName),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = profileColor,
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
                    modifier = Modifier.width(140.dp),
                    shape = RoundedCornerShape(12.dp),
                    containerColor = PanelCardBg
                ) {
                    relevantProfiles.forEach { entry ->
                        val isSelected = entry.index == thermalProfile.value
                        val entryColor = getThermalProfileColor(entry.index)
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = getProfileIcon(entry.index, entry.name),
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
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
                                thermalProfile.value = entry.index
                                currentGamePackage?.let { pkg ->
                                    store.setThermalProfile(pkg, entry.index)
                                }
                                thermalExpanded = false
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
                    modifier = Modifier.width(140.dp),
                    shape = RoundedCornerShape(12.dp),
                    containerColor = PanelCardBg
                ) {
                    colorModes.forEach { mode ->
                        val isSelected = displayMode.value == mode.id
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
                                displayMode.value = mode.id
                                currentGamePackage?.let { pkg ->
                                    store.setDisplayStyle(pkg, mode.id)
                                }
                                displayExpanded = false
                            },
                            modifier = Modifier.background(
                                if (isSelected) LocalPanelAccent.current.copy(alpha = 0.9f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            ).height(30.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                    }
                }
            }

            // ── Monitors Dropdown ──
            var monitorExpanded by remember { mutableStateOf(false) }
            val monSettings = tileRepository.monitorSettings
            val monOverlay = tileRepository.monitorOverlayManager

            Box(modifier = Modifier.wrapContentWidth()) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            monitorExpanded = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fps),
                        contentDescription = "Monitors",
                        tint = LocalPanelAccent.current,
                        modifier = Modifier.size(16.dp),
                    )
                }

                if (monSettings != null && monOverlay != null) {
                    DropdownMenu(
                        expanded = monitorExpanded,
                        onDismissRequest = { monitorExpanded = false },
                        modifier = Modifier.width(170.dp),
                        shape = RoundedCornerShape(12.dp),
                        containerColor = PanelCardBg
                    ) {
                        // Ghost Mode
                        DropdownMenuItem(
                            text = { Text("Ghost Mode", color = PanelTextPrimary, fontSize = 10.sp) },
                            trailingIcon = {
                                Switch(
                                    checked = monSettings.isPinned,
                                    onCheckedChange = { 
                                        monSettings.isPinned = it
                                        monOverlay.updateWindowParams()
                                        tileRepository.bringSidebarToFront?.invoke()
                                    },
                                    modifier = Modifier.scale(0.5f)
                                )
                            },
                            onClick = { 
                                monSettings.isPinned = !monSettings.isPinned
                                monOverlay.updateWindowParams()
                                tileRepository.bringSidebarToFront?.invoke()
                            },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                        HorizontalDivider(color = PanelBorder, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 8.dp))
                        
                        // Classical Monitor
                        DropdownMenuItem(
                            text = { Text("Classical Monitor", color = PanelTextPrimary, fontSize = 10.sp) },
                            trailingIcon = {
                                Switch(
                                    checked = monSettings.isClassicalMonitorEnabled,
                                    onCheckedChange = { 
                                        monSettings.isClassicalMonitorEnabled = it
                                        monOverlay.updateWindowParams() 
                                        tileRepository.bringSidebarToFront?.invoke()
                                    },
                                    modifier = Modifier.scale(0.5f)
                                )
                            },
                            onClick = { 
                                val it = !monSettings.isClassicalMonitorEnabled
                                monSettings.isClassicalMonitorEnabled = it
                                monOverlay.updateWindowParams() 
                                tileRepository.bringSidebarToFront?.invoke()
                            },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                        // Mini Monitor
                        DropdownMenuItem(
                            text = { Text("Mini Monitor", color = PanelTextPrimary, fontSize = 10.sp) },
                            trailingIcon = {
                                Switch(
                                    checked = monSettings.isMiniMonitorEnabled,
                                    onCheckedChange = { 
                                        monSettings.isMiniMonitorEnabled = it
                                        monOverlay.updateWindowParams() 
                                        tileRepository.bringSidebarToFront?.invoke()
                                    },
                                    modifier = Modifier.scale(0.5f)
                                )
                            },
                            onClick = { 
                                val it = !monSettings.isMiniMonitorEnabled
                                monSettings.isMiniMonitorEnabled = it
                                monOverlay.updateWindowParams() 
                                tileRepository.bringSidebarToFront?.invoke()
                            },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                        // Processes Monitor
                        DropdownMenuItem(
                            text = { Text("Processes", color = PanelTextPrimary, fontSize = 10.sp) },
                            trailingIcon = {
                                Switch(
                                    checked = monSettings.isProcessesMonitorEnabled,
                                    onCheckedChange = { 
                                        monSettings.isProcessesMonitorEnabled = it
                                        monOverlay.updateWindowParams()
                                        tileRepository.bringSidebarToFront?.invoke()
                                    },
                                    modifier = Modifier.scale(0.5f)
                                )
                            },
                            onClick = { 
                                monSettings.isProcessesMonitorEnabled = !monSettings.isProcessesMonitorEnabled
                                monOverlay.updateWindowParams()
                                tileRepository.bringSidebarToFront?.invoke()
                            },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                        // Temp Monitor
                        DropdownMenuItem(
                            text = { Text("Temperature", color = PanelTextPrimary, fontSize = 10.sp) },
                            trailingIcon = {
                                Switch(
                                    checked = monSettings.isTempMonitorEnabled,
                                    onCheckedChange = { 
                                        monSettings.isTempMonitorEnabled = it
                                        monOverlay.updateWindowParams()
                                        tileRepository.bringSidebarToFront?.invoke()
                                    },
                                    modifier = Modifier.scale(0.5f)
                                )
                            },
                            onClick = { 
                                monSettings.isTempMonitorEnabled = !monSettings.isTempMonitorEnabled
                                monOverlay.updateWindowParams()
                                tileRepository.bringSidebarToFront?.invoke()
                            },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        )
                    }
                }
            }

            // ── Tuner entry pill (icon only) ──
            Box(modifier = Modifier.wrapContentWidth()) {
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
                    "gpu" -> GpuLoadGraph(
                        gpuUsage = t.gpuUsage,
                        gpuFreqMhz = t.gpuFreqMhz,
                        gpuTempC = t.gpuTempC,
                        gpuHeat = t.gpuHeat,
                        history = t.gpuUsageHistory,
                    )
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
