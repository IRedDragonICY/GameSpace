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

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log

/**
 * Client for Xiaomi's touch feature HAL service.
 *
 * Communicates with `vendor.xiaomi.hw.touchfeature.ITouchFeature/default` (AIDL V3)
 * to control hardware-level touch panel parameters such as HTSR, game touch zones,
 * super touch, and touch vibration.
 *
 * Reverse-engineered from HyperOS 3.0 TouchService.apk + PowerKeeper.apk:
 * - TouchService: com.xiaomi.touchservice.ITouchFeature
 * - PowerKeeper:  com.miui.powerkeeper.utils.TouchFeatureUtil
 *
 * Architecture:
 *   App → XiaomiTouchFeatureClient → AIDL service → Kernel touch driver → Touch IC
 *
 * All calls use displayId=0 (primary display).
 */
object XiaomiTouchFeatureClient {

    private const val TAG = "XiaomiTouchFeature"

    // ──────────────────────────────────────────────────────────────────
    // Service constants
    // ──────────────────────────────────────────────────────────────────

    private const val SERVICE_NAME = "vendor.xiaomi.hw.touchfeature.ITouchFeature/default"
    private const val INTERFACE_TOKEN = "vendor.xiaomi.hw.touchfeature.ITouchFeature"

    /** AIDL transact codes (V3) */
    private const val TRANSACT_GET_MODE_CUR_VALUE = 1
    private const val TRANSACT_GET_MODE_DEFAULT_VALUE = 2
    private const val TRANSACT_GET_MODE_MAX_VALUE = 3
    private const val TRANSACT_GET_MODE_MIN_VALUE = 4
    private const val TRANSACT_GET_MODE_VALUE = 5
    private const val TRANSACT_GET_TOUCH_EVENT = 6
    private const val TRANSACT_SET_TOUCH_MODE = 9
    private const val TRANSACT_GET_MODE_WHITELIST = 11
    private const val TRANSACT_SET_MODE_PACKAGE_NAME = 14

    // ──────────────────────────────────────────────────────────────────
    // Touch Mode IDs — Complete list from RE
    // ──────────────────────────────────────────────────────────────────


    /** @section Basic Touch Modes (0-199) */
    const val MODE_WATERPROOF = 100                 // Water resistance sensitivity
    const val MODE_GLOVE = 101                      // Glove mode sensitivity
    const val MODE_SCP_TP_MISTOUCH = 103            // Mistouch prevention
    const val MODE_THP_SENSORHUB = 104              // Touch + sensor hub data
    const val MODE_TICKET_GRAB = 105                // Fast ticket grab mode
    const val MODE_TP_ACTIVE_TIME = 13              // Touch panel active scan interval (ms)

    /** @section Touch Control Modes (1000-1199) */
    const val MODE_EXPERT_PRESET = 1                // Expert preset (1=default, ranges from 1 to 3)
    const val MODE_TUNE_TOUCH_UP_THRESHOLD = 2      // Touch-up threshold (default 2, 0 to 4)
    const val MODE_TUNE_JITTER_TOLERANCE = 3        // Jitter tolerance (default 2, 0 to 4)
    const val MODE_TUNE_AIM_SENSITIVITY = 4         // Aim sensitivity (default 2, 0 to 4)
    const val MODE_TUNE_TAP_STABILITY = 5           // Tap stability (default 2, 0 to 4)
    const val MODE_TUNE_EDGE_FILTER = 7             // Edge filter (default 2, 0 to 3)
    const val MODE_PANEL_ORIENTATION = 8            // Panel orientation (usually 0)
    const val MODE_SUPER_REPORT_RATE = 202          // 480Hz Super report rate toggle (0/1)

    const val MODE_TICKET_GRAB_BOOST = 1028         // Ticket grab touch boost
    const val MODE_DYNAMIC_RATE_APPS = 1085         // HTSR: High Touch Sampling Rate
    const val MODE_HOT_AREA_SECTION = 1097          // Game UI hot area section params
    const val MODE_PROXIMITY_MODE = 1106            // Proximity-based touch on/off
    const val MODE_HOT_AREA_FREEFORM = 1109         // Freeform window touch hotspot
    const val MODE_TICKET_GRAB_MODE = 1116          // Ticket grab touch mode
    const val MODE_GAME_UI_LIST = 1118              // Game UI list scene params

    /** @section Game Control Modes (10000-10199) */
    const val MODE_GAME_SUPER_CORE = 10001          // SuperCore: frame boost/duration/buffer
    const val MODE_GAME_GAMEPAD = 10007             // Gamepad: highlight/multi-device
    const val MODE_GAME_PACKAGE_NAME = 10100        // Set current game package (use setModePackageName)
    const val MODE_GAME_START = 10101               // Game start (1) / stop (0)
    const val MODE_SUPER_TOUCH_APP = 10103          // Super touch whitelist (use setModePackageName)
    const val MODE_PENDING_BUFFER_APP = 10104       // Pending buffer whitelist (use setModePackageName)
    const val MODE_SUPER_TOUCH_LEVEL = 10105        // Super touch sensitivity level (0-100)
    const val MODE_PENDING_BUFFER_LEVEL = 10106     // Pending buffer level (0-100)
    const val MODE_GAME_SCENE = 10109               // Game scene change (loading/battle/menu)
    const val MODE_GAME_4D_SCENE = 10110            // 4D vibration scene ID
    const val MODE_NORMAL_REPORT_RATE = 10111       // Normal report rate enable
    const val MODE_NORMAL_REPORT_RATE_APP = 10112   // Normal report rate app list (use setModePackageName)

    /** @section Hot Area Modes (10500-10599) */
    const val MODE_HOT_AREA_APP_SET = 10501         // Hot area app set enable
    const val MODE_HOT_AREA_APP_SET_LIST = 10502    // Hot area app set list (use setModePackageName)
    const val MODE_HOT_AREA_ADAPT = 10503           // Hot area adaptive enable
    const val MODE_HOT_AREA_ADAPT_LIST = 10504      // Hot area adaptive list (use setModePackageName)

    /** @section Vibrator Modes (10600-10699) */
    const val MODE_VIBRATOR = 10602                 // Game vibrator enable
    const val MODE_VIBRATOR_APP = 10603             // Game vibrator app list (use setModePackageName)

    /** @section Pencil Modes (20000+) */
    const val MODE_PENCIL_POSTURE = 20036           // Pencil posture control

    // ──────────────────────────────────────────────────────────────────
    // SuperCore sub-values (sent as value param to MODE_GAME_SUPER_CORE)
    // ──────────────────────────────────────────────────────────────────

    const val SUPER_CORE_FIRSTFRAME_DURATION_ON = 101
    const val SUPER_CORE_FIRSTFRAME_DURATION_OFF = 102
    const val SUPER_CORE_FIRSTFRAME_DROPBUFFER_ON = 103
    const val SUPER_CORE_FIRSTFRAME_DROPBUFFER_OFF = 104
    const val SUPER_CORE_DYNAMIC_DURATION_ON = 105
    const val SUPER_CORE_DYNAMIC_DURATION_OFF = 106
    const val SUPER_CORE_DYNAMIC_DROPBUFFER_ON = 107
    const val SUPER_CORE_DYNAMIC_DROPBUFFER_OFF = 108
    const val SUPER_CORE_SUPERTOUCH_ON = 109
    const val SUPER_CORE_SUPERTOUCH_OFF = 110
    const val SUPER_CORE_FIRSTFRAME_BOOST_ON = 111
    const val SUPER_CORE_FIRSTFRAME_BOOST_OFF = 112
    const val SUPER_CORE_DYNAMIC_BOOST_ON = 113
    const val SUPER_CORE_DYNAMIC_BOOST_OFF = 114
    const val SUPER_CORE_PENDINGBUFFER_ON = 115
    const val SUPER_CORE_PENDINGBUFFER_OFF = 116
    const val SUPER_CORE_VSYNCBUFFER_ON = 117
    const val SUPER_CORE_VSYNCBUFFER_OFF = 118

    // ──────────────────────────────────────────────────────────────────
    // Gamepad sub-values (sent as value param to MODE_GAME_GAMEPAD)
    // ──────────────────────────────────────────────────────────────────

    const val GAMEPAD_ENABLE = 701
    const val GAMEPAD_DISABLE = 702
    const val GAMEPAD_HIGHLIGHT_ON = 703
    const val GAMEPAD_HIGHLIGHT_OFF = 704
    const val GAMEPAD_AIPREDICT_ON = 705
    const val GAMEPAD_AIPREDICT_OFF = 706
    const val GAMEPAD_MULTIDEVICE_ON = 707
    const val GAMEPAD_MULTIDEVICE_OFF = 708

    // ──────────────────────────────────────────────────────────────────
    // Hot Area sub-values
    // ──────────────────────────────────────────────────────────────────

    const val HOT_AREA_ENABLE = 501
    const val HOT_AREA_DISABLE = 502

    // ──────────────────────────────────────────────────────────────────
    // Primary display
    // ──────────────────────────────────────────────────────────────────

    private const val DISPLAY_PRIMARY = 0

    // ──────────────────────────────────────────────────────────────────
    // Cached service binder
    // ──────────────────────────────────────────────────────────────────

    @Volatile
    private var cachedBinder: IBinder? = null

    private fun getService(): IBinder? {
        cachedBinder?.let {
            if (it.isBinderAlive) return it
            cachedBinder = null
        }
        return ServiceManager.getService(SERVICE_NAME)?.also {
            cachedBinder = it
            try {
                it.linkToDeath({ cachedBinder = null }, 0)
            } catch (_: RemoteException) {}
        }
    }

    /**
     * Whether the Xiaomi touch feature HAL is available on this device.
     */
    val isAvailable: Boolean
        get() = getService() != null

    // ──────────────────────────────────────────────────────────────────
    // Core API
    // ──────────────────────────────────────────────────────────────────

    /**
     * Set a touch mode value on the primary display.
     *
     * @param modeId One of the MODE_* constants
     * @param value  The value to set (mode-specific)
     * @return true if the HAL call succeeded, false otherwise
     */
    fun setTouchMode(modeId: Int, value: Int): Boolean {
        return setTouchMode(DISPLAY_PRIMARY, modeId, value)
    }

    /**
     * Set a touch mode value on a specific display.
     *
     * @param displayId Display ID (0 = primary)
     * @param modeId    One of the MODE_* constants
     * @param value     The value to set (mode-specific)
     * @return true if the HAL call succeeded, false otherwise
     */
    fun setTouchMode(displayId: Int, modeId: Int, value: Int): Boolean {
        val binder = getService() ?: run {
            Log.w(TAG, "setTouchMode($modeId, $value) — service unavailable")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(displayId)
            data.writeInt(modeId)
            data.writeInt(value)
            binder.transact(TRANSACT_SET_TOUCH_MODE, data, reply, 0)
            reply.readException()
            val result = reply.readInt()
            if (result != 0) {
                Log.w(TAG, "setTouchMode($modeId, $value) failed: ret=$result")
                false
            } else {
                true
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "setTouchMode($modeId, $value) transact failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Helper to read an integer from a mode getter transact.
     */
    private fun getModeInt(transactCode: Int, displayId: Int, modeId: Int): Int {
        val binder = getService() ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(displayId)
            data.writeInt(modeId)
            if (binder.transact(transactCode, data, reply, 0)) {
                reply.readException()
                reply.readInt()
            } else {
                -1
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Transact $transactCode failed for mode $modeId", e)
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun getModeCurValue(modeId: Int): Int = getModeInt(TRANSACT_GET_MODE_CUR_VALUE, DISPLAY_PRIMARY, modeId)
    fun getModeDefaultValue(modeId: Int): Int = getModeInt(TRANSACT_GET_MODE_DEFAULT_VALUE, DISPLAY_PRIMARY, modeId)
    fun getModeMinValue(modeId: Int): Int = getModeInt(TRANSACT_GET_MODE_MIN_VALUE, DISPLAY_PRIMARY, modeId)
    fun getModeMaxValue(modeId: Int): Int = getModeInt(TRANSACT_GET_MODE_MAX_VALUE, DISPLAY_PRIMARY, modeId)

    /**
     * Get an integer array for a specific mode value query.
     */
    fun getModeValue(modeId: Int): IntArray? {
        val binder = getService() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(DISPLAY_PRIMARY)
            data.writeInt(modeId)
            if (binder.transact(TRANSACT_GET_MODE_VALUE, data, reply, 0)) {
                reply.readException()
                reply.createIntArray()
            } else {
                null
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "getModeValue failed for mode $modeId", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Associate a package name with a touch mode (for per-app whitelist modes).
     *
     * Used with modes like MODE_GAME_PACKAGE_NAME, MODE_SUPER_TOUCH_APP,
     * MODE_HOT_AREA_APP_SET_LIST, etc.
     *
     * @param modeId      One of the MODE_* constants
     * @param packageName The package name(s), comma-separated for lists
     */
    fun setModePackageName(modeId: Int, packageName: String): Boolean {
        val binder = getService() ?: run {
            Log.w(TAG, "setModePackageName($modeId) — service unavailable")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(DISPLAY_PRIMARY)
            data.writeInt(modeId)
            data.writeString(packageName)
            binder.transact(TRANSACT_SET_MODE_PACKAGE_NAME, data, reply, 0)
            reply.readException()
            Log.d(TAG, "setModePackageName($modeId, $packageName) — success")
            true
        } catch (e: RemoteException) {
            Log.e(TAG, "setModePackageName($modeId) transact failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Get touch event data from the HAL.
     *
     * @return JSON string with touch event info, or null on failure
     */
    fun getTouchEvent(): String? {
        val binder = getService() ?: return null

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            binder.transact(TRANSACT_GET_TOUCH_EVENT, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: RemoteException) {
            Log.e(TAG, "getTouchEvent failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Get the whitelist JSON for specific mode IDs.
     *
     * @param modeIds Array of mode IDs to query
     * @return JSON string with whitelist data, or null on failure
     */
    fun getModeWhitelist(vararg modeIds: Int): String? {
        val binder = getService() ?: return null

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(DISPLAY_PRIMARY)
            data.writeIntArray(modeIds)
            binder.transact(TRANSACT_GET_MODE_WHITELIST, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: RemoteException) {
            Log.e(TAG, "getModeWhitelist failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // High-level convenience methods
    // ──────────────────────────────────────────────────────────────────

    /**
     * Activate full game touch mode for a package.
     * Equivalent to what Joyose does when a game starts.
     */
    fun onGameStart(packageName: String) {
        setModePackageName(MODE_GAME_PACKAGE_NAME, packageName)
        setTouchMode(MODE_GAME_START, 1)
        // Enable HTSR (high touch sampling rate)
        setTouchMode(MODE_DYNAMIC_RATE_APPS, 1)
        Log.i(TAG, "Game touch mode activated: $packageName")
    }

    /**
     * Deactivate game touch mode.
     * Equivalent to what Joyose does when a game stops.
     */
    fun onGameStop() {
        setTouchMode(MODE_GAME_START, 0)
        setTouchMode(MODE_DYNAMIC_RATE_APPS, 0)
        Log.i(TAG, "Game touch mode deactivated")
    }

    /**
     * Set game scene (loading screen, battle, menu, etc.)
     * Adjusts touch sensitivity based on gameplay context.
     */
    fun setGameScene(sceneId: Int) {
        setTouchMode(MODE_GAME_SCENE, sceneId)
    }

    /**
     * Enable/disable freeform window touch mode.
     * Adjusts touch zones when game is in split/freeform window.
     */
    fun setFreeformMode(enabled: Boolean) {
        setTouchMode(MODE_HOT_AREA_FREEFORM, if (enabled) 1 else 0)
    }

    /**
     * Enable/disable super touch (enhanced touch tracking).
     * @param level Sensitivity level 0-100 (default: 100)
     */
    fun setSuperTouch(enabled: Boolean, level: Int = 100) {
        setTouchMode(MODE_GAME_SUPER_CORE,
            if (enabled) SUPER_CORE_SUPERTOUCH_ON else SUPER_CORE_SUPERTOUCH_OFF
        )
        if (enabled) {
            setTouchMode(MODE_SUPER_TOUCH_LEVEL, level.coerceIn(0, 100))
        }
    }

    /**
     * Enable/disable first-frame boost.
     * Reduces latency of the first touch event after idle.
     */
    fun setFirstFrameBoost(enabled: Boolean) {
        setTouchMode(MODE_GAME_SUPER_CORE,
            if (enabled) SUPER_CORE_FIRSTFRAME_BOOST_ON else SUPER_CORE_FIRSTFRAME_BOOST_OFF
        )
        setTouchMode(MODE_GAME_SUPER_CORE,
            if (enabled) SUPER_CORE_FIRSTFRAME_DURATION_ON else SUPER_CORE_FIRSTFRAME_DURATION_OFF
        )
    }

    /**
     * Enable/disable game vibration (4D haptics).
     */
    fun setGameVibration(enabled: Boolean) {
        setTouchMode(MODE_VIBRATOR, if (enabled) 1 else 0)
    }

    /**
     * Set game 4D vibration scene.
     */
    fun set4DScene(sceneId: Int) {
        setTouchMode(MODE_GAME_4D_SCENE, sceneId)
    }

    /**
     * Set TP active time (touch panel scan interval).
     * Lower values = faster response = more power consumption.
     */
    fun setTPActiveTime(timeMs: Int) {
        setTouchMode(MODE_TP_ACTIVE_TIME, timeMs)
    }

    /**
     * Enable/disable hot area app set (per-game touch zones).
     */
    fun setHotAreaAppSet(enabled: Boolean) {
        setTouchMode(MODE_HOT_AREA_APP_SET,
            if (enabled) HOT_AREA_ENABLE else HOT_AREA_DISABLE
        )
    }

    /**
     * Enable/disable hot area adaptive mode.
     */
    fun setHotAreaAdaptive(enabled: Boolean) {
        setTouchMode(MODE_HOT_AREA_ADAPT,
            if (enabled) HOT_AREA_ENABLE else HOT_AREA_DISABLE
        )
    }

    /**
     * Set game UI hot area section parameters (JSON format).
     */
    fun setHotAreaSection(sectionJson: String) {
        setModePackageName(MODE_HOT_AREA_SECTION, sectionJson)
    }
}
