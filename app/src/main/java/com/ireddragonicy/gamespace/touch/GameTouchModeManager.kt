/*
 * Copyright (C) 2025-2026 AxionOS
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
import android.provider.Settings
import android.util.Log

/**
 * Manages game touch mode lifecycle for GameSpace sessions.
 *
 * Integrates with [XiaomiTouchFeatureClient] to activate/deactivate
 * touch optimizations when games are started/stopped via GameSpace.
 *
 * Features managed:
 * - HTSR (High Touch Sampling Rate)
 * - Super Touch (enhanced touch tracking)
 * - First Frame Boost (reduced first-touch latency)
 * - Hot Area (per-game touch zones)
 * - Game Vibration (4D haptics)
 * - Freeform window touch mode
 *
 * Usage:
 * ```kotlin
 * val manager = GameTouchModeManager(context)
 * manager.onGameStart("com.example.game")
 * // ... game session ...
 * manager.onGameStop()
 * ```
 *
 * All settings are persisted via Settings.System under the "gamespace_touch_*" namespace.
 */
class GameTouchModeManager(private val context: Context) {

    companion object {
        private const val TAG = "GameTouchModeManager"

        // Settings.System keys for user-configurable touch features
        const val KEY_HTSR_ENABLED = "gamespace_touch_htsr"
        const val KEY_SUPER_TOUCH_ENABLED = "gamespace_touch_super_touch"
        const val KEY_SUPER_TOUCH_LEVEL = "gamespace_touch_super_touch_level"
        const val KEY_FIRST_FRAME_BOOST = "gamespace_touch_first_frame_boost"
        const val KEY_GAME_VIBRATION = "gamespace_touch_vibration"
        const val KEY_HOT_AREA_ENABLED = "gamespace_touch_hot_area"
    }

    private val client = XiaomiTouchFeatureClient
    private var currentPackage: String? = null
    private var isSessionActive = false

    /** Whether the Xiaomi touch feature HAL is available */
    val isHalAvailable: Boolean
        get() = client.isAvailable

    // ──────────────────────────────────────────────────────────────────
    // Settings helpers
    // ──────────────────────────────────────────────────────────────────

    private fun getBoolSetting(key: String, default: Boolean = true): Boolean {
        return Settings.System.getInt(
            context.contentResolver, key, if (default) 1 else 0
        ) == 1
    }

    private fun getIntSetting(key: String, default: Int): Int {
        return Settings.System.getInt(context.contentResolver, key, default)
    }

    private fun getPerAppBoolSetting(key: String, packageName: String, default: Boolean): Boolean {
        val json = Settings.System.getStringForUser(context.contentResolver, key, android.os.UserHandle.USER_CURRENT) ?: return default
        return try { org.json.JSONObject(json).optBoolean(packageName, default) } catch (e: Exception) { default }
    }

    private fun getPerAppIntSetting(key: String, packageName: String, default: Int): Int {
        val json = Settings.System.getStringForUser(context.contentResolver, key, android.os.UserHandle.USER_CURRENT) ?: return default
        return try { org.json.JSONObject(json).optInt(packageName, default) } catch (e: Exception) { default }
    }

    // ──────────────────────────────────────────────────────────────────
    // Session lifecycle
    // ──────────────────────────────────────────────────────────────────

    /**
     * Called when a game session starts.
     * Activates all user-enabled touch optimizations.
     *
     * @param packageName The game's package name
     */
    fun onGameStart(packageName: String) {
        if (!client.isAvailable) {
            Log.w(TAG, "Touch feature HAL not available, skipping touch optimizations")
            return
        }

        currentPackage = packageName
        isSessionActive = true

        // 1. Register game package with HAL
        client.setModePackageName(XiaomiTouchFeatureClient.MODE_GAME_PACKAGE_NAME, packageName)

        // 2. Signal game start
        client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_START, 1)

        // 3. Apply user-configured features
        applyUserSettings()

        Log.i(TAG, "Game touch session started: $packageName")
    }

    /**
     * Called when a game session ends.
     * Deactivates all touch optimizations, restoring defaults.
     */
    fun onGameStop() {
        if (!isSessionActive) return

        if (client.isAvailable) {
            // Reset all features to defaults
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_START, 0)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_DYNAMIC_RATE_APPS, 0)

            client.setSuperTouch(false)
            client.setFirstFrameBoost(false)
            client.setGameVibration(false)
            client.setHotAreaAppSet(false)
            client.setHotAreaAdaptive(false)
            client.setFreeformMode(false)
        }

        currentPackage = null
        isSessionActive = false
        Log.i(TAG, "Game touch session stopped")
    }

    /**
     * Re-applies all user settings during an active session.
     * Called when user toggles settings in the GamePanel.
     */
    fun applyUserSettings() {
        if (!isSessionActive || !client.isAvailable) return

        // HTSR
        if (getBoolSetting(KEY_HTSR_ENABLED, true)) {
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_DYNAMIC_RATE_APPS, 1)
        } else {
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_DYNAMIC_RATE_APPS, 0)
        }

        // Super Touch (THP interpolation — enables 480Hz effective rate)
        val superTouchEnabled = getBoolSetting(KEY_SUPER_TOUCH_ENABLED, true)
        val superTouchLevel = getIntSetting(KEY_SUPER_TOUCH_LEVEL, 100)
        client.setSuperTouch(superTouchEnabled, superTouchLevel)

        // Enable full SuperCore pipeline for maximum touch performance
        if (superTouchEnabled) {
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_SUPER_CORE,
                XiaomiTouchFeatureClient.SUPER_CORE_VSYNCBUFFER_ON)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_SUPER_CORE,
                XiaomiTouchFeatureClient.SUPER_CORE_PENDINGBUFFER_ON)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_SUPER_CORE,
                XiaomiTouchFeatureClient.SUPER_CORE_DYNAMIC_DROPBUFFER_ON)
        }

        // First Frame Boost
        client.setFirstFrameBoost(getBoolSetting(KEY_FIRST_FRAME_BOOST, true))

        // Game Vibration
        client.setGameVibration(getBoolSetting(KEY_GAME_VIBRATION, false))

        // Hot Area
        val hotAreaEnabled = getBoolSetting(KEY_HOT_AREA_ENABLED, true)
        client.setHotAreaAppSet(hotAreaEnabled)
        client.setHotAreaAdaptive(hotAreaEnabled)

        // Apply advanced touch tuning per-app
        currentPackage?.let { pkg ->
            val superReport = getPerAppBoolSetting("gamespace_touch_super_report", pkg, true)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE, if (superReport) 1 else 0)

            val expertMode = getPerAppBoolSetting("gamespace_touch_expert_mode", pkg, false)
            if (expertMode) {
                val preset = getPerAppIntSetting("gamespace_touch_expert_preset", pkg, 1)
                client.setTouchMode(XiaomiTouchFeatureClient.MODE_EXPERT_PRESET, preset)
            } else {
                client.setTouchMode(XiaomiTouchFeatureClient.MODE_EXPERT_PRESET, 0)
                client.setTouchMode(XiaomiTouchFeatureClient.MODE_TUNE_TOUCH_UP_THRESHOLD, getPerAppIntSetting("gamespace_touch_threshold", pkg, 2))
                client.setTouchMode(XiaomiTouchFeatureClient.MODE_TUNE_JITTER_TOLERANCE, getPerAppIntSetting("gamespace_touch_tolerance", pkg, 2))
                client.setTouchMode(XiaomiTouchFeatureClient.MODE_TUNE_AIM_SENSITIVITY, getPerAppIntSetting("gamespace_touch_aim_sens", pkg, 2))
                client.setTouchMode(XiaomiTouchFeatureClient.MODE_TUNE_TAP_STABILITY, getPerAppIntSetting("gamespace_touch_tap_stab", pkg, 2))
                client.setTouchMode(XiaomiTouchFeatureClient.MODE_TUNE_EDGE_FILTER, getPerAppIntSetting("gamespace_touch_edge_filter", pkg, 2))
            }
        }

        Log.d(TAG, "Applied touch settings: " +
            "htsr=${getBoolSetting(KEY_HTSR_ENABLED)}, " +
            "superTouch=$superTouchEnabled(lv=$superTouchLevel), " +
            "firstFrame=${getBoolSetting(KEY_FIRST_FRAME_BOOST)}, " +
            "vibration=${getBoolSetting(KEY_GAME_VIBRATION, false)}, " +
            "hotArea=$hotAreaEnabled"
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Individual toggle methods (for GamePanel UI)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Toggle HTSR (High Touch Sampling Rate).
     * Increases touch polling rate from default (~120Hz) to maximum (~480Hz).
     */
    fun toggleHTSR(enabled: Boolean) {
        Settings.System.putInt(context.contentResolver, KEY_HTSR_ENABLED, if (enabled) 1 else 0)
        if (isSessionActive) {
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_DYNAMIC_RATE_APPS, if (enabled) 1 else 0)
        }
    }

    /**
     * Toggle Super Touch (enhanced touch tracking).
     * Improves touch accuracy and reduces jitter during fast swipes.
     */
    fun toggleSuperTouch(enabled: Boolean) {
        Settings.System.putInt(context.contentResolver, KEY_SUPER_TOUCH_ENABLED, if (enabled) 1 else 0)
        if (isSessionActive) {
            val level = getIntSetting(KEY_SUPER_TOUCH_LEVEL, 100)
            client.setSuperTouch(enabled, level)
            // Also toggle VsyncBuffer + PendingBuffer + DynamicDropBuffer
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_SUPER_CORE,
                if (enabled) XiaomiTouchFeatureClient.SUPER_CORE_VSYNCBUFFER_ON
                else XiaomiTouchFeatureClient.SUPER_CORE_VSYNCBUFFER_OFF)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_SUPER_CORE,
                if (enabled) XiaomiTouchFeatureClient.SUPER_CORE_PENDINGBUFFER_ON
                else XiaomiTouchFeatureClient.SUPER_CORE_PENDINGBUFFER_OFF)
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_GAME_SUPER_CORE,
                if (enabled) XiaomiTouchFeatureClient.SUPER_CORE_DYNAMIC_DROPBUFFER_ON
                else XiaomiTouchFeatureClient.SUPER_CORE_DYNAMIC_DROPBUFFER_OFF)
        }
    }

    /**
     * Set super touch sensitivity level.
     * @param level 0-100 (higher = more sensitive)
     */
    fun setSuperTouchLevel(level: Int) {
        val clamped = level.coerceIn(0, 100)
        Settings.System.putInt(context.contentResolver, KEY_SUPER_TOUCH_LEVEL, clamped)
        if (isSessionActive && getBoolSetting(KEY_SUPER_TOUCH_ENABLED, false)) {
            client.setTouchMode(XiaomiTouchFeatureClient.MODE_SUPER_TOUCH_LEVEL, clamped)
        }
    }

    /**
     * Toggle First Frame Boost.
     * Eliminates touch latency spike on first contact after idle period.
     */
    fun toggleFirstFrameBoost(enabled: Boolean) {
        Settings.System.putInt(context.contentResolver, KEY_FIRST_FRAME_BOOST, if (enabled) 1 else 0)
        if (isSessionActive) {
            client.setFirstFrameBoost(enabled)
        }
    }

    /**
     * Toggle game vibration (4D haptics).
     * Provides haptic feedback mapped to in-game touch events.
     */
    fun toggleGameVibration(enabled: Boolean) {
        Settings.System.putInt(context.contentResolver, KEY_GAME_VIBRATION, if (enabled) 1 else 0)
        if (isSessionActive) {
            client.setGameVibration(enabled)
        }
    }

    /**
     * Toggle hot area mode (game-specific touch zones).
     * Optimizes touch sensitivity for game control areas.
     */
    fun toggleHotArea(enabled: Boolean) {
        Settings.System.putInt(context.contentResolver, KEY_HOT_AREA_ENABLED, if (enabled) 1 else 0)
        if (isSessionActive) {
            client.setHotAreaAppSet(enabled)
            client.setHotAreaAdaptive(enabled)
        }
    }

    /**
     * Notify that the game entered freeform/multi-window mode.
     */
    fun onFreeformModeChanged(inFreeform: Boolean) {
        if (isSessionActive) {
            client.setFreeformMode(inFreeform)
        }
    }

    /**
     * Notify game scene change (for scene-aware touch tuning).
     */
    fun onGameSceneChanged(sceneId: Int) {
        if (isSessionActive) {
            client.setGameScene(sceneId)
        }
    }
}
