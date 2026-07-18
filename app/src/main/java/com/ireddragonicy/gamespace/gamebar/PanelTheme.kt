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

// ── Game Panel Color System ──
// Dark base colors (constant — work with all accent colors)
internal val PanelBackground = Color(0xFF1A1A2E)
internal val PanelSurface = Color(0xFF16162B)
internal val PanelRed = Color(0xFFFF001F)        // Performance mode accent
internal val PanelTextPrimary = Color.White.copy(alpha = 0.95f)
internal val PanelTextSecondary = Color.White.copy(alpha = 0.50f)
internal val PanelCardBg = Color(0xFF222244)
internal val PanelBorder = Color.White.copy(alpha = 0.08f)

// Accent color presets (index 1-8, index 0 = Material You dynamic)
internal val ACCENT_PRESETS = arrayOf(
    Color(0xFF3CEDFF),  // 1: Cyan (Classic Game Turbo)
    Color(0xFF448AFF),  // 2: Electric Blue
    Color(0xFF00E676),  // 3: Emerald Green
    Color(0xFFFF6D00),  // 4: Sunset Orange
    Color(0xFFB388FF),  // 5: Royal Purple
    Color(0xFFFF1744),  // 6: Cherry Red
    Color(0xFFFFD600),  // 7: Golden Yellow
    Color(0xFFE0E0E0),  // 8: Arctic White
)

// Legacy alias — used as fallback when Material You is unavailable
private val PanelCyan = ACCENT_PRESETS[0]

/** CompositionLocal for panel accent color — set once at GamePanelCard root. */
val LocalPanelAccent = compositionLocalOf { PanelCyan }

/** Resolves the panel accent color based on panelColorMode setting. */
@Composable
fun rememberPanelAccent(panelColorMode: Int): Color {
    return if (panelColorMode == 0) {
        // Material You — follow device wallpaper / system theme
        MaterialTheme.colorScheme.primary
    } else {
        // Preset (1-indexed into ACCENT_PRESETS)
        ACCENT_PRESETS.getOrElse(panelColorMode - 1) { ACCENT_PRESETS[0] }
    }
}

/**
 * ROG-inspired design language for the game overlay — angular chamfered
 * silhouettes, deep translucent glass, neon edge glow and segmented gauge
 * rings (Armoury Crate / Game Genie aesthetic).
 *
 * Rendering budget: every widget here is a single Canvas draw pass — no
 * shadows, no RenderEffect, no offscreen layers — so the overlay stays
 * jank-free on top of a running game.
 */
object PanelTheme {
    val BaseDeep = Color(0xFF07090F) // panel bottom
    val BaseDark = Color(0xFF10141D) // panel top
    val TextPrimary = Color.White.copy(alpha = 0.95f)
    val TextDim = Color.White.copy(alpha = 0.50f)
    val Danger = Color(0xFFFF2A44)
    val Warn = Color(0xFFFFA000)
}

/**
 * Signature ROG silhouette: aggressive chamfer on top-end & bottom-start,
 * tight chamfer on the remaining corners.
 */
fun chamferShape(bigCut: Dp = 18.dp, smallCut: Dp = 7.dp): Shape = object : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val b = with(density) { bigCut.toPx() }.coerceAtMost(size.minDimension / 4f)
        val s = with(density) { smallCut.toPx() }.coerceAtMost(size.minDimension / 8f)
        val path = Path().apply {
            moveTo(s, 0f)
            lineTo(size.width - b, 0f)
            lineTo(size.width, b)
            lineTo(size.width, size.height - s)
            lineTo(size.width - s, size.height)
            lineTo(b, size.height)
            lineTo(0f, size.height - b)
            lineTo(0f, s)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Dark glass gradient used behind panel content. */
fun glassBrush(accent: Color): Brush = Brush.verticalGradient(
    colors = listOf(
        PanelTheme.BaseDark.copy(alpha = 0.86f).compositeOverAccent(accent, 0.05f),
        PanelTheme.BaseDeep.copy(alpha = 0.94f),
    )
)

private fun Color.compositeOverAccent(accent: Color, amount: Float): Color =
    Color(
        red = red + (accent.red - red) * amount,
        green = green + (accent.green - green) * amount,
        blue = blue + (accent.blue - blue) * amount,
        alpha = alpha,
    )
