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

    // All thermal profiles = built-in + user-created custom profiles
    val thermalProfileOptions: List<Pair<Int, String>>
        get() {
            val list = builtinProfiles.toMutableList()
            try {
                val json = Settings.System.getStringForUser(
                    context.contentResolver,
                    "mithermal_custom_profiles", UserHandle.USER_CURRENT)
                if (!json.isNullOrEmpty()) {
                    val profiles = org.json.JSONArray(json)
                    for (i in 0 until profiles.length()) {
                        val profile = profiles.getJSONObject(i)
                        val name = profile.getString("name")
                        list.add((customProfileBase + i) to "\u2728 $name")
                    }
                }
            } catch (_: Exception) { /* ignore parse errors */ }
            return list
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

        // Load thermal profile from mithermal_app_profiles
        thermalProfile = loadThermalProfile(pkg)

        // Load GPU composition from DISABLE_HW_OVERLAYS_APPS
        gpuComposition = loadGpuComposition(pkg)

        // Load resolution from gamespace_game_resolution
        resolution = loadResolution(pkg)

        // Load AFME, SGSR, Smooth Motion, VRS, Color Enhance per-game settings
        afmeMultiplier = loadAfmeMultiplier(pkg)
        afmeFactor = loadAfmeFactor(pkg)
        sgsrMode = loadSgsrMode(pkg)
        smoothMotionEnabled = loadSmoothMotion(pkg)
        vrsLevel = loadVrs(pkg)
        colorEnhanceEnabled = loadColorEnhance(pkg)

        // Load Touch Tuning settings
        touchSuperReport = loadBoolPref("gamespace_touch_super_report", pkg, true)
        touchExpertMode = loadBoolPref("gamespace_touch_expert_mode", pkg, false)
        touchExpertPreset = loadIntPref("gamespace_touch_expert_preset", pkg, 1)
        touchThreshold = loadIntPref("gamespace_touch_threshold", pkg, 2)
        touchTolerance = loadIntPref("gamespace_touch_tolerance", pkg, 2)
        touchAimSens = loadIntPref("gamespace_touch_aim_sens", pkg, 2)
        touchTapStab = loadIntPref("gamespace_touch_tap_stab", pkg, 2)
        touchEdgeFilter = loadIntPref("gamespace_touch_edge_filter", pkg, 2)
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

        // Clean up per-app settings
        removeThermalProfile(packageName)
        removeGpuComposition(packageName)
        removeResolution(packageName)
        removeAfmeMultiplier(packageName)
        removeAfmeFactor(packageName)
        removeSgsrMode(packageName)
        removeSmoothMotion(packageName)
        removeVrs(packageName)
        removeColorEnhance(packageName)

        // Clean up Touch settings
        removePref("gamespace_touch_super_report", packageName)
        removePref("gamespace_touch_expert_mode", packageName)
        removePref("gamespace_touch_expert_preset", packageName)
        removePref("gamespace_touch_threshold", packageName)
        removePref("gamespace_touch_tolerance", packageName)
        removePref("gamespace_touch_aim_sens", packageName)
        removePref("gamespace_touch_tap_stab", packageName)
        removePref("gamespace_touch_edge_filter", packageName)
    }

    // ---- Thermal Profile (synced with MiThermal) ----

    fun updateThermalProfile(profileIdx: Int) {
        thermalProfile = profileIdx
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "mithermal_app_profiles", UserHandle.USER_CURRENT) ?: "{}"

        try {
            val obj = JSONObject(json)
            if (profileIdx == 0) {
                obj.remove(packageName) // Follow Global = remove override
            } else {
                obj.put(packageName, profileIdx)
            }
            Settings.System.putStringForUser(resolver,
                "mithermal_app_profiles", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun loadThermalProfile(pkg: String): Int {
        val json = Settings.System.getStringForUser(context.contentResolver,
            "mithermal_app_profiles", UserHandle.USER_CURRENT) ?: return 0
        return try {
            val obj = JSONObject(json)
            if (obj.has(pkg)) obj.getInt(pkg) else 0
        } catch (e: Exception) { 0 }
    }

    private fun removeThermalProfile(pkg: String) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "mithermal_app_profiles", UserHandle.USER_CURRENT) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(pkg)
            Settings.System.putStringForUser(resolver,
                "mithermal_app_profiles", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    // ---- GPU Composition (synced with DisplayManagerService) ----

    fun updateGpuComposition(enabled: Boolean) {
        gpuComposition = enabled
        val resolver = context.contentResolver
        val current = Settings.Secure.getStringForUser(resolver,
            Settings.Secure.DISABLE_HW_OVERLAYS_APPS, UserHandle.USER_CURRENT) ?: ""
        val apps = current.split(",").filter { it.isNotBlank() }.toMutableSet()

        if (enabled) {
            apps.add(packageName)
        } else {
            apps.remove(packageName)
        }

        Settings.Secure.putStringForUser(resolver,
            Settings.Secure.DISABLE_HW_OVERLAYS_APPS,
            apps.joinToString(","), UserHandle.USER_CURRENT)
    }

    private fun loadGpuComposition(pkg: String): Boolean {
        val current = Settings.Secure.getStringForUser(context.contentResolver,
            Settings.Secure.DISABLE_HW_OVERLAYS_APPS, UserHandle.USER_CURRENT) ?: ""
        return current.split(",").contains(pkg)
    }

    private fun removeGpuComposition(pkg: String) {
        val resolver = context.contentResolver
        val current = Settings.Secure.getStringForUser(resolver,
            Settings.Secure.DISABLE_HW_OVERLAYS_APPS, UserHandle.USER_CURRENT) ?: ""
        val apps = current.split(",").filter { it.isNotBlank() && it != pkg }
        Settings.Secure.putStringForUser(resolver,
            Settings.Secure.DISABLE_HW_OVERLAYS_APPS,
            apps.joinToString(","), UserHandle.USER_CURRENT)
    }

    // ---- Resolution (per-game downscale) ----

    fun updateResolution(factor: String) {
        resolution = factor
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_game_resolution", UserHandle.USER_CURRENT) ?: "{}"

        try {
            val obj = JSONObject(json)
            if (factor == "1.0") {
                obj.remove(packageName) // Native = no override
            } else {
                obj.put(packageName, factor)
            }
            Settings.System.putStringForUser(resolver,
                "gamespace_game_resolution", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun loadResolution(pkg: String): String {
        val json = Settings.System.getStringForUser(context.contentResolver,
            "gamespace_game_resolution", UserHandle.USER_CURRENT) ?: return "1.0"
        return try {
            val obj = JSONObject(json)
            if (obj.has(pkg)) obj.getString(pkg) else "1.0"
        } catch (e: Exception) { "1.0" }
    }

    private fun removeResolution(pkg: String) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_game_resolution", UserHandle.USER_CURRENT) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(pkg)
            Settings.System.putStringForUser(resolver,
                "gamespace_game_resolution", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    // ---- AFME Frame Generation (Multiplier) ----

    val afmeMultiplierOptions = listOf(
        0 to "Off",
        2 to "2× Frame Generation",
        3 to "3× Frame Generation",
        4 to "4× Frame Generation"
    )

    fun updateAfmeMultiplier(multiplier: Int) {
        afmeMultiplier = multiplier
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_afme_multiplier", UserHandle.USER_CURRENT) ?: "{}"
        try {
            val obj = JSONObject(json)
            if (multiplier > 0) {
                obj.put(packageName, multiplier)
            } else {
                obj.remove(packageName)
            }
            Settings.System.putStringForUser(resolver,
                "gamespace_afme_multiplier", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }

        // Set system properties that the AFME VK layer reads
        android.os.SystemProperties.set("persist.sys.afme.enable",
            if (multiplier > 0) "1" else "0")
        if (multiplier > 0) {
            android.os.SystemProperties.set("persist.sys.afme.multiplier",
                multiplier.toString())
        }
    }

    private fun loadAfmeMultiplier(pkg: String): Int {
        val json = Settings.System.getStringForUser(context.contentResolver,
            "gamespace_afme_multiplier", UserHandle.USER_CURRENT) ?: return 0
        return try {
            val obj = JSONObject(json)
            if (obj.has(pkg)) obj.getInt(pkg) else 0
        } catch (e: Exception) { 0 }
    }

    private fun removeAfmeMultiplier(pkg: String) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_afme_multiplier", UserHandle.USER_CURRENT) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(pkg)
            Settings.System.putStringForUser(resolver,
                "gamespace_afme_multiplier", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    // ---- AFME Factor (per-game extrapolation strength) ----

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
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_afme_factor", UserHandle.USER_CURRENT) ?: "{}"
        try {
            val obj = JSONObject(json)
            if (factor == "auto") {
                obj.remove(packageName)
            } else {
                obj.put(packageName, factor)
            }
            Settings.System.putStringForUser(resolver,
                "gamespace_afme_factor", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }

        // Set system property for native layer
        if (factor != "auto") {
            android.os.SystemProperties.set("persist.sys.afme.factor", factor)
        } else {
            // Auto: clear the factor override, layer will compute from multiplier
            android.os.SystemProperties.set("persist.sys.afme.factor", "")
        }
    }

    private fun loadAfmeFactor(pkg: String): String {
        val json = Settings.System.getStringForUser(context.contentResolver,
            "gamespace_afme_factor", UserHandle.USER_CURRENT) ?: return "auto"
        return try {
            val obj = JSONObject(json)
            if (obj.has(pkg)) obj.getString(pkg) else "auto"
        } catch (e: Exception) { "auto" }
    }

    private fun removeAfmeFactor(pkg: String) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_afme_factor", UserHandle.USER_CURRENT) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(pkg)
            Settings.System.putStringForUser(resolver,
                "gamespace_afme_factor", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    // ---- Graphics Enhancement mode (per-game) ----
    // 0=off, 1=SGSR1 Spatial, 2=SGSR2 Temporal, 3=MobFGSR Frame Gen

    val sgsrOptions = listOf(
        0 to "Off",
        1 to "SGSR1 — Adaptive Sharpening",
        2 to "SGSR2 — Temporal Upscale (Quality)",
        3 to "MobFGSR — Motion Interpolation (Best)"
    )

    fun updateSgsrMode(mode: Int) {
        sgsrMode = mode
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_sgsr_mode", UserHandle.USER_CURRENT) ?: "{}"
        try {
            val obj = JSONObject(json)
            if (mode > 0) {
                obj.put(packageName, mode)
            } else {
                obj.remove(packageName)
            }
            Settings.System.putStringForUser(resolver,
                "gamespace_sgsr_mode", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }

        // Set system properties for native layer
        android.os.SystemProperties.set("persist.sys.sgsr.enable",
            if (mode > 0) "1" else "0")
        if (mode > 0) {
            android.os.SystemProperties.set("persist.sys.sgsr.mode",
                mode.toString())
        }
    }

    private fun loadSgsrMode(pkg: String): Int {
        val json = Settings.System.getStringForUser(context.contentResolver,
            "gamespace_sgsr_mode", UserHandle.USER_CURRENT) ?: return 0
        return try {
            val obj = JSONObject(json)
            if (obj.has(pkg)) obj.getInt(pkg) else 0
        } catch (e: Exception) { 0 }
    }

    private fun removeSgsrMode(pkg: String) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_sgsr_mode", UserHandle.USER_CURRENT) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(pkg)
            Settings.System.putStringForUser(resolver,
                "gamespace_sgsr_mode", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    // ---- Smooth Motion (vendor.display.use_smooth_motion + framepacing) ----

    fun updateSmoothMotion(enabled: Boolean) {
        smoothMotionEnabled = enabled
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_smooth_motion", UserHandle.USER_CURRENT) ?: "{}"
        try {
            val obj = JSONObject(json)
            obj.put(packageName, enabled)
            Settings.System.putStringForUser(resolver,
                "gamespace_smooth_motion", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }

        // vendor.display.* is sepolicy-protected — apps cannot set it (throws).
        // init.target.rc forwards persist.sys.afme.smooth_motion to
        // vendor.display.use_smooth_motion + vendor.perf.framepacing.enable.
        try {
            android.os.SystemProperties.set("persist.sys.afme.smooth_motion",
                if (enabled) "1" else "0")
        } catch (e: RuntimeException) {
            android.util.Log.w("PerAppSettings", "smooth motion prop denied: ${e.message}")
        }
    }

    private fun loadSmoothMotion(pkg: String): Boolean {
        val json = Settings.System.getStringForUser(context.contentResolver,
            "gamespace_smooth_motion", UserHandle.USER_CURRENT) ?: return true
        return try {
            val obj = JSONObject(json)
            if (obj.has(pkg)) obj.getBoolean(pkg) else true
        } catch (e: Exception) { true }
    }

    private fun removeSmoothMotion(pkg: String) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_smooth_motion", UserHandle.USER_CURRENT) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(pkg)
            Settings.System.putStringForUser(resolver,
                "gamespace_smooth_motion", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    // ---- VRS (Variable Rate Shading) ---- per-game JSON map

    fun updateVrs(level: Int) {
        vrsLevel = level
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_vrs_level", UserHandle.USER_CURRENT) ?: "{}"
        try {
            val obj = JSONObject(json)
            if (level > 0) {
                obj.put(packageName, level)
            } else {
                obj.remove(packageName)
            }
            Settings.System.putStringForUser(resolver,
                "gamespace_vrs_level", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun loadVrs(pkg: String): Int {
        val json = Settings.System.getStringForUser(context.contentResolver,
            "gamespace_vrs_level", UserHandle.USER_CURRENT) ?: return 0
        return try {
            val obj = JSONObject(json)
            if (obj.has(pkg)) obj.getInt(pkg) else 0
        } catch (e: Exception) { 0 }
    }

    private fun removeVrs(pkg: String) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_vrs_level", UserHandle.USER_CURRENT) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(pkg)
            Settings.System.putStringForUser(resolver,
                "gamespace_vrs_level", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    // ---- Game Color Enhance ---- per-game JSON map

    fun updateColorEnhance(enabled: Boolean) {
        colorEnhanceEnabled = enabled
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_color_enhance", UserHandle.USER_CURRENT) ?: "{}"
        try {
            val obj = JSONObject(json)
            if (enabled) {
                obj.put(packageName, true)
            } else {
                obj.remove(packageName)
            }
            Settings.System.putStringForUser(resolver,
                "gamespace_color_enhance", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun loadColorEnhance(pkg: String): Boolean {
        val json = Settings.System.getStringForUser(context.contentResolver,
            "gamespace_color_enhance", UserHandle.USER_CURRENT) ?: return false
        return try {
            val obj = JSONObject(json)
            obj.optBoolean(pkg, false)
        } catch (e: Exception) { false }
    }

    private fun removeColorEnhance(pkg: String) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver,
            "gamespace_color_enhance", UserHandle.USER_CURRENT) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(pkg)
            Settings.System.putStringForUser(resolver,
                "gamespace_color_enhance", obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    // ---- Generic Helpers for Touch Settings ----

    private fun loadBoolPref(key: String, pkg: String, def: Boolean): Boolean {
        val json = Settings.System.getStringForUser(context.contentResolver, key, UserHandle.USER_CURRENT) ?: return def
        return try { JSONObject(json).optBoolean(pkg, def) } catch (e: Exception) { def }
    }

    private fun loadIntPref(key: String, pkg: String, def: Int): Int {
        val json = Settings.System.getStringForUser(context.contentResolver, key, UserHandle.USER_CURRENT) ?: return def
        return try { JSONObject(json).optInt(pkg, def) } catch (e: Exception) { def }
    }

    private fun updatePref(key: String, value: Any) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver, key, UserHandle.USER_CURRENT) ?: "{}"
        try {
            val obj = JSONObject(json)
            obj.put(packageName, value)
            Settings.System.putStringForUser(resolver, key, obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun removePref(key: String, pkg: String) {
        val resolver = context.contentResolver
        val json = Settings.System.getStringForUser(resolver, key, UserHandle.USER_CURRENT) ?: return
        try {
            val obj = JSONObject(json)
            obj.remove(pkg)
            Settings.System.putStringForUser(resolver, key, obj.toString(), UserHandle.USER_CURRENT)
        } catch (e: Exception) { /* ignore */ }
    }

    fun updateTouchSuperReport(value: Boolean) {
        touchSuperReport = value
        updatePref("gamespace_touch_super_report", value)
    }

    fun updateTouchExpertMode(value: Boolean) {
        touchExpertMode = value
        updatePref("gamespace_touch_expert_mode", value)
    }

    fun updateTouchExpertPreset(value: Int) {
        touchExpertPreset = value
        updatePref("gamespace_touch_expert_preset", value)
    }

    fun updateTouchThreshold(value: Int) {
        touchThreshold = value
        updatePref("gamespace_touch_threshold", value)
    }

    fun updateTouchTolerance(value: Int) {
        touchTolerance = value
        updatePref("gamespace_touch_tolerance", value)
    }

    fun updateTouchAimSens(value: Int) {
        touchAimSens = value
        updatePref("gamespace_touch_aim_sens", value)
    }

    fun updateTouchTapStab(value: Int) {
        touchTapStab = value
        updatePref("gamespace_touch_tap_stab", value)
    }

    fun updateTouchEdgeFilter(value: Int) {
        touchEdgeFilter = value
        updatePref("gamespace_touch_edge_filter", value)
    }
}
