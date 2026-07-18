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
package com.ireddragonicy.gamespace.data

import android.content.Context
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings

/**
 * Single writer for every per-game graphics/frame setting.
 *
 * Each feature is one per-app JSON map in Settings.System (see [PerAppJson])
 * plus, where a native layer consumes it, the matching SystemProperties
 * side-effect. The in-game panel tabs and the per-app settings screen both go
 * through here so the two UIs can never drift apart.
 *
 * Side-effect note: properties reflect the setting for the CURRENTLY RUNNING
 * game, so setters take effect immediately for the active session — identical
 * to the historical PerAppSettingsViewModel behavior.
 */
class PerAppSettingStore(private val context: Context) {

    private companion object {
        const val KEY_AFME_MULTIPLIER = "gamespace_afme_multiplier"
        const val KEY_AFME_FACTOR = "gamespace_afme_factor"
        const val KEY_SGSR_MODE = "gamespace_sgsr_mode"
        const val KEY_SMOOTH_MOTION = "gamespace_smooth_motion"
        const val KEY_VRS_LEVEL = "gamespace_vrs_level"
        const val KEY_RESOLUTION = "gamespace_game_resolution"
        const val KEY_COLOR_ENHANCE = "gamespace_color_enhance"
    }

    private fun read(key: String): String? = Settings.System.getStringForUser(
        context.contentResolver, key, UserHandle.USER_CURRENT
    )

    private fun write(key: String, json: String) {
        try {
            Settings.System.putStringForUser(
                context.contentResolver, key, json, UserHandle.USER_CURRENT
            )
        } catch (_: Exception) {
        }
    }

    /**
     * SystemProperties.set THROWS when sepolicy denies the write (e.g. vendor.*
     * props from an app domain). Settings state must still be saved, so prop
     * side-effects are best-effort — a denied prop must never crash the panel.
     */
    private fun setPropSafe(name: String, value: String) {
        try {
            SystemProperties.set(name, value)
        } catch (e: RuntimeException) {
            android.util.Log.w("PerAppSettingStore", "cannot set $name: ${e.message}")
        }
    }

    // ── AFME frame generation ──────────────────────────────────────────

    /** 0 = off; 2/3/4 = frame-gen multiplier. */
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

    /** "auto" or a fixed extrapolation factor ("0.25".."1.0"). */
    fun afmeFactor(pkg: String): String =
        PerAppJson.getString(read(KEY_AFME_FACTOR), pkg, "auto")

    fun setAfmeFactor(pkg: String, factor: String) {
        write(
            KEY_AFME_FACTOR,
            PerAppJson.put(read(KEY_AFME_FACTOR), pkg, factor.takeIf { it != "auto" })
        )
        setPropSafe("persist.sys.afme.factor", if (factor == "auto") "" else factor)
    }

    // ── Graphics enhancement (SGSR upscaler) ───────────────────────────

    /** 0 = off, 1 = SGSR1, 2 = SGSR2, 3 = MobFGSR. */
    fun sgsrMode(pkg: String): Int = PerAppJson.getInt(read(KEY_SGSR_MODE), pkg, 0)

    fun setSgsrMode(pkg: String, mode: Int) {
        write(KEY_SGSR_MODE, PerAppJson.put(read(KEY_SGSR_MODE), pkg, mode.takeIf { it > 0 }))
        setPropSafe("persist.sys.sgsr.enable", if (mode > 0) "1" else "0")
        if (mode > 0) {
            setPropSafe("persist.sys.sgsr.mode", mode.toString())
        }
    }

    // ── Smooth motion (frame pacing) ───────────────────────────────────

    fun smoothMotion(pkg: String): Boolean =
        PerAppJson.getBoolean(read(KEY_SMOOTH_MOTION), pkg, false)

    fun setSmoothMotion(pkg: String, enabled: Boolean) {
        // Historical shape: the entry is written for both states.
        write(KEY_SMOOTH_MOTION, PerAppJson.put(read(KEY_SMOOTH_MOTION), pkg, enabled))
        // vendor.display.* is sepolicy-protected (vendor_display_prop): apps cannot
        // set it. The supported path is persist.sys.afme.smooth_motion, which
        // init.target.rc forwards to vendor.display.use_smooth_motion +
        // vendor.perf.framepacing.enable via a property trigger.
        setPropSafe("persist.sys.afme.smooth_motion", if (enabled) "1" else "0")
    }

    // ── Variable rate shading ──────────────────────────────────────────

    /** 0 = off, 1 = 2×1, 2 = 2×2, 3 = 4×4. */
    fun vrsLevel(pkg: String): Int = PerAppJson.getInt(read(KEY_VRS_LEVEL), pkg, 0)

    fun setVrsLevel(pkg: String, level: Int) {
        write(KEY_VRS_LEVEL, PerAppJson.put(read(KEY_VRS_LEVEL), pkg, level.takeIf { it > 0 }))
    }

    // ── Render resolution scale ────────────────────────────────────────

    /** "1.0" = native (no override). */
    fun resolution(pkg: String): String =
        PerAppJson.getString(read(KEY_RESOLUTION), pkg, "1.0")

    fun setResolution(pkg: String, factor: String) {
        write(
            KEY_RESOLUTION,
            PerAppJson.put(read(KEY_RESOLUTION), pkg, factor.takeIf { it != "1.0" })
        )
    }

    // ── Color enhance ──────────────────────────────────────────────────

    fun colorEnhance(pkg: String): Boolean =
        PerAppJson.getBoolean(read(KEY_COLOR_ENHANCE), pkg, false)

    fun setColorEnhance(pkg: String, enabled: Boolean) {
        write(
            KEY_COLOR_ENHANCE,
            PerAppJson.put(read(KEY_COLOR_ENHANCE), pkg, if (enabled) true else null)
        )
    }
}
