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

/**
 * Single writer for per-app video toolbox settings — the video-side twin of
 * [PerAppSettingStore].
 *
 * Everything here ends up driving Qualcomm's VPP post-processing filter through codec2
 * vendor params (`vendor.qti-ext-vpp-*`), applied inside the player's own process by the
 * media-stack hook. Settings.System holds the durable per-package map; the
 * `persist.sys.videobox.*` properties are the scoped hand-off to that hook, mirroring how
 * `persist.sys.afme.*` scopes frame generation to one game.
 *
 * See sidebar_research/VPP_VIDEOBOX.md for how the parameter names were recovered.
 */
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoSettingStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val io: SettingsIO = SettingsIO(context),
) {

    private companion object {
        const val KEY_FRC_TARGET = "videobox_frc_target"
        const val KEY_FRC_LEVEL = "videobox_frc_level"
        const val KEY_UPSCALE = "videobox_upscale"
        const val KEY_ENHANCE = "videobox_enhance"
        const val KEY_ENHANCE_GRADE = "videobox_enhance_grade"

        const val PROP_APP = "persist.sys.videobox.app"
        const val PROP_FRC_TARGET = "persist.sys.videobox.frc_target"
        const val PROP_FRC_LEVEL = "persist.sys.videobox.frc_level"
        const val PROP_UPSCALE = "persist.sys.videobox.ais"
        const val PROP_ENHANCE = "persist.sys.videobox.aie"
        const val PROP_ENHANCE_GRADE = "persist.sys.videobox.aie_grade"
    }

    /** Target frame rates offered in the UI. 0 = frame generation off. */
    object FrcTarget {
        const val OFF = 0
        val CHOICES = listOf(0, 48, 60, 90, 120)
    }

    /**
     * FRC_LEVEL_LOW / MEDIUM / HIGH. Governs how aggressively the interpolator trusts its
     * motion vectors: HIGH is smoothest but shows the most artefacting on hard cuts.
     */
    object FrcLevel {
        const val LOW = 0
        const val MEDIUM = 1
        const val HIGH = 2
    }

    // ── Frame generation ────────────────────────────────────────────────────

    /** 0 = off, otherwise the target frame rate in fps. */
    fun frcTarget(pkg: String): Int = io.getPerAppInt(KEY_FRC_TARGET, pkg, FrcTarget.OFF)

    fun setFrcTarget(pkg: String, targetFps: Int) {
        val target = if (targetFps in FrcTarget.CHOICES) targetFps else FrcTarget.OFF
        io.putPerApp(KEY_FRC_TARGET, pkg, target.takeIf { it != FrcTarget.OFF })

        // The VPP block cannot run FRC and AIS at once — the filter logs
        // "FRC is going to enable, force AIS bypass" and silently drops the upscaler.
        // Resolving that here rather than in the UI means the stored state always
        // matches what the hardware will actually do, whichever surface wrote it.
        if (target != FrcTarget.OFF && upscale(pkg)) {
            io.putPerApp(KEY_UPSCALE, pkg, null)
        }
    }

    fun frcLevel(pkg: String): Int =
        io.getPerAppInt(KEY_FRC_LEVEL, pkg, FrcLevel.MEDIUM)

    fun setFrcLevel(pkg: String, level: Int) {
        io.putPerApp(KEY_FRC_LEVEL, pkg, level.takeIf { it != FrcLevel.MEDIUM })
    }

    // ── AI upscale (VPP AIS) ────────────────────────────────────────────────

    fun upscale(pkg: String): Boolean = io.getPerAppBoolean(KEY_UPSCALE, pkg, false)

    fun setUpscale(pkg: String, enabled: Boolean) {
        io.putPerApp(KEY_UPSCALE, pkg, enabled.takeIf { it })
        // Mirror of setFrcTarget: whichever the user picks last wins.
        if (enabled && frcTarget(pkg) != FrcTarget.OFF) {
            io.putPerApp(KEY_FRC_TARGET, pkg, null)
        }
    }

    // ── Picture / colour (VPP AIE) ──────────────────────────────────────────

    fun enhance(pkg: String): Boolean = io.getPerAppBoolean(KEY_ENHANCE, pkg, false)

    fun setEnhance(pkg: String, enabled: Boolean) {
        io.putPerApp(KEY_ENHANCE, pkg, enabled.takeIf { it })
    }

    /** CSV in [VideoGrade.serialize] order, or empty for the hardware default. */
    fun enhanceGrade(pkg: String): String =
        io.getPerAppString(KEY_ENHANCE_GRADE, pkg, "")

    fun setEnhanceGrade(pkg: String, csv: String) {
        io.putPerApp(KEY_ENHANCE_GRADE, pkg, csv.takeIf { it.isNotEmpty() })
    }

    // ── Session hand-off ────────────────────────────────────────────────────

    /**
     * Publish this package's settings to the scoped properties the media-stack hook reads.
     *
     * Call on every video-session start AND after every edit: the hook samples the props
     * when the player configures its decoder, which for a mid-playback change means the
     * next track or the next seek that re-creates the codec.
     */
    fun applyForSession(pkg: String) {
        // Order matters: the app filter must be in place before any feature prop, so a
        // player that reads them concurrently can never see another app's settings
        // paired with our package gate. Same reasoning as persist.sys.afme.app.
        io.setProp(PROP_APP, pkg)
        io.setProp(PROP_FRC_TARGET, frcTarget(pkg).toString())
        io.setProp(PROP_FRC_LEVEL, frcLevel(pkg).toString())
        io.setProp(PROP_UPSCALE, if (upscale(pkg)) "1" else "0")
        io.setProp(PROP_ENHANCE, if (enhance(pkg)) "1" else "0")
        io.setProp(PROP_ENHANCE_GRADE, enhanceGrade(pkg))
    }

    /**
     * Drop the session scope. The feature props are cleared too — unlike AFME, nothing
     * here has to stay staged across a cold start, because the media hook runs at
     * MediaCodec.configure() which always happens well after the session is announced.
     */
    fun clearSession() {
        io.setProp(PROP_APP, "")
        io.setProp(PROP_FRC_TARGET, "0")
        io.setProp(PROP_FRC_LEVEL, FrcLevel.MEDIUM.toString())
        io.setProp(PROP_UPSCALE, "0")
        io.setProp(PROP_ENHANCE, "0")
        io.setProp(PROP_ENHANCE_GRADE, "")
    }
}
