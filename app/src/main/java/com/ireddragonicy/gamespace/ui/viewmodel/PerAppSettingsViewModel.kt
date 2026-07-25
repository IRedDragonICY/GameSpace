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
package com.ireddragonicy.gamespace.ui.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.*
import com.ireddragonicy.gamespace.utils.GameModeUtils
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class PerAppSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val systemSettings: SystemSettings,
    private val gameModeUtils: GameModeUtils
) : ViewModel() {

    private val store = PerAppSettingStore(context)

    var packageName by mutableStateOf("")
        private set

    var gameLabel by mutableStateOf("")
        private set

    var gameIcon by mutableStateOf<Drawable?>(null)
        private set


    var angleDriverChoice by mutableStateOf(GameModeUtils.DRIVER_CHOICE_DEFAULT)
        private set

    var angleFeatureAvailable by mutableStateOf(false)
        private set

    // Display style (per-game, shared with the in-game panel header)
    var displayStyle by mutableIntStateOf(0)
        private set

    // Thermal profile (synced with MiThermal mithermal_app_profiles)
    var thermalProfile by mutableIntStateOf(0)
        private set

    // GPU composition (synced with DISABLE_HW_OVERLAYS_APPS)
    var gpuComposition by mutableStateOf(false)
        private set

    // Resolution downscale factor
    var resolution by mutableStateOf("1.0")
        private set

    // AFME Frame Generation multiplier (per-game: 0=off, 2=2x, 3=3x, 4=4x)
    var afmeMultiplier by mutableIntStateOf(0)
        private set

    // AFME Extrapolation factor override (per-game: "auto", "0.25", "0.33", "0.5", "0.67", "0.75", "1.0")
    var afmeFactor by mutableStateOf("auto")
        private set

    // Graphics Enhancement mode (per-game: 0=off, 1=SGSR1 Sharpening, 2=SGSR2 Temporal, 3=MobFGSR)
    var sgsrMode by mutableIntStateOf(0)
        private set

    // Smooth Motion (vendor.display.use_smooth_motion + vendor.perf.framepacing.enable)
    var smoothMotionEnabled by mutableStateOf(true)
        private set

    // VRS (Variable Rate Shading) level: 0=off, 1=2x1, 2=2x2, 3=4x4
    var vrsLevel by mutableIntStateOf(0)
        private set

    // Game Color Enhance (per-game toggle)
    var colorEnhanceEnabled by mutableStateOf(false)
        private set

    // GPU MSAA (Anti-Aliasing): 0=Off, 2=2x, 4=4x
    var gpuMsaa by mutableIntStateOf(0)
        private set

    // GPU AF (Anisotropic Filtering): 0=Off, 2=2x, 4=4x, 8=8x, 16=16x
    var gpuAf by mutableIntStateOf(0)
        private set

    // GPU Texture Quality: 0=Default, 1=Speed, 2=Balanced, 3=Quality
    var gpuTexQuality by mutableIntStateOf(0)
        private set

    // Touch Tuning (per-game)
    var touchSuperReport by mutableStateOf(true)
        private set
    var touchExpertMode by mutableStateOf(false)
        private set
    var touchExpertPreset by mutableIntStateOf(1)
        private set
    var touchThreshold by mutableIntStateOf(2)
        private set
    var touchTolerance by mutableIntStateOf(2)
        private set
    var touchAimSens by mutableIntStateOf(2)
        private set
    var touchTapStab by mutableIntStateOf(2)
        private set
    var touchEdgeFilter by mutableIntStateOf(2)
        private set

    // Built-in vendor profiles (indices 0..16)
    private val builtinProfiles = listOf(
        0 to "Follow Global",
        1 to "Default",
        2 to "Performance",
        3 to "Heavy Gaming",
        4 to "Light Gaming",
        5 to "Genshin Impact",
        6 to "Honkai Star Rail",
        7 to "High FPS",
        8 to "Camera",
        9 to "4K Recording",
        10 to "Video Playback",
        11 to "Video Chat",
        12 to "Navigation",
        13 to "Phone Call",
        14 to "AR/VR",
        15 to "Data Transfer",
        16 to "Battery Saver"
    )

    // Custom profile base index (must match MiThermalService.CUSTOM_PROFILE_BASE)
    private val customProfileBase = 1000

    // All thermal profiles = built-in + user-created custom profiles.
    //
    // Backed by Compose STATE (not a getter) on purpose: when the user creates
    // a brand-new custom profile from the thermal picker and returns, we
    // auto-select it, and the row + sheet must reflect it immediately. A plain
    // getter would stay stale until some unrelated recomposition fired, so a
    // freshly-saved profile could fail to even show up.
    var thermalProfileOptions by mutableStateOf<List<Pair<Int, String>>>(emptyList())

    /** id -> dropdown index for custom profiles, rebuilt alongside the options. */
    private var customIdToIndex: Map<String, Int> = emptyMap()

    private fun computeThermalOptions(): List<Pair<Int, String>> {
        val list = builtinProfiles.toMutableList()
        val idMap = mutableMapOf<String, Int>()
        try {
            val json = Settings.System.getStringForUser(
                context.contentResolver,
                "mithermal_custom_profiles", UserHandle.USER_CURRENT)
            if (!json.isNullOrEmpty()) {
                val profiles = org.json.JSONArray(json)
                for (i in 0 until profiles.length()) {
                    val profile = profiles.getJSONObject(i)
                    val name = profile.optString("name", "")
                    val idx = customProfileBase + i
                    // No emoji: the UI draws a Material icon for custom profiles,
                    // exactly like the in-game side panel.
                    list.add(idx to name)
                    val id = profile.optString("id", "")
                    if (id.isNotEmpty()) idMap[id] = idx
                }
            }
        } catch (_: Exception) { /* ignore parse errors */ }
        customIdToIndex = idMap
        return list
    }

    /** Re-read custom profiles from Settings into [thermalProfileOptions]. */
    fun refreshThermalOptions() {
        thermalProfileOptions = computeThermalOptions()
    }

    /**
     * Select a custom profile by its stable id (the editor hands this back on
     * RESULT_OK). Resolves the id to its current array index, refreshes the
     * dropdown and applies it to this game in one shot — so a profile the user
     * just created is both *visible* and *active* when they return, with no
     * second trip through the picker.
     */
    fun selectCustomProfileById(id: String): Boolean {
        refreshThermalOptions()
        val idx = customIdToIndex[id] ?: return false
        updateThermalProfile(idx)
        return true
    }

    // Eager first fill so the sheet is never empty on first composition
    // (also covers the rare case where loadGame() bails out early).
    init {
        refreshThermalOptions()
    }

    // Dynamic resolution options — reads native display size from WMS
    val resolutionOptions: List<Pair<String, String>> by lazy {
        val nativeW: Int
        val nativeH: Int
        try {
            val wm = android.view.WindowManagerGlobal.getWindowManagerService()
                ?: return@lazy listOf("1.0" to "Native")
            val size = android.graphics.Point()
            wm.getInitialDisplaySize(android.view.Display.DEFAULT_DISPLAY, size)
            nativeW = size.x
            nativeH = size.y
        } catch (e: Exception) {
            return@lazy listOf("1.0" to "Native")
        }

        val scales = listOf(
            2.0   to "Ultra Sharp (2×)",
            1.5   to "Super Sharp (1.5×)",
            1.25  to "Sharp (1.25×)",
            1.0   to "Native",
            0.85  to "High",
            0.75  to "Medium-High",
            0.65  to "Medium",
            0.5   to "Low",
            0.35  to "Very Low",
            0.25  to "Ultra Low",
        )
        scales.map { (factor, label) ->
            val w = (nativeW * factor).toInt()
            val h = (nativeH * factor).toInt()
            factor.toString() to "$label (${w}×${h})"
        }
    }

    fun loadGame(pkg: String) {
        if (pkg.isEmpty()) return
        packageName = pkg

        try {
            val pm = context.packageManager
            val flags = PackageManager.ApplicationInfoFlags.of(0)
            val appInfo = pm.getApplicationInfo(pkg, flags)
            gameLabel = appInfo.loadLabel(pm).toString()
            gameIcon = appInfo.loadIcon(pm)
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }


        val hasAngle = gameModeUtils.findAnglePackage()?.isEnabled == true
        val hasVulkan = gameModeUtils.isVulkanSupported()
        angleFeatureAvailable = hasAngle && hasVulkan
        angleDriverChoice = gameModeUtils.getAngleDriverChoice(pkg)

        displayStyle = store.displayStyle(pkg)
        thermalProfile = store.thermalProfile(pkg)
        gpuComposition = store.gpuComposition(pkg)
        resolution = store.resolution(pkg)
        afmeMultiplier = store.afmeMultiplier(pkg)
        afmeFactor = store.afmeFactor(pkg)
        sgsrMode = store.sgsrMode(pkg)
        smoothMotionEnabled = store.smoothMotion(pkg)
        vrsLevel = store.vrsLevel(pkg)
        colorEnhanceEnabled = store.colorEnhance(pkg)
        gpuMsaa = store.gpuMsaa(pkg)
        gpuAf = store.gpuAf(pkg)
        gpuTexQuality = store.gpuTexQuality(pkg)

        touchSuperReport = store.touchSuperReport(pkg)
        touchExpertMode = store.touchExpertMode(pkg)
        touchExpertPreset = store.touchExpertPreset(pkg)
        touchThreshold = store.touchThreshold(pkg)
        touchTolerance = store.touchTolerance(pkg)
        touchAimSens = store.touchAimSens(pkg)
        touchTapStab = store.touchTapStab(pkg)
        touchEdgeFilter = store.touchEdgeFilter(pkg)
        refreshThermalOptions()
    }


    val angleDriverOptions = listOf(
        GameModeUtils.DRIVER_CHOICE_DEFAULT to "Default",
        GameModeUtils.DRIVER_CHOICE_ANGLE to "ANGLE",
        GameModeUtils.DRIVER_CHOICE_NATIVE to "Native"
    )

    fun updateAngleDriverChoice(choice: String) {
        angleDriverChoice = choice
        gameModeUtils.setAngleDriverChoice(packageName, choice)
    }

    // VRS options for per-app picker
    val vrsOptions = listOf(
        0 to "Off",
        1 to "Conservative (2×1) — ~25% GPU save",
        2 to "Aggressive (2×2) — ~50% GPU save",
        3 to "Ultra (4×4 edges) — ~60% GPU save"
    )

    fun unregisterGame() {
        gameModeUtils.setAngleDriverChoice(packageName, GameModeUtils.DRIVER_CHOICE_DEFAULT)
        val games = systemSettings.userGames.toMutableList()
        games.removeIf { it.packageName == packageName }
        systemSettings.userGames = games

        store.removeAllForPackage(packageName)
    }

    // ---- Thermal Profile ----
    fun updateThermalProfile(profileIdx: Int) {
        thermalProfile = profileIdx
        store.setThermalProfile(packageName, profileIdx)
    }

    // ---- Display Style ----
    fun updateDisplayStyle(mode: Int) {
        displayStyle = mode
        store.setDisplayStyle(packageName, mode)
    }

    // ---- GPU Composition ----
    fun updateGpuComposition(enabled: Boolean) {
        gpuComposition = enabled
        store.setGpuComposition(packageName, enabled)
    }

    // ---- Resolution ----
    fun updateResolution(factor: String) {
        resolution = factor
        store.setResolution(packageName, factor)
    }

    // ---- GPU MSAA & AF ----
    val gpuMsaaOptions = listOf(
        0 to "Off",
        2 to "2× MSAA",
        4 to "4× MSAA"
    )

    fun updateGpuMsaa(level: Int) {
        gpuMsaa = level
        store.setGpuMsaa(packageName, level)
    }

    val gpuAfOptions = listOf(
        0 to "Off",
        2 to "2× AF",
        4 to "4× AF",
        8 to "8× AF",
        16 to "16× AF"
    )

    fun updateGpuAf(level: Int) {
        gpuAf = level
        store.setGpuAf(packageName, level)
    }

    val gpuTexQualityOptions = listOf(
        0 to "Default",
        1 to "Speed (Bilinear)",
        2 to "Balanced",
        3 to "Quality (Trilinear)"
    )

    fun updateGpuTexQuality(quality: Int) {
        gpuTexQuality = quality
        store.setGpuTexQuality(packageName, quality)
    }

    // ---- AFME ----
    val afmeMultiplierOptions = listOf(
        0 to "Off",
        2 to "2× Frame Generation",
        3 to "3× Frame Generation",
        4 to "4× Frame Generation"
    )

    fun updateAfmeMultiplier(multiplier: Int) {
        afmeMultiplier = multiplier
        store.setAfmeMultiplier(packageName, multiplier)
    }

    val afmeFactorOptions = listOf(
        "auto" to "Auto (recommended)",
        "0.25" to "0.25 — Subtle",
        "0.33" to "0.33 — Light",
        "0.5" to "0.5 — Moderate",
        "0.67" to "0.67 — Strong",
        "0.75" to "0.75 — Aggressive",
        "1.0" to "1.0 — Maximum"
    )

    fun updateAfmeFactor(factor: String) {
        afmeFactor = factor
        store.setAfmeFactor(packageName, factor)
    }

    // ---- SGSR ----
    val sgsrOptions = listOf(
        0 to "Off",
        1 to "SGSR1 — Adaptive Sharpening",
        2 to "SGSR2 — Temporal Upscale (Quality)",
        3 to "MobFGSR — Motion Interpolation (Best)"
    )

    fun updateSgsrMode(mode: Int) {
        sgsrMode = mode
        store.setSgsrMode(packageName, mode)
    }

    // ---- Smooth Motion ----
    fun updateSmoothMotion(enabled: Boolean) {
        smoothMotionEnabled = enabled
        store.setSmoothMotion(packageName, enabled)
    }

    // ---- VRS ----
    fun updateVrs(level: Int) {
        vrsLevel = level
        store.setVrsLevel(packageName, level)
    }

    // ---- Color Enhance ----
    fun updateColorEnhance(enabled: Boolean) {
        colorEnhanceEnabled = enabled
        store.setColorEnhance(packageName, enabled)
    }

    // ---- Touch Settings ----
    fun updateTouchSuperReport(value: Boolean) {
        touchSuperReport = value
        store.setTouchSuperReport(packageName, value)
    }

    fun updateTouchExpertMode(value: Boolean) {
        touchExpertMode = value
        store.setTouchExpertMode(packageName, value)
    }

    fun updateTouchExpertPreset(value: Int) {
        touchExpertPreset = value
        store.setTouchExpertPreset(packageName, value)
    }

    fun updateTouchThreshold(value: Int) {
        touchThreshold = value
        store.setTouchThreshold(packageName, value)
    }

    fun updateTouchTolerance(value: Int) {
        touchTolerance = value
        store.setTouchTolerance(packageName, value)
    }

    fun updateTouchAimSens(value: Int) {
        touchAimSens = value
        store.setTouchAimSens(packageName, value)
    }

    fun updateTouchTapStab(value: Int) {
        touchTapStab = value
        store.setTouchTapStab(packageName, value)
    }

    fun updateTouchEdgeFilter(value: Int) {
        touchEdgeFilter = value
        store.setTouchEdgeFilter(packageName, value)
    }
}
