/*
* Copyright (C) 2026 IRedDragonICY
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*/
package com.ireddragonicy.gamespace.data

import android.content.Context
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import com.ireddragonicy.gamespace.display.DisplayColorManager
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single writer for every per-game setting.
 *
 * Phase 1 goal:
 * ALL per-app writes must go through this class.
 */
@Singleton
class PerAppSettingStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val KEY_AFME_MULTIPLIER = "gamespace_afme_multiplier"
        const val KEY_AFME_FACTOR = "gamespace_afme_factor"
        const val KEY_SGSR_MODE = "gamespace_sgsr_mode"
        const val KEY_SMOOTH_MOTION = "gamespace_smooth_motion"
        const val KEY_VRS_LEVEL = "gamespace_vrs_level"
        const val KEY_RESOLUTION = "gamespace_game_resolution"
        const val KEY_COLOR_ENHANCE = "gamespace_color_enhance"
        const val KEY_DISPLAY_STYLE = "gamespace_display_style"
        const val KEY_GPU_MSAA = "gamespace_gpu_msaa"
        const val KEY_GPU_AF = "gamespace_gpu_af"
        const val KEY_GPU_TEX_QUALITY = "gamespace_gpu_tex_quality"

        const val KEY_TOUCH_SUPER_REPORT = "gamespace_touch_super_report"
        const val KEY_TOUCH_EXPERT_MODE = "gamespace_touch_expert_mode"
        const val KEY_TOUCH_EXPERT_PRESET = "gamespace_touch_expert_preset"
        const val KEY_TOUCH_THRESHOLD = "gamespace_touch_threshold"
        const val KEY_TOUCH_TOLERANCE = "gamespace_touch_tolerance"
        const val KEY_TOUCH_AIM_SENS = "gamespace_touch_aim_sens"
        const val KEY_TOUCH_TAP_STAB = "gamespace_touch_tap_stab"
        const val KEY_TOUCH_EDGE_FILTER = "gamespace_touch_edge_filter"
    }

    private val displayColorManager by lazy { DisplayColorManager.get(context) }

    private fun read(key: String): String? = Settings.System.getStringForUser(
        context.contentResolver, key, UserHandle.USER_CURRENT
    )

    private fun write(key: String, json: String) {
        runCatching {
            Settings.System.putStringForUser(
                context.contentResolver, key, json, UserHandle.USER_CURRENT
            )
        }
    }

    private fun setPropSafe(name: String, value: String) {
        runCatching { SystemProperties.set(name, value) }
    }

    private fun legacyDisplayKey(pkg: String) = "game_color_mode_$pkg"

    // ── AFME ───────────────────────────────────────────────────────
    fun afmeMultiplier(pkg: String): Int =
        PerAppJson.getInt(read(KEY_AFME_MULTIPLIER), pkg, 0)

    fun setAfmeMultiplier(pkg: String, multiplier: Int) {
        write(
            KEY_AFME_MULTIPLIER,
            PerAppJson.put(read(KEY_AFME_MULTIPLIER), pkg, multiplier.takeIf { it > 0 })
        )
        setPropSafe("persist.sys.afme.enable", if (multiplier > 0) "1" else "0")
        if (multiplier > 0) {
            setPropSafe("persist.sys.afme.multiplier", multiplier.toString())
        }
    }

    fun afmeFactor(pkg: String): String =
        PerAppJson.getString(read(KEY_AFME_FACTOR), pkg, "auto")

    fun setAfmeFactor(pkg: String, factor: String) {
        write(
            KEY_AFME_FACTOR,
            PerAppJson.put(read(KEY_AFME_FACTOR), pkg, factor.takeIf { it != "auto" })
        )
        setPropSafe("persist.sys.afme.factor", if (factor == "auto") "" else factor)
    }

    // ── SGSR ───────────────────────────────────────────────────────
    fun sgsrMode(pkg: String): Int =
        PerAppJson.getInt(read(KEY_SGSR_MODE), pkg, 0)

    fun setSgsrMode(pkg: String, mode: Int) {
        write(KEY_SGSR_MODE, PerAppJson.put(read(KEY_SGSR_MODE), pkg, mode.takeIf { it > 0 }))
        setPropSafe("persist.sys.sgsr.enable", if (mode > 0) "1" else "0")
        if (mode > 0) {
            setPropSafe("persist.sys.sgsr.mode", mode.toString())
        }
    }

    // ── Smooth motion ──────────────────────────────────────────────
    fun smoothMotion(pkg: String): Boolean =
        PerAppJson.getBoolean(read(KEY_SMOOTH_MOTION), pkg, false)

    fun setSmoothMotion(pkg: String, enabled: Boolean) {
        write(KEY_SMOOTH_MOTION, PerAppJson.put(read(KEY_SMOOTH_MOTION), pkg, enabled))
        setPropSafe("persist.sys.afme.smooth_motion", if (enabled) "1" else "0")
    }

    // ── VRS ────────────────────────────────────────────────────────
    fun vrsLevel(pkg: String): Int =
        PerAppJson.getInt(read(KEY_VRS_LEVEL), pkg, 0)

    fun setVrsLevel(pkg: String, level: Int) {
        write(KEY_VRS_LEVEL, PerAppJson.put(read(KEY_VRS_LEVEL), pkg, level.takeIf { it > 0 }))
    }

    // ── Resolution ─────────────────────────────────────────────────
    fun resolution(pkg: String): String =
        PerAppJson.getString(read(KEY_RESOLUTION), pkg, "1.0")

    fun setResolution(pkg: String, factor: String) {
        write(
            KEY_RESOLUTION,
            PerAppJson.put(read(KEY_RESOLUTION), pkg, factor.takeIf { it != "1.0" })
        )
    }

    // ── Color enhance ──────────────────────────────────────────────
    fun colorEnhance(pkg: String): Boolean =
        PerAppJson.getBoolean(read(KEY_COLOR_ENHANCE), pkg, false)

    fun setColorEnhance(pkg: String, enabled: Boolean) {
        write(
            KEY_COLOR_ENHANCE,
            PerAppJson.put(read(KEY_COLOR_ENHANCE), pkg, if (enabled) true else null)
        )
    }

    // ── Thermal profile ────────────────────────────────────────────
    fun thermalProfile(pkg: String): Int =
        PerAppJson.getInt(read(ThermalProfiles.KEY_APP_PROFILES), pkg, 0)

    fun setThermalProfile(pkg: String, index: Int) {
        write(
            ThermalProfiles.KEY_APP_PROFILES,
            PerAppJson.put(read(ThermalProfiles.KEY_APP_PROFILES), pkg, index.takeIf { it > 0 })
        )
    }

    // ── GPU composition ────────────────────────────────────────────
    fun gpuComposition(pkg: String): Boolean {
        val current = Settings.Secure.getStringForUser(
            context.contentResolver,
            Settings.Secure.DISABLE_HW_OVERLAYS_APPS,
            UserHandle.USER_CURRENT
        ).orEmpty()
        return current.split(",").any { it == pkg }
    }

    fun setGpuComposition(pkg: String, enabled: Boolean) {
        val current = Settings.Secure.getStringForUser(
            context.contentResolver,
            Settings.Secure.DISABLE_HW_OVERLAYS_APPS,
            UserHandle.USER_CURRENT
        ).orEmpty()

        val set = current.split(",")
            .filter { it.isNotBlank() }
            .toMutableSet()

        if (enabled) set.add(pkg) else set.remove(pkg)

        runCatching {
            Settings.Secure.putStringForUser(
                context.contentResolver,
                Settings.Secure.DISABLE_HW_OVERLAYS_APPS,
                set.joinToString(","),
                UserHandle.USER_CURRENT
            )
        }
    }

    // ── Display style ──────────────────────────────────────────────
    fun displayStyle(pkg: String): Int {
        val json = read(KEY_DISPLAY_STYLE)
        if (PerAppJson.has(json, pkg)) {
            return PerAppJson.getInt(json, pkg, 0)
        }

        // Legacy migration from old per-package int key
        val legacy = runCatching {
            Settings.System.getIntForUser(
                context.contentResolver,
                legacyDisplayKey(pkg),
                0,
                UserHandle.USER_CURRENT
            )
        }.getOrDefault(0)

        if (legacy != 0) {
            write(KEY_DISPLAY_STYLE, PerAppJson.put(json, pkg, legacy))
            return legacy
        }

        return 0
    }

    fun setDisplayStyle(pkg: String, mode: Int) {
        write(
            KEY_DISPLAY_STYLE,
            PerAppJson.put(read(KEY_DISPLAY_STYLE), pkg, mode.takeIf { it > 0 })
        )

        // Apply immediately only if this is the active game
        if (ActiveGameHolder.currentPackage == pkg) {
            displayColorManager.applyStyle(mode)
        }
    }

    // ── Touch tuning ───────────────────────────────────────────────
    fun touchSuperReport(pkg: String): Boolean =
        PerAppJson.getBoolean(read(KEY_TOUCH_SUPER_REPORT), pkg, true)

    fun setTouchSuperReport(pkg: String, value: Boolean) {
        write(KEY_TOUCH_SUPER_REPORT, PerAppJson.put(read(KEY_TOUCH_SUPER_REPORT), pkg, value))
    }

    fun touchExpertMode(pkg: String): Boolean =
        PerAppJson.getBoolean(read(KEY_TOUCH_EXPERT_MODE), pkg, false)

    fun setTouchExpertMode(pkg: String, value: Boolean) {
        write(KEY_TOUCH_EXPERT_MODE, PerAppJson.put(read(KEY_TOUCH_EXPERT_MODE), pkg, value))
    }

    fun touchExpertPreset(pkg: String): Int =
        PerAppJson.getInt(read(KEY_TOUCH_EXPERT_PRESET), pkg, 1)

    fun setTouchExpertPreset(pkg: String, value: Int) {
        write(KEY_TOUCH_EXPERT_PRESET, PerAppJson.put(read(KEY_TOUCH_EXPERT_PRESET), pkg, value))
    }

    fun touchThreshold(pkg: String): Int =
        PerAppJson.getInt(read(KEY_TOUCH_THRESHOLD), pkg, 2)

    fun setTouchThreshold(pkg: String, value: Int) {
        write(KEY_TOUCH_THRESHOLD, PerAppJson.put(read(KEY_TOUCH_THRESHOLD), pkg, value))
    }

    fun touchTolerance(pkg: String): Int =
        PerAppJson.getInt(read(KEY_TOUCH_TOLERANCE), pkg, 2)

    fun setTouchTolerance(pkg: String, value: Int) {
        write(KEY_TOUCH_TOLERANCE, PerAppJson.put(read(KEY_TOUCH_TOLERANCE), pkg, value))
    }

    fun touchAimSens(pkg: String): Int =
        PerAppJson.getInt(read(KEY_TOUCH_AIM_SENS), pkg, 2)

    fun setTouchAimSens(pkg: String, value: Int) {
        write(KEY_TOUCH_AIM_SENS, PerAppJson.put(read(KEY_TOUCH_AIM_SENS), pkg, value))
    }

    fun touchTapStab(pkg: String): Int =
        PerAppJson.getInt(read(KEY_TOUCH_TAP_STAB), pkg, 2)

    fun setTouchTapStab(pkg: String, value: Int) {
        write(KEY_TOUCH_TAP_STAB, PerAppJson.put(read(KEY_TOUCH_TAP_STAB), pkg, value))
    }

    fun touchEdgeFilter(pkg: String): Int =
        PerAppJson.getInt(read(KEY_TOUCH_EDGE_FILTER), pkg, 2)

    fun setTouchEdgeFilter(pkg: String, value: Int) {
        write(KEY_TOUCH_EDGE_FILTER, PerAppJson.put(read(KEY_TOUCH_EDGE_FILTER), pkg, value))
    }

    // ── GPU MSAA, AF & Texture Quality ─────────────────────────────
    fun gpuMsaa(pkg: String): Int =
        PerAppJson.getInt(read(KEY_GPU_MSAA), pkg, 0)

    fun setGpuMsaa(pkg: String, level: Int) {
        write(KEY_GPU_MSAA, PerAppJson.put(read(KEY_GPU_MSAA), pkg, level.takeIf { it > 0 }))
        setPropSafe("persist.sys.gamespace.gpu_msaa", level.toString())
        setPropSafe("debug.egl.force_msaa", level.toString())
    }

    fun gpuAf(pkg: String): Int =
        PerAppJson.getInt(read(KEY_GPU_AF), pkg, 0)

    fun setGpuAf(pkg: String, level: Int) {
        write(KEY_GPU_AF, PerAppJson.put(read(KEY_GPU_AF), pkg, level.takeIf { it > 0 }))
        setPropSafe("persist.sys.gamespace.gpu_af", level.toString())
        setPropSafe("vendor.gputuning.af", level.toString())
    }

    fun gpuTexQuality(pkg: String): Int =
        PerAppJson.getInt(read(KEY_GPU_TEX_QUALITY), pkg, 0)

    fun setGpuTexQuality(pkg: String, quality: Int) {
        write(KEY_GPU_TEX_QUALITY, PerAppJson.put(read(KEY_GPU_TEX_QUALITY), pkg, quality.takeIf { it > 0 }))
        setPropSafe("persist.sys.gamespace.gpu_tex_quality", quality.toString())
        setPropSafe("vendor.gputuning.texture_filter", quality.toString())
    }

    // ── Cleanup ────────────────────────────────────────────────────
    fun removeAllForPackage(pkg: String) {
        listOf(
            KEY_AFME_MULTIPLIER,
            KEY_AFME_FACTOR,
            KEY_SGSR_MODE,
            KEY_SMOOTH_MOTION,
            KEY_VRS_LEVEL,
            KEY_RESOLUTION,
            KEY_COLOR_ENHANCE,
            KEY_DISPLAY_STYLE,
            KEY_GPU_MSAA,
            KEY_GPU_AF,
            KEY_GPU_TEX_QUALITY,
            ThermalProfiles.KEY_APP_PROFILES,
            KEY_TOUCH_SUPER_REPORT,
            KEY_TOUCH_EXPERT_MODE,
            KEY_TOUCH_EXPERT_PRESET,
            KEY_TOUCH_THRESHOLD,
            KEY_TOUCH_TOLERANCE,
            KEY_TOUCH_AIM_SENS,
            KEY_TOUCH_TAP_STAB,
            KEY_TOUCH_EDGE_FILTER,
        ).forEach { key ->
            write(key, PerAppJson.put(read(key), pkg, null))
        }

        setGpuComposition(pkg, false)

        runCatching {
            Settings.System.putStringForUser(
                context.contentResolver,
                legacyDisplayKey(pkg),
                null,
                UserHandle.USER_CURRENT
            )
        }
    }
}
