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
package com.ireddragonicy.gamespace.touch

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.ireddragonicy.gamespace.data.settings.TouchSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of GameSpace's active touch profile.
 *
 * Report-rate control is the existing verified HAL → driver path.  Edge and
 * grip protection use [KernelTouchFilterClient], whose companion OSS kernel
 * implementation filters new contacts immediately before Android input events
 * are reported.  This deliberately avoids presenting mode 7/15 AIDL writes
 * as functional when the closed onyx HAL only caches them.
 */
@Singleton
class GameTouchModeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val touchSettings: TouchSettingsStore,
) {

    companion object {
        private const val TAG = "GameTouchModeManager"

        /** Panel rate with the game-mode gate engaged. */
        const val REPORT_RATE_BOOSTED = 240

        /** Panel rate otherwise — also the NT36532's power-on default. */
        const val REPORT_RATE_NORMAL = 120
    }

    private val client = XiaomiTouchFeatureClient
    private var currentPackage: String? = null
    private var isSessionActive = false

    /** Whether the Xiaomi touch feature HAL is available. */
    val isHalAvailable: Boolean
        get() = client.isAvailable

    /** Whether this device's HAL reports support for boosted panel sampling. */
    val supportsSuperReport: Boolean
        get() = client.isAvailable &&
            client.isModeSupported(XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE)

    /** True only when the matching OSS kernel node and SELinux permission exist. */
    val supportsAccidentalTouchFilter: Boolean
        get() = KernelTouchFilterClient.isAvailable

    /** Session mirrors of the per-app values, shared by Tuner and quick controls. */
    val superReportEnabled: MutableState<Boolean> = mutableStateOf(false)
    val edgeFilterLevel = mutableIntStateOf(KernelTouchFilterClient.LEVEL_OFF)
    val gripSuppressionLevel = mutableIntStateOf(KernelTouchFilterClient.LEVEL_OFF)
    val filterStats: MutableState<KernelTouchFilterClient.Stats?> = mutableStateOf(null)

    /** The actual panel rate, read from the driver rather than inferred. */
    val reportRateHz = mutableIntStateOf(REPORT_RATE_NORMAL)

    val superReportEngaged: Boolean
        get() = reportRateHz.intValue >= REPORT_RATE_BOOSTED

    // ──────────────────────────────────────────────────────────────────
    // Session lifecycle
    // ──────────────────────────────────────────────────────────────────

    fun onGameStart(packageName: String) {
        currentPackage = packageName
        isSessionActive = true

        if (client.isAvailable) {
            client.setModePackageName(XiaomiTouchFeatureClient.MODE_GAME_PACKAGE_NAME, packageName)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_START, 1)
        } else {
            Log.w(TAG, "Touch HAL unavailable; applying OSS touch filter only")
        }
        applyUserSettings()
        Log.i(TAG, "Game touch session started: $packageName")
    }

    fun onGameStop() {
        if (!isSessionActive) return

        // Always clear our input filter, even on devices without the vendor HAL.
        KernelTouchFilterClient.apply(
            KernelTouchFilterClient.Profile(orientation = currentOrientation()),
        )
        edgeFilterLevel.intValue = KernelTouchFilterClient.LEVEL_OFF
        gripSuppressionLevel.intValue = KernelTouchFilterClient.LEVEL_OFF
        filterStats.value = KernelTouchFilterClient.readStats()

        if (client.isAvailable) {
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_START, 0)
            // Drop back to the base rate — leaving game mode latched keeps the
            // panel scanning at 240Hz outside games and costs idle battery.
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE, 0)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_MODE, 0)
            superReportEnabled.value = false
            refreshReportRate()
        }

        currentPackage = null
        isSessionActive = false
        Log.i(TAG, "Game touch session stopped")
    }

    /** Re-arm the HAL and OSS profile after the panel resumes. */
    fun onScreenOn() {
        if (!isSessionActive) return
        applyUserSettings()
        Log.d(TAG, "Re-armed touch state after resume")
    }

    /** Keep landscape grip edges aligned with the active game's orientation. */
    fun onConfigurationChanged(configuration: Configuration) {
        if (!isSessionActive) return
        applyKernelProfile(configuration)
    }

    /** Re-apply both real backend paths for the current package. */
    fun applyUserSettings() {
        val pkg = currentPackage ?: return
        val superReport = touchSettings.superReport(pkg)
        superReportEnabled.value = superReport
        edgeFilterLevel.intValue = touchSettings.edgeFilter(pkg)
        gripSuppressionLevel.intValue = touchSettings.gripSuppression(pkg)

        if (client.isAvailable) {
            applySuperReportToDriver(superReport)
            refreshReportRate()
        }
        applyKernelProfile(context.resources.configuration)
    }

    // ──────────────────────────────────────────────────────────────────
    // Report rate — the verified HAL → NT36532 path
    // ──────────────────────────────────────────────────────────────────

    fun superReport(pkg: String): Boolean = touchSettings.superReport(pkg)

    /** Quick-toggle entry point — applies to whichever game is in session. */
    fun toggleSuperReport(enabled: Boolean) {
        val pkg = currentPackage ?: run {
            Log.w(TAG, "toggleSuperReport with no active session")
            return
        }
        setSuperReport(pkg, enabled)
    }

    fun setSuperReport(pkg: String, enabled: Boolean) {
        touchSettings.setSuperReport(pkg, enabled)
        if (pkg != currentPackage || !isSessionActive) return

        superReportEnabled.value = enabled
        if (client.isAvailable) {
            applySuperReportToDriver(enabled)
            refreshReportRate()
        }
        Log.i(TAG, "Super report ${if (enabled) "ON" else "OFF"} for $pkg " +
            "-> ${reportRateHz.intValue}Hz")
    }

    private fun applySuperReportToDriver(enabled: Boolean) {
        client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_MODE, if (enabled) 1 else 0)
        client.setTouchMode(
            XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE,
            if (enabled) 1 else 0,
        )
    }

    /** Publish the panel's real scan rate. */
    fun refreshReportRate() {
        if (!client.isAvailable) {
            reportRateHz.intValue = REPORT_RATE_NORMAL
            return
        }
        val reported = client.getModeCurValue(XiaomiTouchFeatureClient.MODE_REPORT_RATE)
        reportRateHz.intValue =
            if (reported > 0) reported
            else if (client.getModeCurValue(XiaomiTouchFeatureClient.MODE_GAME_MODE) == 1)
                REPORT_RATE_BOOSTED
            else REPORT_RATE_NORMAL
    }

    // ──────────────────────────────────────────────────────────────────
    // OSS input-side edge and grip filter
    // ──────────────────────────────────────────────────────────────────

    fun edgeFilter(pkg: String): Int = touchSettings.edgeFilter(pkg)

    fun setEdgeFilter(pkg: String, level: Int) {
        val normalized = level.coerceIn(
            KernelTouchFilterClient.LEVEL_OFF,
            KernelTouchFilterClient.LEVEL_HIGH,
        )
        touchSettings.setEdgeFilter(pkg, normalized)
        if (pkg != currentPackage || !isSessionActive) return

        edgeFilterLevel.intValue = normalized
        applyKernelProfile(context.resources.configuration)
    }

    fun gripSuppression(pkg: String): Int = touchSettings.gripSuppression(pkg)

    fun setGripSuppression(pkg: String, level: Int) {
        val normalized = level.coerceIn(
            KernelTouchFilterClient.LEVEL_OFF,
            KernelTouchFilterClient.LEVEL_HIGH,
        )
        touchSettings.setGripSuppression(pkg, normalized)
        if (pkg != currentPackage || !isSessionActive) return

        gripSuppressionLevel.intValue = normalized
        applyKernelProfile(context.resources.configuration)
    }

    fun refreshFilterStats() {
        filterStats.value = KernelTouchFilterClient.readStats()
    }

    private fun applyKernelProfile(configuration: Configuration): Boolean {
        val profile = KernelTouchFilterClient.Profile(
            edgeFilter = edgeFilterLevel.intValue,
            gripSuppression = gripSuppressionLevel.intValue,
            orientation = currentOrientation(configuration),
        )
        val applied = KernelTouchFilterClient.apply(profile)
        filterStats.value = if (applied) KernelTouchFilterClient.readStats() else null
        if (!applied && (profile.edgeFilter != 0 || profile.gripSuppression != 0)) {
            Log.w(TAG, "OSS accidental-touch filter unavailable; profile not applied: $profile")
        }
        return applied
    }

    private fun currentOrientation(configuration: Configuration = context.resources.configuration): Int =
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            KernelTouchFilterClient.ORIENTATION_LANDSCAPE
        } else {
            KernelTouchFilterClient.ORIENTATION_PORTRAIT
        }

    /** Notify that the game entered freeform/multi-window mode. */
    fun onFreeformModeChanged(inFreeform: Boolean) {
        if (isSessionActive) client.setFreeformMode(inFreeform)
    }

    /** Notify game scene change (for scene-aware touch tuning). */
    fun onGameSceneChanged(sceneId: Int) {
        if (isSessionActive) client.setGameScene(sceneId)
    }
}
