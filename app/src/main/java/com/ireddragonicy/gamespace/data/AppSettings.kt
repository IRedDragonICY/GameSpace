/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2023 risingOS Android Project
 *               2022-2026 crDroid Android Project
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

import android.app.Service
import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import androidx.preference.PreferenceManager
import com.ireddragonicy.gamespace.ui.theme.AppThemeMode
import com.ireddragonicy.gamespace.ui.theme.AppThemeState
import com.ireddragonicy.gamespace.utils.BooleanPref
import com.ireddragonicy.gamespace.utils.IntClampedPref
import com.ireddragonicy.gamespace.utils.IntPref
import com.ireddragonicy.gamespace.utils.StringPref
import com.ireddragonicy.gamespace.utils.dp
import com.ireddragonicy.gamespace.utils.statusbarHeight
import javax.inject.Inject

class AppSettings @Inject constructor(private val context: Context) {

    private val db by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private val wm by lazy { context.getSystemService(Service.WINDOW_SERVICE) as WindowManager }

    var x
        get() = db.getInt("offset_x", wm.maximumWindowMetrics.bounds.width() / 2)
        set(point) = db.edit().putInt("offset_x", point).apply()

    var y
        get() = db.getInt("offset_y", context.statusbarHeight + 8.dp)
        set(point) = db.edit().putInt("offset_y", point).apply()

    var showFps by BooleanPref(db, "show_fps", false)
    var noAutoBrightness by BooleanPref(db, KEY_AUTO_BRIGHTNESS_DISABLE, true)
    var noThreeScreenshot by BooleanPref(db, KEY_3SCREENSHOT_DISABLE, false)
    var danmakuNotification by BooleanPref(db, KEY_DANMAKU_NOTIFICATION_MODE, false)

    var callsMode: Int
        get() = db.getString(KEY_CALLS_MODE, "0")?.toIntOrNull() ?: 0
        set(value) = db.edit().putString(KEY_CALLS_MODE, value.toString()).apply()

    var enableBlur by BooleanPref(db, KEY_ENABLE_BLUR, true)
    var blurRadius by IntPref(db, KEY_BLUR_RADIUS, 50)

    // ── Screen record defaults ──
    var screenRecordTargetMode by IntPref(db, "screen_record_target_mode", 0) // 0 = One App (Game), 1 = Full Screen
    var screenRecordAudioSource by IntPref(db, "screen_record_audio_source", 1) // 1 = Internal Audio
    var screenRecordShowTaps by BooleanPref(db, "screen_record_show_taps", false)
    var screenRecordHEVC by BooleanPref(db, "screen_record_hevc", true)
    var screenRecordLowQuality by BooleanPref(db, "screen_record_low_quality", false)
    var screenRecordLongerDuration by BooleanPref(db, "screen_record_longer_duration", false)
    var screenRecordResolution by IntPref(db, "screen_record_resolution", 0) // 0 = Native
    var screenRecordFps by IntPref(db, "screen_record_fps", 0) // 0 = Native

    var ringerMode: Int
        get() = db.getString(KEY_RINGER_MODE, "3")?.toIntOrNull() ?: 3
        set(value) = db.edit().putString(KEY_RINGER_MODE, value.toString()).apply()

    var menuOpacity by IntPref(db, KEY_MENU_OPACITY, 100)

    /** Game Hub library layout: false = vertical list, true = hero carousel */
    var hubCarouselMode by BooleanPref(db, "hub_carousel_mode", false)

    var tileOrder: List<String>
        get() = db.getString(KEY_TILE_ORDER, null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = db.edit().putString(KEY_TILE_ORDER, value.joinToString(",")).apply()

    var quickToggleOrder: List<String>
        get() = db.getString(KEY_QUICK_TOGGLE_ORDER, null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = db.edit().putString(KEY_QUICK_TOGGLE_ORDER, value.joinToString(",")).apply()

    var brightnessEnabled by BooleanPref(db, KEY_BRIGHTNESS_ENABLED, true)
    var fpsGraphEnabled by BooleanPref(db, KEY_FPS_GRAPH_ENABLED, true)
    var quickStartApps by StringPref(db, KEY_QUICK_START_APPS, "")

    /** User-edited sidebar dock per mode (see SidebarMode). Empty = auto-build. */
    fun getDockList(mode: Int): List<String> =
        db.getString(KEY_DOCK_LIST_PREFIX + mode, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun setDockList(mode: Int, pkgs: List<String>) {
        db.edit().putString(KEY_DOCK_LIST_PREFIX + mode, pkgs.joinToString(",")).apply()
    }

    // ── Dock behaviour (app strip) ──────────────────────────────────
    /** Total slots in the strip. Default 20, clamped 5..30 on read AND write. */
    var dockMaxApps by IntClampedPref(db, KEY_DOCK_MAX_APPS, 20, 5..30)

    /** How the free (non-pinned) slots are sorted. */
    var dockSort: DockSort
        get() = DockSort.fromKey(db.getString(KEY_DOCK_SORT, null))
        set(value) = db.edit().putString(KEY_DOCK_SORT, value.key).apply()

    /** Where the auto slots pull candidates from (independent of sort). */
    var dockSource: DockSource
        get() = DockSource.fromKey(db.getString(KEY_DOCK_SOURCE, null))
        set(value) = db.edit().putString(KEY_DOCK_SOURCE, value.key).apply()

    /** Fill free slots with suggestions instead of leaving them empty. */
    var dockAutoFill by BooleanPref(db, KEY_DOCK_AUTO_FILL, true)

    /** Re-sort the free slots every time the panel opens. */
    var dockRefreshOnOpen by BooleanPref(db, KEY_DOCK_REFRESH_ON_OPEN, true)

    fun getDockPinned(mode: Int): List<String> =
        db.getString(KEY_DOCK_PINNED_PREFIX + mode, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun setDockPinned(mode: Int, pkgs: List<String>) {
        db.edit().putString(KEY_DOCK_PINNED_PREFIX + mode, pkgs.joinToString(",")).apply()
    }

    fun getDockHidden(mode: Int): Set<String> =
        db.getString(KEY_DOCK_HIDDEN_PREFIX + mode, null)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun setDockHidden(mode: Int, pkgs: Set<String>) {
        db.edit().putString(KEY_DOCK_HIDDEN_PREFIX + mode, pkgs.joinToString(",")).apply()
    }

    var callOverlayEnabled by BooleanPref(db, KEY_CALL_OVERLAY_ENABLED, true)
    var autoDnd by BooleanPref(db, KEY_AUTO_DND, false)
    var lockGesture by BooleanPref(db, KEY_LOCK_GESTURE, false)
    var stayAwake by BooleanPref(db, KEY_STAY_AWAKE, false)
    var noPulseBassHaptics by BooleanPref(db, KEY_PULSE_BASS_DISABLE, true)

    var panelColorMode: Int
        get() = db.getString(KEY_PANEL_COLOR_MODE, "0")?.toIntOrNull() ?: 0
        set(value) = db.edit().putString(KEY_PANEL_COLOR_MODE, value.toString()).apply()

    var panelCustomColor by IntPref(db, KEY_PANEL_CUSTOM_COLOR, 0xFFFF2D78.toInt())
    var launchAppInBubble by BooleanPref(db, KEY_LAUNCH_APP_IN_BUBBLE, true)

    /** Banner image source in Game Hub. */
    var bannerSource by StringPref(db, KEY_BANNER_SOURCE, com.ireddragonicy.gamespace.data.banner.BannerSource.DEFAULT.key)

    /** Auto-fetch hero banner when a game is added. */
    var bannerAutoFetch by BooleanPref(db, KEY_BANNER_AUTO_FETCH, true)

    // ── App-wide appearance (semua activity; overlay panel punya sendiri) ──

    var appThemeMode: Int
        get() = db.getInt(KEY_APP_THEME_MODE, AppThemeMode.AUTO)
        set(value) {
            db.edit().putInt(KEY_APP_THEME_MODE, value).apply()
            AppThemeState.themeMode = value
        }

    var appColorMode: Int
        get() = db.getInt(KEY_APP_COLOR_MODE, 0)
        set(value) {
            db.edit().putInt(KEY_APP_COLOR_MODE, value).apply()
            AppThemeState.colorMode = value
        }

    var appCustomColor: Int
        get() = db.getInt(KEY_APP_CUSTOM_COLOR, AppThemeState.DEFAULT_CUSTOM_COLOR)
        set(value) = setAppCustomColor(value, persist = true)

    /** persist=false → hanya memory, untuk preview live mulus saat drag HSV. */
    fun setAppCustomColor(argb: Int, persist: Boolean) {
        AppThemeState.customColor = argb
        if (persist) db.edit().putInt(KEY_APP_CUSTOM_COLOR, argb).apply()
    }

    // ── Sidebar Style SSOT ──────────────────────────────────────────────
    // SidebarStyle lives in SidebarStyleStore (SharedPreferences "sidebar_style"
    // → "style_v2"). No mirror here: one source of truth, period.

    /** Push nilai persist ke state observable — dipanggil sekali saat boot. */
    fun syncThemeState() {
        AppThemeState.sync(
            themeMode = db.getInt(KEY_APP_THEME_MODE, AppThemeMode.AUTO),
            colorMode = db.getInt(KEY_APP_COLOR_MODE, 0),
            customColor = db.getInt(KEY_APP_CUSTOM_COLOR, AppThemeState.DEFAULT_CUSTOM_COLOR),
        )
    }

    fun getString(key: String, defaultValue: String): String =
        db.getString(key, defaultValue) ?: defaultValue

    fun putString(key: String, value: String) {
        db.edit().putString(key, value).apply()
    }

    companion object {
        const val KEY_AUTO_BRIGHTNESS_DISABLE = "gamespace_auto_brightness_disabled"
        const val KEY_3SCREENSHOT_DISABLE = "gamespace_tfgesture_disabled"
        const val KEY_STAY_AWAKE = "gamespace_stay_awake"
        const val KEY_DANMAKU_NOTIFICATION_MODE = "gamespace_danmaku_notification_mode"
        const val KEY_CALLS_MODE = "gamespace_calls_mode"
        const val KEY_ENABLE_BLUR = "gamespace_enable_blur"
        const val KEY_BLUR_RADIUS = "gamespace_blur_radius"
        const val KEY_RINGER_MODE = "gamespace_ringer_mode"
        const val KEY_LOCK_GESTURE = "gamespace_lock_gesture"
        const val KEY_MENU_OPACITY = "gamespace_menu_opacity"
        const val KEY_TILE_ORDER = "tile_order"
        const val KEY_QUICK_TOGGLE_ORDER = "quick_toggle_order"
        const val KEY_BRIGHTNESS_ENABLED = "brightness_enabled"
        const val KEY_FPS_GRAPH_ENABLED = "fps_graph_enabled"
        const val KEY_QUICK_START_APPS = "quick_start_apps"
        const val KEY_DOCK_LIST_PREFIX = "gamespace_dock_list_mode_"
        const val KEY_DOCK_MAX_APPS = "dock_max_apps"
        const val KEY_DOCK_SORT = "dock_sort"
        const val KEY_DOCK_SOURCE = "dock_source"
        const val KEY_DOCK_AUTO_FILL = "dock_auto_fill"
        const val KEY_DOCK_REFRESH_ON_OPEN = "dock_refresh_on_open"
        const val KEY_DOCK_PINNED_PREFIX = "dock_pinned_mode_"
        const val KEY_DOCK_HIDDEN_PREFIX = "dock_hidden_mode_"
        const val KEY_CALL_OVERLAY_ENABLED = "call_overlay_enabled"
        const val KEY_AUTO_DND = "gamespace_auto_dnd"
        const val KEY_PULSE_BASS_DISABLE = "gamespace_pulse_bass_haptics_disabled"
        const val KEY_PANEL_COLOR_MODE = "gamespace_panel_color_mode"
        const val KEY_PANEL_CUSTOM_COLOR = "gamespace_panel_custom_color"
        const val KEY_LAUNCH_APP_IN_BUBBLE = "gamespace_launch_app_in_bubble"
        const val KEY_BANNER_SOURCE = "gamespace_banner_source"
        const val KEY_BANNER_AUTO_FETCH = "gamespace_banner_auto_fetch"
        const val KEY_APP_THEME_MODE = "gamespace_app_theme_mode"
        const val KEY_APP_COLOR_MODE = "gamespace_app_color_mode"
        const val KEY_APP_CUSTOM_COLOR = "gamespace_app_custom_color"
    }
}
