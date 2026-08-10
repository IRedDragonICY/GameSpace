package com.ireddragonicy.gamespace.gamebar.tiles

import android.content.Context
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.ActiveSessionStore
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.gamebar.NetworkTelemetry
import com.ireddragonicy.gamespace.gamebar.PanelStateStore
import com.ireddragonicy.gamespace.gamebar.SidebarMode
import com.ireddragonicy.gamespace.gamebar.TunerTab
import com.ireddragonicy.gamespace.gamebar.video.DolbyController
import com.ireddragonicy.gamespace.ui.theme.ThemeStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A2: deprecated facade. It owns NO state; every member below is a passthrough.
 *
 * The real owners are:
 *  - session / current package ........ [ActiveSessionStore]
 *  - tiles & ordering ................. [TileRegistry]
 *  - panel display & UI flags ......... [PanelStateStore]
 *  - accent / color mode .............. [ThemeStore]
 *  - ping / latency / rx/tx ........... [NetworkTelemetry]
 *  - screen record .................... [ScreenRecordController]
 *
 * New code must inject the concrete store. This class survives only so the
 * existing Compose call-sites keep compiling while they are migrated.
 */
@Deprecated(
    message = "Passthrough facade with no state. Inject ActiveSessionStore, " +
        "TileRegistry, PanelStateStore, ThemeStore, NetworkTelemetry or " +
        "ScreenRecordController directly instead."
)
@Singleton
class TileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val systemSettings: SystemSettings,
    private val perAppStore: PerAppSettingStore,
    val dolbyController: DolbyController,
    private val panelState: PanelStateStore,
    private val activeSession: ActiveSessionStore,
    private val themeStore: ThemeStore,
    private val tileRegistry: TileRegistry,
    private val network: NetworkTelemetry,
    private val screenRecord: ScreenRecordController,
    private val touchManager: com.ireddragonicy.gamespace.touch.GameTouchModeManager,
) {
    // ── Session (→ ActiveSessionStore) ────────────────────────────────
    var currentGamePackage: String?
        get() = activeSession.currentPackage
        @Deprecated("Writes happen via ActiveSessionStore.start/stop") set(_) {}
    val sidebarMode get() = activeSession.mode
    val sessionStartTimeMs: Long get() = activeSession.startElapsedMs

    // ── Tiles (→ TileRegistry) ────────────────────────────────────────
    val allAvailableTiles get() = tileRegistry.allAvailableTiles
    val quickToggles get() = tileRegistry.quickToggles
    val toolTiles get() = tileRegistry.toolTiles
    fun init() = tileRegistry.init()
    fun dispose() {}
    fun updateTileSelection(ids: List<String>) = tileRegistry.updateTileSelection(ids)
    fun updateQuickToggles(ids: List<String>) = tileRegistry.updateQuickToggles(ids)
    fun resetToDefault() = tileRegistry.resetToDefault()
    /** Injected singleton — the single owner of touch state (see GameTouchModeManager). */
    val touchModeManager: com.ireddragonicy.gamespace.touch.GameTouchModeManager
        get() = touchManager

    // ── Panel display (→ PanelStateStore) ─────────────────────────────
    val isBrightnessVisible get() = panelState.isBrightnessVisible
    val isFpsGraphVisible get() = panelState.isFpsGraphVisible
    val isBlurEnabled get() = panelState.isBlurEnabled
    val blurRadius get() = panelState.blurRadius
    fun setBrightnessEnabled(e: Boolean) = panelState.setBrightnessEnabled(e)
    fun setFpsGraphEnabled(e: Boolean) = panelState.setFpsGraphEnabled(e)
    fun setBlurEnabled(e: Boolean) = panelState.setBlurEnabled(e)
    fun setBlurRadius(r: Int) = panelState.setBlurRadius(r)

    // ── Panel UI state (→ PanelStateStore) ────────────────────────────
    val keyboardFocusRequested get() = panelState.keyboardFocusRequested
    val showPingDetails get() = panelState.showPingDetails
    val showPerfTuner get() = panelState.showPerfTuner
    val touchTesterExpanded get() = panelState.touchTesterExpanded
    val gestureLockState get() = panelState.gestureLockState
    val showScreenRecordChooser get() = panelState.showScreenRecordChooser
    val showBluetoothDevices get() = panelState.showBluetoothDevices
    val screenRecordingActive get() = panelState.screenRecordingActive
    val screenRecordingStarting get() = panelState.screenRecordingStarting
    val screenRecordingStartElapsedMs get() = panelState.screenRecordingStartElapsedMs
    val fpsStatsRecordingState get() = panelState.fpsStatsRecordingState
    val fpsStatsElapsedSeconds get() = panelState.fpsStatsElapsedSeconds
    var monitorSettings get() = panelState.monitorSettings; set(v) { panelState.monitorSettings = v }
    var monitorOverlayManager get() = panelState.monitorOverlayManager; set(v) { panelState.monitorOverlayManager = v }
    var onGestureLockChanged: ((Boolean) -> Unit)?
        get() = panelState.onGestureLockChanged; set(v) { panelState.onGestureLockChanged = v }
    var onShowFpsChanged: ((Boolean) -> Unit)?
        get() = panelState.onShowFpsChanged; set(v) { panelState.onShowFpsChanged = v }
    var onMapControls: (() -> Unit)?
        get() = panelState.onMapControls; set(v) { panelState.onMapControls = v }
    var onFpsStatsToggle: ((Boolean) -> Unit)?
        get() = panelState.onFpsStatsToggle; set(v) { panelState.onFpsStatsToggle = v }
    var bringSidebarToFront: (() -> Unit)?
        get() = panelState.bringSidebarToFront; set(v) { panelState.bringSidebarToFront = v }
    fun requestTuner(tab: TunerTab) = panelState.requestTuner(tab)
    fun consumeRequestedTunerTab(): TunerTab? = panelState.consumeRequestedTunerTab()

    // ── Theme / accent (→ ThemeStore) ─────────────────────────────────
    val panelColorMode get() = themeStore.panelColorMode
    val panelCustomColor get() = themeStore.panelCustomColor
    fun setPanelColorMode(mode: Int) = themeStore.setPanelColorMode(mode)
    fun setPanelCustomColor(argb: Int, persist: Boolean = true) =
        themeStore.setPanelCustomColor(argb, persist)

    // ── Network (→ NetworkTelemetry) ──────────────────────────────────
    val networkTelemetry get() = network
    val pingLatencyMs get() = network.latencyMs
    val allServerPings get() = network.allServerPings
    val rxSpeedKbps get() = network.rxSpeedKbps
    val txSpeedKbps get() = network.txSpeedKbps

    // ── Screen record (→ ScreenRecordController) ──────────────────────
    fun toggleScreenRecord(targetGameOnly: Boolean = activeSession.mode == SidebarMode.MODE_GAME) = screenRecord.toggle(targetGameOnly)
    fun refreshScreenRecordState() = screenRecord.refresh()

    // ── Misc ──────────────────────────────────────────────────────────
    fun republishAfmeForCurrentGame() {
        activeSession.currentPackage?.let { perAppStore.publishForSession(it) }
    }
}