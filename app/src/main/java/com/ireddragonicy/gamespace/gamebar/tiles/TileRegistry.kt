package com.ireddragonicy.gamespace.gamebar.tiles

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemProperties
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.ireddragonicy.gamespace.BuildFlags
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.ActiveSessionStore
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.gamebar.PanelStateStore
import com.ireddragonicy.gamespace.gamebar.SidebarMode
import com.ireddragonicy.gamespace.gamebar.TunerTab
import com.ireddragonicy.gamespace.gamebar.video.DolbyBridge
import com.ireddragonicy.gamespace.gamebar.video.DolbyController
import com.ireddragonicy.gamespace.touch.GameTouchModeManager
import com.ireddragonicy.gamespace.touch.XiaomiTouchFeatureClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TileRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val systemSettings: SystemSettings,
    private val perAppStore: PerAppSettingStore,
    private val panelState: PanelStateStore,
    private val activeSession: ActiveSessionStore,
    private val screenRecord: ScreenRecordController,
    private val dolbyController: DolbyController,
    private val touchModeManager: GameTouchModeManager,
    private val qsTileCatalog: QsTileCatalog,
) {
    private lateinit var defaultTiles: List<TileAction>
    private val _quickToggleOrder = mutableStateListOf<String>()
    private val _toolTileOrder = mutableStateListOf<String>()

    /** Discovered TileService tiles (system + apps) never default into a row. */
    private fun isQsTile(id: String) = id.startsWith("qs:")

    /** Native system toggles (bluetooth, flashlight, ...) — pool only, never default. */
    private val systemTileIds = mutableSetOf<String>()

    private fun canAutoPlace(id: String) = !isQsTile(id) && id !in systemTileIds

    private val defaultQuickToggleIds = listOf(
        "wifi", "mobile_data", "bluetooth", "afme", "super_report", "touch_boost",
    )

    val allAvailableTiles: List<TileAction> get() = defaultTiles
    val quickToggles: List<TileAction>
        get() = _quickToggleOrder.mapNotNull { id -> defaultTiles.find { it.id == id } }
    val toolTiles: List<TileAction>
        get() = _toolTileOrder.mapNotNull { id -> defaultTiles.find { it.id == id } }

    fun init() {
        defaultTiles = buildDefaultTiles()
        val savedQuick = appSettings.quickToggleOrder
        _quickToggleOrder.clear()
        if (savedQuick.isNotEmpty()) {
            val filtered = savedQuick.filter { id -> defaultTiles.any { it.id == id } }
            val stickyDefaults = defaultQuickToggleIds
                .takeWhile { id -> id == "wifi" || id == "mobile_data" }
                .filter { id -> defaultTiles.any { it.id == id } }
            _quickToggleOrder.addAll((stickyDefaults + filtered).distinct())
        } else {
            _quickToggleOrder.addAll(defaultQuickToggleIds.filter { id -> defaultTiles.any { it.id == id } })
        }
        val savedTool = appSettings.tileOrder
        _toolTileOrder.clear()
        if (savedTool.isNotEmpty()) {
            _toolTileOrder.addAll(savedTool.filter { id ->
                defaultTiles.any { it.id == id }
            })
        } else {
            val qt = _quickToggleOrder.toSet()
            _toolTileOrder.addAll(defaultTiles.map { it.id }
                .filter { it !in qt && canAutoPlace(it) })
        }
    }

    private fun saveTileOrder() {
        appSettings.tileOrder = _toolTileOrder.toList()
        appSettings.quickToggleOrder = _quickToggleOrder.toList()
    }

    fun updateTileSelection(ids: List<String>) { _toolTileOrder.clear(); _toolTileOrder.addAll(ids); saveTileOrder() }
    fun updateQuickToggles(ids: List<String>) { _quickToggleOrder.clear(); _quickToggleOrder.addAll(ids); saveTileOrder() }

    fun resetToDefault() {
        _quickToggleOrder.clear()
        _quickToggleOrder.addAll(defaultQuickToggleIds.filter { id -> defaultTiles.any { it.id == id } })
        val qt = _quickToggleOrder.toSet()
        _toolTileOrder.clear()
        _toolTileOrder.addAll(defaultTiles.map { it.id }
            .filter { it !in qt && canAutoPlace(it) })
        saveTileOrder()
    }

    private fun clearBackgroundProcesses() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val running = am.runningAppProcesses ?: return
        var boosted = false
        running.forEach { p ->
            if (p.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                am.killBackgroundProcesses(p.processName); boosted = true
            }
        }
        if (boosted) Toast.makeText(context, context.getString(R.string.game_memory_boosted), Toast.LENGTH_SHORT).show()
    }

    private fun buildDefaultTiles(): List<TileAction> = buildList {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager

        add(WifiTile("wifi", context.getString(R.string.tile_wifi),
            R.drawable.ic_wifi, wifiManager))
        add(MobileDataTile("mobile_data", context.getString(R.string.tile_mobile_data),
            R.drawable.ic_mobile_data, telephonyManager))

        add(ToggleableTile("notification", context.getString(R.string.tile_danmaku),
            R.drawable.materialsymbols_ic_notifications_rounded_filled,
            mutableStateOf(appSettings.danmakuNotification),
            setter = { it ->
                appSettings.danmakuNotification = it
                systemSettings.headsup = !it
            }))
        add(ToggleableTile("stay_awake", context.getString(R.string.tile_stay_awake),
            R.drawable.materialsymbols_ic_bedtime_rounded_filled,
            mutableStateOf(appSettings.stayAwake),
            setter = { it ->
                appSettings.stayAwake = it
                systemSettings.stayAwake = it
            }))
        add(ToggleableTile("fps_info", context.getString(R.string.tile_fps_info),
            R.drawable.materialsymbols_ic_bar_chart_rounded_filled,
            mutableStateOf(appSettings.showFps),
            setter = { it ->
                appSettings.showFps = it
                panelState.onShowFpsChanged?.invoke(it)
            }))
        add(FixedActionTile("boost_memory", context.getString(R.string.tile_boost_memory),
            R.drawable.materialsymbols_ic_speed_rounded_filled) { clearBackgroundProcesses() })
        add(FixedActionTile("settings", context.getString(R.string.tile_settings),
            R.drawable.materialsymbols_ic_settings_rounded_filled) {
            val intent = Intent(context, com.ireddragonicy.gamespace.settings.PerAppSettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("package_name", activeSession.currentPackage)
            }
            context.startActivity(intent)
        })

        // B1: the two platform actions that actually work, kept as real tiles.
        add(FixedActionTile("screenshot", context.getString(R.string.tile_screenshot),
            R.drawable.materialsymbols_ic_screenshot_rounded_filled) {
            context.sendBroadcast(Intent("android.intent.action.SCREENSHOT"))
        })

        if (SystemProperties.getBoolean("persist.sys.target_supports_touch_boost", false)) {
            val touchBoostState = mutableStateOf(SystemProperties.getInt("persist.sys.touchboost_enable", 0) == 1)
            add(ToggleableTile("touch_boost", context.getString(R.string.tile_touch_boost),
                R.drawable.materialsymbols_ic_touch_app_rounded_filled, touchBoostState,
                setter = { it ->
                    SystemProperties.set("persist.sys.touchboost_enable", if (it) "1" else "0")
                    touchBoostState.value = it
                }))
            }
        add(ToggleableTile("lock_gesture", context.getString(R.string.tile_lock_gesture),
            R.drawable.materialsymbols_ic_lock_rounded_filled, panelState.gestureLockState,
            setter = { it ->
                panelState.onGestureLockChanged?.invoke(it)
            }))
        add(AfmeTile("afme", R.drawable.materialsymbols_ic_auto_awesome_rounded_filled,
            context.getString(R.string.tile_afme), perAppStore,
            { activeSession.currentPackage }) { tab: TunerTab -> panelState.requestTuner(tab) })
        if (DolbyBridge.isAvailable(context)) {
            add(DolbyTile("dolby", R.drawable.ic_sound_eq, dolbyController,
                { activeSession.currentPackage }) { tab -> panelState.requestTuner(tab) })
        }
        if (BuildFlags.MAPPER_ENABLED) {
            add(FixedActionTile("map_controls", context.getString(R.string.tile_map_controls),
                R.drawable.materialsymbols_ic_tune_rounded_filled) { panelState.onMapControls?.invoke() })
        }
        // One touch tile, for the one touch feature this platform implements.
        // The HTSR / super-touch / first-frame-boost / hot-area / vibration
        // tiles were removed: the HAL accepted them and the hardware ignored
        // them, so they were quick toggles that could not do anything.
        if (touchModeManager.supportsSuperReport) {
            add(ToggleableTile("super_report", context.getString(R.string.touch_test_super_report),
                R.drawable.materialsymbols_ic_swipe_rounded_filled,
                touchModeManager.superReportEnabled, touchModeManager::toggleSuperReport))
        }
        add(ScreenRecordTile(
            id = "screen_record",
            label = context.getString(R.string.tile_screen_record),
            icon = R.drawable.materialsymbols_ic_screen_record_rounded_filled,
            state = panelState.screenRecordingActive,
            onToggle = {
                screenRecord.toggle()
            },
            onLongClickAction = {
                panelState.showScreenRecordChooser.value = true
            }
        ))
        add(ToggleableTile("fps_stats_record", "FPS Stats",
            R.drawable.materialsymbols_ic_speed_rounded_filled,
            panelState.fpsStatsRecordingState,
            setter = { it -> panelState.onFpsStatsToggle?.invoke(it) }))

        // ── Native system toggles (bluetooth, flashlight, DND, ...) ──
        // ROM-agnostic: public APIs + Settings only. Offered in the pool
        // under the "System" group; never placed by default.
        addSystemTiles()

        // ── Dynamically discovered TileServices (system + app QS tiles) ──
        // Offered in the "available tiles" pool only; never placed by default.
        qsTileCatalog.discoveredTiles.forEach { discovered ->
            add(QsServiceTile(
                id = "qs:" + discovered.componentName.flattenToString(),
                label = discovered.label,
                icon = 0,
                sourceGroup = qsTileCatalog.groupOf(discovered),
                discovered = discovered,
                context = context,
            ))
        }
    }

    /** Adds the ROM-agnostic system tiles to [buildDefaultTiles]. */
    private fun MutableList<TileAction>.addSystemTiles() {
        systemTileIds.clear()

        systemTileIds += "bluetooth"
        add(BluetoothTile("bluetooth", context.getString(R.string.tile_bluetooth),
            R.drawable.materialsymbols_ic_bluetooth_rounded_filled, context) {
            panelState.showBluetoothDevices.value = true
        })

        if (hasFlashlightSupport(context)) {
            systemTileIds += "flashlight"
            add(FlashlightTile("flashlight", context.getString(R.string.tile_flashlight),
                R.drawable.materialsymbols_ic_flashlight_on_rounded_filled, context))
        }

        systemTileIds += "dnd"
        add(DndTile("dnd", context.getString(R.string.tile_dnd),
            R.drawable.materialsymbols_ic_do_not_disturb_on_rounded_filled, context))

        systemTileIds += "airplane"
        add(AirplaneTile("airplane", context.getString(R.string.tile_airplane_mode),
            R.drawable.materialsymbols_ic_flight_rounded_filled, context))

        systemTileIds += "auto_rotate"
        add(AutoRotateTile("auto_rotate", context.getString(R.string.tile_auto_rotate),
            R.drawable.materialsymbols_ic_screen_rotation_up_rounded_filled, context))

        systemTileIds += "rotation_lock"
        add(RotationLockTile("rotation_lock", context.getString(R.string.tile_rotation_lock),
            R.drawable.materialsymbols_ic_rotate_right_rounded_filled, context))

        systemTileIds += "location"
        add(LocationTile("location", context.getString(R.string.tile_location),
            R.drawable.materialsymbols_ic_location_on_rounded_filled, context))

        if (hasNfcSupport(context)) {
            systemTileIds += "nfc"
            add(NfcTile("nfc", context.getString(R.string.tile_nfc),
                R.drawable.materialsymbols_ic_nfc_rounded_filled, context))
        }

        systemTileIds += "hotspot"
        add(HotspotTile("hotspot", context.getString(R.string.tile_hotspot),
            R.drawable.materialsymbols_ic_wifi_tethering_rounded_filled, context))

        systemTileIds += "battery_saver"
        add(BatterySaverTile("battery_saver", context.getString(R.string.tile_battery_saver),
            R.drawable.materialsymbols_ic_battery_saver_rounded_filled, context))

        systemTileIds += "dark_mode"
        add(DarkModeTile("dark_mode", context.getString(R.string.tile_dark_mode),
            R.drawable.materialsymbols_ic_dark_mode_rounded_filled, context))

        systemTileIds += "color_inversion"
        add(ColorInversionTile("color_inversion", context.getString(R.string.tile_color_inversion),
            R.drawable.materialsymbols_ic_invert_colors_rounded_filled, context))

        systemTileIds += "night_light"
        add(NightLightTile("night_light", context.getString(R.string.tile_night_light),
            R.drawable.materialsymbols_ic_nights_stay_rounded_filled, context))

        systemTileIds += "data_saver"
        add(DataSaverTile("data_saver", context.getString(R.string.tile_data_saver),
            R.drawable.materialsymbols_ic_data_saver_on_rounded_filled, context))

        systemTileIds += "usb_tether"
        add(UsbTetherTile("usb_tether", context.getString(R.string.tile_usb_tether),
            R.drawable.materialsymbols_ic_usb_rounded_filled, context))

        systemTileIds += "cast"
        add(CastTile("cast", context.getString(R.string.tile_cast),
            R.drawable.materialsymbols_ic_cast_rounded_filled, context))
    }
}