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
import androidx.compose.ui.zIndex
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
import com.ireddragonicy.gamespace.data.PerAppSettingStore

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale


import com.ireddragonicy.gamespace.utils.rememberPerAppStore

@Composable
fun rememberThermalProfile(packageName: String?): MutableState<Int> {
    val store = rememberPerAppStore()

    return remember(packageName) {
        if (packageName == null) {
            mutableIntStateOf(0)
        } else {
            mutableIntStateOf(store.thermalProfile(packageName))
        }
    }
}

@Composable
fun GamePanelCard(
    interactor: BrightnessInteractor,
    fpsInteractor: FpsInteractor,
    tileRepository: TileRepository,
    maxHeight: Dp = Dp.Unspecified,
) {
    // Resolve panel accent color from settings (Material You or preset)
    val panelAccent = rememberPanelAccent(
        tileRepository.panelColorMode.intValue,
        tileRepository.panelCustomColor.intValue
    )

    CompositionLocalProvider(LocalPanelAccent provides panelAccent) {
        GamePanelCardInner(interactor, fpsInteractor, tileRepository, maxHeight)
    }
}

@Composable
private fun GamePanelCardInner(
    interactor: BrightnessInteractor,
    fpsInteractor: FpsInteractor,
    tileRepository: TileRepository,
    maxHeight: Dp = Dp.Unspecified,
) {

    val panelWidth = 320.dp
    var headerExpanded by remember { mutableStateOf(false) }
    val time = rememberCurrentTime()

    // Game Turbo style: dark glassmorphism card
    Box(
        modifier = Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .width(panelWidth)
            .wrapContentHeight()
            .background(LocalPanelAccent.current.copy(alpha = 0.05f), chamferShape())
    ) {
        GamePanelContent(
            headerExpanded = headerExpanded,
            onToggleExpand = { headerExpanded = !headerExpanded },
            interactor = interactor, fpsInteractor = fpsInteractor, time = time,
            tileRepository = tileRepository, maxHeight = maxHeight,
        )

        // ── full-window TOUCH LAB overlay (smooth fade + scale) ───────────
        val showLab by tileRepository.touchTesterExpanded
        androidx.compose.animation.AnimatedVisibility(
            visible = showLab,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing), initialScale = 0.92f),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing), targetScale = 0.92f),
            modifier = Modifier.matchParentSize().zIndex(50f),
        ) {
            TouchTesterExpanded(tileRepository) { tileRepository.touchTesterExpanded.value = false }
        }

        // ── BLUETOOTH DEVICES WINDOW (like QS bluetooth panel) ──────
        // NOTE: rendered as an inline page — window dialogs (AlertDialog) are
        // impossible here: the panel lives in an overlay ComposeView whose
        // context (service) has no window token (BadTokenException).
    }
}

@Composable
fun GamePanelContent(
    headerExpanded: Boolean,
    onToggleExpand: () -> Unit,
    interactor: BrightnessInteractor,
    fpsInteractor: FpsInteractor,
    time: String,
    tileRepository: TileRepository,
    maxHeight: Dp = Dp.Unspecified,
) {
    var isEditing by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    val resolvedMaxHeight = if (maxHeight != Dp.Unspecified) maxHeight else {
        (LocalConfiguration.current.screenHeightDp - 64).dp
    }

    var viewportTop by remember { mutableStateOf(0f) }
    var viewportHeight by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = resolvedMaxHeight)
            .onGloballyPositioned {
                viewportTop = it.positionInWindow().y
                viewportHeight = it.size.height.toFloat()
            }
            .verticalScroll(scrollState)
            .padding(top = if (isEditing) 4.dp else 6.dp, start = 12.dp, bottom = 6.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (tileRepository.showPingDetails.value) {
            PingDetailsPanel(
                tileRepository = tileRepository,
                onClose = { tileRepository.showPingDetails.value = false }
            )
        } else if (tileRepository.showPerfTuner.value) {
            TunerScreen(
                tileRepository = tileRepository,
                onClose = { tileRepository.showPerfTuner.value = false }
            )
        } else if (tileRepository.showScreenRecordChooser.value) {
            ScreenRecordChooserPanel(
                onClose = { tileRepository.showScreenRecordChooser.value = false },
                onCurrentGame = {
                    tileRepository.showScreenRecordChooser.value = false
                    tileRepository.toggleScreenRecord(targetGameOnly = true)
                },
                onFullScreen = {
                    tileRepository.showScreenRecordChooser.value = false
                    tileRepository.toggleScreenRecord(targetGameOnly = false)
                },
            )
        } else if (tileRepository.showBluetoothDevices.value) {
            BluetoothDevicesPanel(
                onClose = { tileRepository.showBluetoothDevices.value = false }
            )
        } else if (!isEditing) {
            HeaderInfoBar(
                modifier = Modifier.fillMaxWidth(),
                headerExpanded = headerExpanded,
                time = time,
                fpsInteractor = fpsInteractor,
                onToggleExpand = onToggleExpand,
                onEditClick = { isEditing = true },
                tileRepository = tileRepository,
                currentGamePackage = tileRepository.currentGamePackage,
            )

            PanelContent(
                interactor = interactor,
                tileRepository = tileRepository,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TileEditPanel(
                tileRepository = tileRepository,
                scrollState = scrollState,
                viewportTop = viewportTop,
                viewportHeight = viewportHeight,
                onClose = { isEditing = false }
            )
        }
    }
}

/**
 * Inline screen-record target chooser (replaces the window AlertDialog — see
 * note in GamePanelCardInner).
 */
@Composable
fun ScreenRecordChooserPanel(
    onClose: () -> Unit,
    onCurrentGame: () -> Unit,
    onFullScreen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SCREEN RECORD",
                color = PanelTheme.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(id = R.drawable.materialsymbols_ic_close_rounded_filled),
                contentDescription = "Close",
                tint = PanelTheme.TextDim,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onClose() },
            )
        }
        Text("Pilih area yang ingin direkam:", color = PanelTheme.TextDim, fontSize = 12.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), chamferShape())
                .clickable(onClick = onCurrentGame)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_action_record),
                contentDescription = null,
                tint = LocalPanelAccent.current,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Current Game",
                color = PanelTheme.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(">", color = PanelTheme.TextDim, fontSize = 14.sp)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), chamferShape())
                .clickable(onClick = onFullScreen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_action_record),
                contentDescription = null,
                tint = PanelTheme.TextDim,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Full Screen",
                color = PanelTheme.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(">", color = PanelTheme.TextDim, fontSize = 14.sp)
        }
    }
}
