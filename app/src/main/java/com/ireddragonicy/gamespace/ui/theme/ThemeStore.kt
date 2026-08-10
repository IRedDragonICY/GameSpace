package com.ireddragonicy.gamespace.ui.theme

import androidx.compose.runtime.mutableIntStateOf
import com.ireddragonicy.gamespace.data.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSOT for accent/theme across overlay panel + app screens.
 * Replaces TileRepository.panelColorMode/panelCustomColor mirrors and
 * centralises the AppThemeState bridge.
 */
@Singleton
class ThemeStore @Inject constructor(
    private val appSettings: AppSettings,
) {
    // ── Overlay panel accent ──
    val panelColorMode = mutableIntStateOf(appSettings.panelColorMode)
    val panelCustomColor = mutableIntStateOf(appSettings.panelCustomColor)

    fun setPanelColorMode(mode: Int) {
        panelColorMode.intValue = mode
        appSettings.panelColorMode = mode
    }
    /** persist=false → smooth live preview while dragging HSV. */
    fun setPanelCustomColor(argb: Int, persist: Boolean = true) {
        panelCustomColor.intValue = argb
        if (persist) appSettings.panelCustomColor = argb
    }

    // ── App-wide theme (mirrors AppThemeState so there is one writer) ──
    val appThemeMode get() = AppThemeState.themeMode
    val appColorMode get() = AppThemeState.colorMode
    val appCustomColor get() = AppThemeState.customColor

    fun setAppThemeMode(mode: Int) { appSettings.appThemeMode = mode }
    fun setAppColorMode(mode: Int) { appSettings.appColorMode = mode }
    fun setAppCustomColor(argb: Int, persist: Boolean) =
        appSettings.setAppCustomColor(argb, persist)
}
