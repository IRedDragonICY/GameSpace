/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)

package com.ireddragonicy.gamespace.gamebar

import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.thermal.ThermalProfiles

// Thermal profile catalog re-exports
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
    index >= CUSTOM_PROFILE_BASE -> {
        val hue = ((index - CUSTOM_PROFILE_BASE) * 137.508f) % 360f
        Color.hsl(hue, 0.65f, 0.55f)
    }
    else -> Color(0xFF90A4AE)
}

/**
 * Live thermal-profile catalog: built-in scenes + user-created custom profiles,
 * kept in sync with the store via a ContentObserver so a profile created
 * elsewhere shows up without any extra wiring.
 *
 * The list itself is built by [ThermalProfiles.options] — the one builder every
 * picker in the app shares.
 */
@Composable
fun rememberThermalProfiles(): List<ThermalProfiles.ProfileOption> {
    val context = LocalContext.current
    val profiles = remember { mutableStateOf(emptyList<ThermalProfiles.ProfileOption>()) }
    val customProfilesUri = remember {
        Settings.System.getUriFor(THERMAL_CUSTOM_PROFILES_KEY)
    }

    DisposableEffect(Unit) {
        fun reload() {
            profiles.value = ThermalProfiles.options(
                Settings.System.getStringForUser(
                    context.contentResolver,
                    THERMAL_CUSTOM_PROFILES_KEY,
                    UserHandle.USER_CURRENT
                )
            )
        }
        reload()
        val observer = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = reload()
        }
        context.contentResolver.registerContentObserver(customProfilesUri, false, observer)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    return profiles.value
}

data class GameColorMode(
    val id: Int,
    val label: String,
    val iconRes: Int
)

internal val colorModes = listOf(
    GameColorMode(0, "Original", R.drawable.ic_color_original),
    GameColorMode(1, "Vivid", R.drawable.ic_color_vivid),
    GameColorMode(2, "Saturated", R.drawable.ic_color_vibrant),
    GameColorMode(3, "P3", R.drawable.ic_color_bright),
    GameColorMode(4, "sRGB", R.drawable.ic_color_hdr),
)
