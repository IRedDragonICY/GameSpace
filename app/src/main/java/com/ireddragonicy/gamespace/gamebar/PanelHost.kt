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


@Composable
fun rememberThermalProfile(packageName: String?): MutableState<Int> {
    val context = LocalContext.current
    val store = remember { PerAppSettingStore(context) }

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
    apps: List<AppInfo>,
    tileRepository: TileRepository,
    maxHeight: Dp = Dp.Unspecified,
) {
    // Resolve panel accent color from settings (Material You or preset)
    val panelAccent = rememberPanelAccent(tileRepository.panelColorMode.intValue)

    CompositionLocalProvider(LocalPanelAccent provides panelAccent) {
        GamePanelCardInner(interactor, fpsInteractor, apps, tileRepository, maxHeight)
    }
}

@Composable
private fun GamePanelCardInner(
    interactor: BrightnessInteractor,
    fpsInteractor: FpsInteractor,
    apps: List<AppInfo>,
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
            apps = apps, headerExpanded = headerExpanded,
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
    }
}

@Composable
fun GamePanelContent(
    apps: List<AppInfo>,
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = resolvedMaxHeight)
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
                onClose = { isEditing = false }
            )
        }
    }
}
