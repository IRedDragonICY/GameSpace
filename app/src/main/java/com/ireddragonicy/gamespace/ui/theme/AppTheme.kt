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
package com.ireddragonicy.gamespace.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/** Theme mode seluruh app (activity). Overlay in-game TIDAK ikut — dia dark by design. */
object AppThemeMode {
    const val AUTO = 0    // ikut sistem
    const val LIGHT = 1
    const val DARK = 2
}

/**
 * Skin state observable process-global untuk semua activity.
 *
 * AppSettings satu-satunya writer: setiap setter persist ke SharedPreferences
 * DAN push ke sini, jadi activity mana pun yang membaca ini di composition
 * langsung re-skin saat itu juga — tanpa restart, tanpa observer plumbing.
 * Pola yang sama dengan TileRepository.panelColorMode / afmeRevision.
 *
 * [GameSpaceTheme] membaca ketiganya sebagai default parameter, sehingga
 * pembacaannya otomatis terdaftar sebagai subscription composition.
 */
object AppThemeState {
    const val DEFAULT_CUSTOM_COLOR = 0xFF3CEDFF.toInt()

    var themeMode by mutableIntStateOf(AppThemeMode.AUTO)
        internal set
    var colorMode by mutableIntStateOf(0)
        internal set
    var customColor by mutableIntStateOf(DEFAULT_CUSTOM_COLOR)
        internal set

    /** Sinkronisasi sekali dari prefs saat process boot (GameSpace.onCreate). */
    fun sync(themeMode: Int, colorMode: Int, customColor: Int) {
        this.themeMode = themeMode
        this.colorMode = colorMode
        this.customColor = customColor
    }
}

/** Preset accent app — satu keluarga dengan ACCENT_PRESETS milik panel. */
val APP_ACCENT_PRESETS = arrayOf(
    Color(0xFF3CEDFF),  // 1: Cyan (Classic Game Turbo)
    Color(0xFF448AFF),  // 2: Electric Blue
    Color(0xFF00E676),  // 3: Emerald Green
    Color(0xFFFF6D00),  // 4: Sunset Orange
    Color(0xFFB388FF),  // 5: Royal Purple
    Color(0xFFFF1744),  // 6: Cherry Red
    Color(0xFFFFD600),  // 7: Golden Yellow
    Color(0xFFE0E0E0),  // 8: Arctic White
)

/** 0 = Material You, 1..size = preset, size+1 = custom. Konvensi sama dgn panel. */
val APP_COLOR_MODE_CUSTOM: Int get() = APP_ACCENT_PRESETS.size + 1

/**
 * Dark/light efektif untuk skin saat ini — dipakai view yang butuh palette
 * semantik di luar MaterialTheme (mis. chip status Diagnostics).
 */
@Composable
fun appIsDarkTheme(): Boolean = when (AppThemeState.themeMode) {
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
    else -> isSystemInDarkTheme()
}

/**
 * Theme wrapper seluruh app. Satu-satunya tempat colorScheme diputuskan:
 *  - colorMode 0            → Material You (dynamic)
 *  - colorMode 1..8         → preset [APP_ACCENT_PRESETS]
 *  - colorMode CUSTOM       → warna user, di-derive jadi scheme penuh
 *  - themeMode AUTO/LIGHT/DARK → memaksa atau mengikuti sistem
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GameSpaceTheme(
    themeMode: Int = AppThemeState.themeMode,
    colorMode: Int = AppThemeState.colorMode,
    customColor: Int = AppThemeState.customColor,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        colorMode == 0 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        colorMode == APP_COLOR_MODE_CUSTOM ->
            seedToScheme(Color(customColor), dark)
        else ->
            seedToScheme(
                APP_ACCENT_PRESETS.getOrElse(colorMode - 1) { APP_ACCENT_PRESETS[0] },
                dark,
            )
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

private fun hsvColor(h: Float, s: Float, v: Float): Color = Color(
    AndroidColor.HSVToColor(
        floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    )
)

/**
 * Derivasi ColorScheme Material 3 penuh dari satu warna seed.
 *
 * Tonal palette didekati lewat HSV: container = seed yang didesaturasi dan
 * didorong ke arah netral surface; on-color dibalik berdasarkan luminance.
 * Hue tertiary digeser +35° supaya tidak monokromatik.
 */
fun seedToScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsv = FloatArray(3).also { AndroidColor.colorToHSV(seed.toArgb(), it) }
    val h = hsv[0]
    val s = hsv[1].coerceAtLeast(0.08f)   // seed "putih" pun tetap dapat sedikit hue
    val onSeed = if (seed.luminance() > 0.45f) Color(0xFF16181D) else Color.White
    val hTert = h + 35f

    return if (dark) {
        darkColorScheme(
            primary = seed,
            onPrimary = onSeed,
            primaryContainer = hsvColor(h, s * 0.55f, 0.30f),
            onPrimaryContainer = hsvColor(h, s * 0.30f, 0.93f),
            inversePrimary = hsvColor(h, s * 0.55f, 0.55f),
            secondary = hsvColor(h, s * 0.40f, 0.74f),
            onSecondary = Color(0xFF16181D),
            secondaryContainer = hsvColor(h, s * 0.28f, 0.22f),
            onSecondaryContainer = hsvColor(h, s * 0.16f, 0.90f),
            tertiary = hsvColor(hTert, s * 0.50f, 0.78f),
            onTertiary = Color(0xFF16181D),
            tertiaryContainer = hsvColor(hTert, s * 0.32f, 0.24f),
            onTertiaryContainer = hsvColor(hTert, s * 0.18f, 0.92f),
            background = hsvColor(h, 0.05f, 0.07f),
            onBackground = hsvColor(h, 0.03f, 0.92f),
            surface = hsvColor(h, 0.05f, 0.07f),
            onSurface = hsvColor(h, 0.03f, 0.92f),
            surfaceVariant = hsvColor(h, 0.07f, 0.14f),
            onSurfaceVariant = hsvColor(h, 0.05f, 0.78f),
            surfaceTint = seed,
            inverseSurface = hsvColor(h, 0.03f, 0.90f),
            inverseOnSurface = hsvColor(h, 0.05f, 0.12f),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            outline = hsvColor(h, 0.07f, 0.38f),
            outlineVariant = hsvColor(h, 0.07f, 0.24f),
            scrim = Color.Black,
            surfaceBright = hsvColor(h, 0.045f, 0.24f),
            surfaceDim = hsvColor(h, 0.06f, 0.055f),
            surfaceContainerLowest = hsvColor(h, 0.06f, 0.045f),
            surfaceContainerLow = hsvColor(h, 0.055f, 0.09f),
            surfaceContainer = hsvColor(h, 0.05f, 0.11f),
            surfaceContainerHigh = hsvColor(h, 0.045f, 0.135f),
            surfaceContainerHighest = hsvColor(h, 0.04f, 0.16f),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = onSeed,
            primaryContainer = hsvColor(h, s * 0.42f, 0.95f),
            onPrimaryContainer = hsvColor(h, s * 0.60f, 0.22f),
            inversePrimary = hsvColor(h, s * 0.45f, 0.85f),
            secondary = hsvColor(h, s * 0.38f, 0.52f),
            onSecondary = Color.White,
            secondaryContainer = hsvColor(h, s * 0.28f, 0.93f),
            onSecondaryContainer = hsvColor(h, s * 0.42f, 0.18f),
            tertiary = hsvColor(hTert, s * 0.45f, 0.50f),
            onTertiary = Color.White,
            tertiaryContainer = hsvColor(hTert, s * 0.30f, 0.94f),
            onTertiaryContainer = hsvColor(hTert, s * 0.45f, 0.20f),
            background = hsvColor(h, 0.025f, 0.985f),
            onBackground = hsvColor(h, 0.05f, 0.12f),
            surface = hsvColor(h, 0.025f, 0.985f),
            onSurface = hsvColor(h, 0.05f, 0.12f),
            surfaceVariant = hsvColor(h, 0.045f, 0.925f),
            onSurfaceVariant = hsvColor(h, 0.05f, 0.30f),
            surfaceTint = seed,
            inverseSurface = hsvColor(h, 0.05f, 0.20f),
            inverseOnSurface = hsvColor(h, 0.03f, 0.95f),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            outline = hsvColor(h, 0.05f, 0.50f),
            outlineVariant = hsvColor(h, 0.045f, 0.80f),
            scrim = Color.Black,
            surfaceBright = hsvColor(h, 0.025f, 0.985f),
            surfaceDim = hsvColor(h, 0.04f, 0.87f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsvColor(h, 0.03f, 0.965f),
            surfaceContainer = hsvColor(h, 0.035f, 0.95f),
            surfaceContainerHigh = hsvColor(h, 0.04f, 0.93f),
            surfaceContainerHighest = hsvColor(h, 0.045f, 0.91f),
        )
    }
}
