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
package com.ireddragonicy.gamespace.gamebar.tiles

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemProperties
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.android.axion.platform.AxPlatformClient
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.touch.GameTouchModeManager
import com.ireddragonicy.gamespace.touch.XiaomiTouchFeatureClient
import com.ireddragonicy.gamespace.gamebar.NetworkPingMonitor
import com.ireddragonicy.gamespace.gamebar.NetworkSpeedMonitor
import javax.inject.Inject
import javax.inject.Singleton

interface TileAction {
    val id: String
    val label: String
    val icon: Int
    val isEnabled: Boolean
    @Composable fun observeEnabled(): State<Boolean>
    fun toggle()
}

class ToggleableTile(
    override val id: String,
    override var label: String,
    override val icon: Int,
    private val state: MutableState<Boolean>,
    private val setter: (Boolean) -> Unit,
) : TileAction {
    override val isEnabled: Boolean get() = state.value
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(state.value)
    override fun toggle() {
        state.value = !state.value
        setter(state.value)
    }
}

class FixedActionTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val action: () -> Unit,
) : TileAction {
    override val isEnabled: Boolean = false
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(false)
    override fun toggle() = action()
}

class PlatformTile(
    override val id: String,
    override val icon: Int,
    private val feature: String,
    private val platform: AxPlatformClient,
) : TileAction {
    val activeState = mutableStateOf(false)
    val labelState = mutableStateOf("")

    override val label: String get() = labelState.value
    override val isEnabled: Boolean get() = activeState.value
    @Composable override fun observeEnabled(): State<Boolean> = rememberUpdatedState(activeState.value)

    override fun toggle() {
        platform.toggle(feature)
    }

    fun updateFromState(state: Bundle) {
        activeState.value = state.getBoolean("active", false)
        val newLabel = AxPlatformClient.getLabel(state)
        if (!newLabel.isNullOrBlank()) labelState.value = newLabel
    }
}

@Singleton
class TileRepository @Inject constructor(
    private val context: Context,
    private val appSettings: AppSettings,
    private val systemSettings: SystemSettings,
) {
    private lateinit var platform: AxPlatformClient

    // Current active game package — set by SessionService
    var currentGamePackage: String? = null

    private lateinit var defaultTiles: List<TileAction>
    private val platformTiles = mutableListOf<PlatformTile>()

    private val platformListener = object : AxPlatformClient.Listener() {
        override fun onFeatureChanged(feature: String, active: Boolean) {
            platformTiles.find { it.id == feature }?.activeState?.value = active
            if (feature == AxPlatformClient.FEATURE_SCREEN_RECORD) {
                screenRecordingActive.value = active
            }
        }

        override fun onStateChanged(key: String, state: Bundle) {
            platformTiles.find { it.id == key }?.updateFromState(state)
            if (key == AxPlatformClient.FEATURE_SCREEN_RECORD) {
                updateScreenRecordState(state)
            }
        }
    }

    /**
     * Sync recording state from SystemUI's recorder. `startElapsedMs` is the
     * recorder's own elapsedRealtime() start timestamp — the sidebar timer is
     * derived from it, so the overlay can never drift from the system
     * recording notification, and survives panel close/reopen.
     */
    private fun updateScreenRecordState(state: Bundle) {
        screenRecordingActive.value = state.getBoolean("active", false)
        screenRecordingStarting.value = state.getBoolean("starting", false)
        screenRecordingStartElapsedMs.value = state.getLong("startElapsedMs", 0L)
    }

    private val _tileOrder = mutableStateListOf<String>()

    val allAvailableTiles: List<TileAction>
        get() = defaultTiles

    private val _tiles = mutableStateListOf<TileAction>()
    val tiles: SnapshotStateList<TileAction> get() = _tiles

    /** Dynamic quick toggle order — persisted in AppSettings */
    private val _quickToggleOrder = mutableStateListOf<String>()

    /** Dynamic tool tile order — persisted in AppSettings */
    private val _toolTileOrder = mutableStateListOf<String>()

    /** Default quick toggle IDs (used on first launch only) */
    private val defaultQuickToggleIds = listOf(
        "lock_gesture", "afme", "htsr", "super_touch",
        "first_frame_boost", "hot_area",
        "game_vibration", "touch_boost",
    )

    /** Quick toggle tiles — shown as horizontal pill chips */
    val quickToggles: List<TileAction>
        get() = _quickToggleOrder.mapNotNull { id ->
            defaultTiles.find { it.id == id }
        }

    /** Tool tiles — shown in 4-column paged grid */
    val toolTiles: List<TileAction>
        get() = _toolTileOrder.mapNotNull { id ->
            defaultTiles.find { it.id == id }
        }

    /** All assigned tile IDs (quick toggles + tool tiles) */
    val allAssignedIds: Set<String>
        get() = _quickToggleOrder.toSet() + _toolTileOrder.toSet()

    val isBrightnessVisible: MutableState<Boolean> = mutableStateOf(appSettings.brightnessEnabled)
    val isFpsGraphVisible: MutableState<Boolean> = mutableStateOf(appSettings.fpsGraphEnabled)
    val isBlurEnabled: MutableState<Boolean> = mutableStateOf(appSettings.enableBlur)

    /** When true, GameSideBar clears FLAG_NOT_FOCUSABLE so keyboard can show */
    val keyboardFocusRequested: MutableState<Boolean> = mutableStateOf(false)
    fun init(platform: AxPlatformClient) {
        this.platform = platform

        defaultTiles = buildDefaultTiles()

        platform.addListener(platformListener)

        refreshPlatformStates()

        // Load quick toggle order
        val savedQuickToggles = appSettings.quickToggleOrder
        _quickToggleOrder.clear()
        if (savedQuickToggles.isNotEmpty()) {
            _quickToggleOrder.addAll(
                savedQuickToggles.filter { id -> defaultTiles.any { it.id == id } }
            )
        } else {
            // First launch — use defaults
            _quickToggleOrder.addAll(
                defaultQuickToggleIds.filter { id -> defaultTiles.any { it.id == id } }
            )
        }

        // Load tool tile order
        val savedToolTiles = appSettings.tileOrder
        _toolTileOrder.clear()
        if (savedToolTiles.isNotEmpty()) {
            _toolTileOrder.addAll(
                savedToolTiles.filter { id -> defaultTiles.any { it.id == id } }
            )
        } else {
            // First launch — everything not in quick toggles
            val qtSet = _quickToggleOrder.toSet()
            _toolTileOrder.addAll(
                defaultTiles.map { it.id }.filter { it !in qtSet }
            )
        }

        // Build combined _tiles list for backward compat
        _tileOrder.clear()
        _tileOrder.addAll(_quickToggleOrder + _toolTileOrder)
        _tiles.clear()
        _tiles.addAll(
            _tileOrder.mapNotNull { id ->
                defaultTiles.find { it.id == id }
            }
        )
    }

    fun refreshPlatformStates() {
        if (!::platform.isInitialized) return
        platformTiles.forEach { tile ->
            val state = platform.getState(tile.id)
            if (!state.isEmpty) tile.updateFromState(state)
        }
        // Prime recorder state (service may start while a recording is running)
        platform.getState(AxPlatformClient.FEATURE_SCREEN_RECORD)
            .takeIf { !it.isEmpty }
            ?.let(::updateScreenRecordState)
    }

    fun dispose() {
        platform.removeListener(platformListener)
    }

    fun setBrightnessEnabled(enabled: Boolean) {
        isBrightnessVisible.value = enabled
        appSettings.brightnessEnabled = enabled
    }

    fun setBlurEnabled(enabled: Boolean) {
        isBlurEnabled.value = enabled
        appSettings.enableBlur = enabled
    }

    fun setFpsGraphEnabled(enabled: Boolean) {
        isFpsGraphVisible.value = enabled
        appSettings.fpsGraphEnabled = enabled
    }

    private fun saveTileOrder() {
        appSettings.tileOrder = _toolTileOrder.toList()
        appSettings.quickToggleOrder = _quickToggleOrder.toList()
    }

    private fun clearBackgroundProcesses() {
        val activityManager: ActivityManager = context.getSystemService(ActivityManager::class.java)
        val runningApps = activityManager.runningAppProcesses ?: return
        var memoryBoosted = false
        runningApps.forEach { processInfo ->
            if (processInfo.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                activityManager.killBackgroundProcesses(processInfo.processName)
                memoryBoosted = true
            }
        }
        if (memoryBoosted)
            Toast.makeText(context, context.getString(R.string.game_memory_boosted), Toast.LENGTH_SHORT).show()
    }

    /** Update tool tile selection from editor */
    fun updateTileSelection(selectedIds: List<String>) {
        _toolTileOrder.clear()
        _toolTileOrder.addAll(selectedIds)
        rebuildCombinedTiles()
        saveTileOrder()
    }

    /** Update quick toggle selection from editor */
    fun updateQuickToggles(ids: List<String>) {
        _quickToggleOrder.clear()
        _quickToggleOrder.addAll(ids)
        rebuildCombinedTiles()
        saveTileOrder()
    }

    /** Move a tile from tool grid to quick toggles */
    fun moveToQuickToggle(tileId: String) {
        _toolTileOrder.remove(tileId)
        if (tileId !in _quickToggleOrder) {
            _quickToggleOrder.add(tileId)
        }
        rebuildCombinedTiles()
        saveTileOrder()
    }

    /** Move a tile from quick toggles to tool grid */
    fun moveToToolGrid(tileId: String) {
        _quickToggleOrder.remove(tileId)
        if (tileId !in _toolTileOrder) {
            _toolTileOrder.add(tileId)
        }
        rebuildCombinedTiles()
        saveTileOrder()
    }

    /** Add tile from available (not assigned) to quick toggles */
    fun addToQuickToggles(tileId: String) {
        if (tileId !in _quickToggleOrder) {
            _quickToggleOrder.add(tileId)
            rebuildCombinedTiles()
            saveTileOrder()
        }
    }

    /** Add tile from available (not assigned) to tool grid */
    fun addToToolGrid(tileId: String) {
        if (tileId !in _toolTileOrder) {
            _toolTileOrder.add(tileId)
            rebuildCombinedTiles()
            saveTileOrder()
        }
    }

    /** Remove tile from whichever zone it's in */
    fun removeTileFromAll(tileId: String) {
        _quickToggleOrder.remove(tileId)
        _toolTileOrder.remove(tileId)
        rebuildCombinedTiles()
        saveTileOrder()
    }

    private fun rebuildCombinedTiles() {
        _tileOrder.clear()
        _tileOrder.addAll(_quickToggleOrder + _toolTileOrder)
        _tiles.clear()
        _tiles.addAll(
            _tileOrder.mapNotNull { id ->
                defaultTiles.find { it.id == id }
            }
        )
    }

    private fun platformTile(feature: String, iconRes: Int, fallbackLabel: String): PlatformTile {
        val tile = PlatformTile(
            id = feature,
            icon = iconRes,
            feature = feature,
            platform = platform,
        )
        tile.labelState.value = fallbackLabel
        platformTiles.add(tile)
        return tile
    }

    private fun buildDefaultTiles(): List<TileAction> = buildList {
        platformTiles.clear()

        add(platformTile(
            AxPlatformClient.FEATURE_WIFI,
            R.drawable.materialsymbols_ic_wifi_rounded_filled,
            context.getString(R.string.tile_wifi),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_BLUETOOTH,
            R.drawable.materialsymbols_ic_bluetooth_rounded_filled,
            context.getString(R.string.tile_bluetooth),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_ZEN,
            R.drawable.materialsymbols_ic_do_not_disturb_on_rounded_filled,
            context.getString(R.string.tile_dnd),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_ROTATION,
            R.drawable.materialsymbols_ic_screen_rotation_up_rounded_filled,
            context.getString(R.string.tile_auto_rotate),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_MOBILE_DATA,
            R.drawable.materialsymbols_ic_android_cell_4_bar_rounded_filled,
            context.getString(R.string.tile_mobile_data),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_AIRPLANE_MODE,
            R.drawable.materialsymbols_ic_flight_rounded_filled,
            context.getString(R.string.tile_airplane_mode),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_FLASHLIGHT,
            R.drawable.materialsymbols_ic_flashlight_on_rounded_filled,
            context.getString(R.string.tile_flashlight),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_DARK_MODE,
            R.drawable.materialsymbols_ic_dark_mode_rounded_filled,
            context.getString(R.string.tile_dark_mode),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_LOCATION,
            R.drawable.materialsymbols_ic_location_on_rounded_filled,
            context.getString(R.string.tile_location),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_BATTERY_SAVER,
            R.drawable.materialsymbols_ic_battery_saver_rounded_filled,
            context.getString(R.string.tile_battery_saver),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_HOTSPOT,
            R.drawable.materialsymbols_ic_wifi_tethering_rounded_filled,
            context.getString(R.string.tile_hotspot),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_NFC,
            R.drawable.materialsymbols_ic_nfc_rounded_filled,
            context.getString(R.string.tile_nfc),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_NIGHT_LIGHT,
            R.drawable.materialsymbols_ic_nights_stay_rounded_filled,
            context.getString(R.string.tile_night_light),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_AOD,
            R.drawable.materialsymbols_ic_aod_rounded_filled,
            context.getString(R.string.tile_aod),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_DATA_SAVER,
            R.drawable.materialsymbols_ic_data_saver_on_rounded_filled,
            context.getString(R.string.tile_data_saver),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_COLOR_INVERSION,
            R.drawable.materialsymbols_ic_invert_colors_rounded_filled,
            context.getString(R.string.tile_color_inversion),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_COLOR_CORRECTION,
            R.drawable.materialsymbols_ic_palette_rounded_filled,
            context.getString(R.string.tile_color_correction),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_REDUCE_BRIGHTNESS,
            R.drawable.materialsymbols_ic_brightness_low_rounded_filled,
            context.getString(R.string.tile_reduce_brightness),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_ONE_HANDED_MODE,
            R.drawable.materialsymbols_ic_phone_android_rounded_filled,
            context.getString(R.string.tile_one_handed),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_HEADS_UP,
            R.drawable.materialsymbols_ic_notifications_active_rounded_filled,
            context.getString(R.string.tile_heads_up),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_AUTO_SYNC,
            R.drawable.materialsymbols_ic_sync_rounded_filled,
            context.getString(R.string.tile_auto_sync),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_CAMERA_PRIVACY,
            R.drawable.materialsymbols_ic_photo_camera_rounded_filled,
            context.getString(R.string.tile_camera_privacy),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_MIC_PRIVACY,
            R.drawable.materialsymbols_ic_mic_rounded_filled,
            context.getString(R.string.tile_mic_privacy),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_WORK_PROFILE,
            R.drawable.materialsymbols_ic_work_rounded_filled,
            context.getString(R.string.tile_work_profile),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_USB_TETHER,
            R.drawable.materialsymbols_ic_usb_rounded_filled,
            context.getString(R.string.tile_usb_tether),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_DREAM,
            R.drawable.materialsymbols_ic_bedtime_rounded_filled,
            context.getString(R.string.tile_dream),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_READING_MODE,
            R.drawable.materialsymbols_ic_menu_book_rounded_filled,
            context.getString(R.string.tile_reading_mode),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_POWER_SHARE,
            R.drawable.materialsymbols_ic_battery_charging_full_rounded_filled,
            context.getString(R.string.tile_power_share),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_CAFFEINE,
            R.drawable.materialsymbols_ic_local_cafe_rounded_filled,
            context.getString(R.string.tile_caffeine),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_VPN,
            R.drawable.materialsymbols_ic_vpn_key_rounded_filled,
            context.getString(R.string.tile_vpn),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_CAST,
            R.drawable.materialsymbols_ic_cast_rounded_filled,
            context.getString(R.string.tile_cast),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_PROFILES,
            R.drawable.materialsymbols_ic_manage_accounts_rounded_filled,
            context.getString(R.string.tile_profiles),
        ))
        add(platformTile(
            AxPlatformClient.FEATURE_SMART_PIXELS,
            R.drawable.materialsymbols_ic_grid_on_rounded_filled,
            context.getString(R.string.tile_smart_pixels),
        ))

        add(platformTile(
            AxPlatformClient.FEATURE_SCREENSHOT,
            R.drawable.materialsymbols_ic_screenshot_rounded_filled,
            context.getString(R.string.tile_screenshot),
        ))

        add(
            ToggleableTile(
                id = "notification",
                label = context.getString(R.string.tile_danmaku),
                icon = R.drawable.materialsymbols_ic_notifications_rounded_filled,
                state = mutableStateOf(appSettings.danmakuNotification),
                setter = {
                    appSettings.danmakuNotification = it
                    systemSettings.headsup = !it
                }
            )
        )

        add(
            ToggleableTile(
                id = "stay_awake",
                label = context.getString(R.string.tile_stay_awake),
                icon = R.drawable.materialsymbols_ic_bedtime_rounded_filled,
                state = mutableStateOf(appSettings.stayAwake),
                setter = {
                    appSettings.stayAwake = it
                    systemSettings.stayAwake = it
                }
            )
        )

        add(
            ToggleableTile(
                id = "fps_info",
                label = context.getString(R.string.tile_fps_info),
                icon = R.drawable.materialsymbols_ic_bar_chart_rounded_filled,
                state = mutableStateOf(appSettings.showFps),
                setter = {
                    appSettings.showFps = it
                    // Live-apply: sidebar starts/stops the TaskFpsCallback now,
                    // not on the next game session
                    onShowFpsChanged?.invoke(it)
                }
            )
        )

        add(
            FixedActionTile(
                id = "boost_memory",
                label = context.getString(R.string.tile_boost_memory),
                icon = R.drawable.materialsymbols_ic_speed_rounded_filled,
                action = {
                    clearBackgroundProcesses()
                }
            )
        )

        add(
            FixedActionTile(
                id = "settings",
                label = context.getString(R.string.tile_settings),
                icon = R.drawable.materialsymbols_ic_settings_rounded_filled,
                action = {
                    val intent = Intent(context,
                        com.ireddragonicy.gamespace.settings.PerAppSettingsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("package_name", currentGamePackage)
                    }
                    context.startActivity(intent)
                }
            )
        )

        if (SystemProperties.getBoolean("persist.sys.target_supports_touch_boost", false)) {
            val touchBoostState = mutableStateOf(
                SystemProperties.getInt("persist.sys.touchboost_enable", 0) == 1
            )
            add(
                ToggleableTile(
                    id = "touch_boost",
                    label = context.getString(R.string.tile_touch_boost),
                    icon = R.drawable.materialsymbols_ic_touch_app_rounded_filled,
                    state = touchBoostState,
                    setter = {
                        val newVal = if (it) 1 else 0
                        SystemProperties.set("persist.sys.touchboost_enable", "$newVal")
                        touchBoostState.value = it
                    }
                )
            )
        }


        // --- Gaming-specific tiles (moved from VerticalPill) ---

        add(
            ToggleableTile(
                id = "lock_gesture",
                label = context.getString(R.string.tile_lock_gesture),
                icon = R.drawable.materialsymbols_ic_lock_rounded_filled,
                state = gestureLockState,
                setter = { onGestureLockChanged?.invoke(it) }
            )
        )

        add(
            ToggleableTile(
                id = "afme",
                label = context.getString(R.string.tile_afme),
                icon = R.drawable.materialsymbols_ic_auto_awesome_rounded_filled,
                state = afmeState,
                setter = {
                    SystemProperties.set("persist.sys.afme.enable", if (it) "1" else "0")
                    afmeState.value = it
                }
            )
        )

        if (com.ireddragonicy.gamespace.BuildFlags.MAPPER_ENABLED) {
            add(
                FixedActionTile(
                    id = "map_controls",
                    label = context.getString(R.string.tile_map_controls),
                    icon = R.drawable.materialsymbols_ic_tune_rounded_filled,
                    action = { onMapControls?.invoke() }
                )
            )
        }

        // --- Xiaomi Touch Feature tiles (from Mi Game Turbo RE) ---

        if (XiaomiTouchFeatureClient.isAvailable) {
            add(
                ToggleableTile(
                    id = "htsr",
                    label = context.getString(R.string.tile_htsr),
                    icon = R.drawable.materialsymbols_ic_swipe_rounded_filled,
                    state = htsrState,
                    setter = { enabled ->
                        htsrState.value = enabled
                        touchModeManager?.toggleHTSR(enabled)
                    }
                )
            )

            add(
                ToggleableTile(
                    id = "super_touch",
                    label = context.getString(R.string.tile_super_touch),
                    icon = R.drawable.materialsymbols_ic_point_scan_rounded_filled,
                    state = superTouchState,
                    setter = { enabled ->
                        superTouchState.value = enabled
                        touchModeManager?.toggleSuperTouch(enabled)
                    }
                )
            )

            add(
                ToggleableTile(
                    id = "first_frame_boost",
                    label = context.getString(R.string.tile_first_frame_boost),
                    icon = R.drawable.materialsymbols_ic_rocket_launch_rounded_filled,
                    state = firstFrameBoostState,
                    setter = { enabled ->
                        firstFrameBoostState.value = enabled
                        touchModeManager?.toggleFirstFrameBoost(enabled)
                    }
                )
            )

            add(
                ToggleableTile(
                    id = "hot_area",
                    label = context.getString(R.string.tile_hot_area),
                    icon = R.drawable.materialsymbols_ic_grid_view_rounded_filled,
                    state = hotAreaState,
                    setter = { enabled ->
                        hotAreaState.value = enabled
                        touchModeManager?.toggleHotArea(enabled)
                    }
                )
            )

            add(
                ToggleableTile(
                    id = "game_vibration",
                    label = context.getString(R.string.tile_game_vibration),
                    icon = R.drawable.materialsymbols_ic_vibration_rounded_filled,
                    state = gameVibrationState,
                    setter = { enabled ->
                        gameVibrationState.value = enabled
                        touchModeManager?.toggleGameVibration(enabled)
                    }
                )
            )
        }

        // --- FPS Stats Recording Tile ---
        add(
            ToggleableTile(
                id = "fps_stats_record",
                label = "FPS Stats",
                icon = R.drawable.materialsymbols_ic_speed_rounded_filled,
                state = fpsStatsRecordingState,
                setter = { onFpsStatsToggle?.invoke(it) }
            )
        )
    }

    // --- Mutable state for gaming tiles (set by GameSidebar) ---

    val gestureLockState: MutableState<Boolean> = mutableStateOf(false)
    val afmeState: MutableState<Boolean> = mutableStateOf(
        SystemProperties.getBoolean("persist.sys.afme.enable", false)
    )

    // --- Touch feature states (from Xiaomi Touch HAL) ---

    val htsrState: MutableState<Boolean> = mutableStateOf(true)
    val superTouchState: MutableState<Boolean> = mutableStateOf(false)
    val firstFrameBoostState: MutableState<Boolean> = mutableStateOf(true)
    val hotAreaState: MutableState<Boolean> = mutableStateOf(true)
    val gameVibrationState: MutableState<Boolean> = mutableStateOf(false)

    /** Touch mode manager, set by SessionService */
    var touchModeManager: GameTouchModeManager? = null

    /** Callback set by GameSidebar to handle gesture lock changes via Settings.Secure */
    var onGestureLockChanged: ((Boolean) -> Unit)? = null

    /** Callback set by GameSidebar to start/stop FPS tracking immediately */
    var onShowFpsChanged: ((Boolean) -> Unit)? = null

    /** Callback set by GameSidebar to enter mapper edit mode */
    var onMapControls: (() -> Unit)? = null

    // --- FPS Stats recording state ---
    val fpsStatsRecordingState: MutableState<Boolean> = mutableStateOf(false)
    val fpsStatsElapsedSeconds: MutableState<Int> = mutableStateOf(0)

    /** Callback set by GameSidebar to start/stop FPS Stats recording */
    var onFpsStatsToggle: ((Boolean) -> Unit)? = null

    /** Game session start time (SystemClock.elapsedRealtime). Set by SessionService. */
    var sessionStartTimeMs: Long = 0L

    /** Panel color mode — exposed from AppSettings for composable access. */
    val panelColorMode = mutableIntStateOf(appSettings.panelColorMode)

    fun setPanelColorMode(mode: Int) {
        appSettings.panelColorMode = mode
        panelColorMode.intValue = mode
    }

    /** Screen recording active state — observed from AxPlatform FEATURE_SCREEN_RECORD */
    val screenRecordingActive: MutableState<Boolean> = mutableStateOf(false)

    /** True while the 3-2-1 countdown runs (record requested, not yet rolling) */
    val screenRecordingStarting: MutableState<Boolean> = mutableStateOf(false)

    /** elapsedRealtime() when the system recorder actually started; 0 when idle */
    val screenRecordingStartElapsedMs: MutableState<Long> = mutableStateOf(0L)

    /** Network ping monitor — measures RTT to game server */
    val networkPingMonitor = NetworkPingMonitor()

    /** Network speed monitor — measures game network traffic */
    val networkSpeedMonitor = NetworkSpeedMonitor()

    /** Current ping latency in ms (-1 = no connection) */
    val pingLatencyMs: MutableState<Int> get() = networkPingMonitor.latencyMs

    /** All connected servers and their latencies */
    val allServerPings: MutableState<List<Pair<String, Int>>> get() = networkPingMonitor.allServerPings

    val rxSpeedKbps: MutableState<Float> get() = networkSpeedMonitor.rxSpeedKbps
    val txSpeedKbps: MutableState<Float> get() = networkSpeedMonitor.txSpeedKbps

    /** Toggle for showing detailed ping list in the main panel */
    val showPingDetails = mutableStateOf(false)

    /** Toggle for the full-page PERF TUNER view (replaces panel content — no scrolling) */
    val showPerfTuner = mutableStateOf(false)
}
