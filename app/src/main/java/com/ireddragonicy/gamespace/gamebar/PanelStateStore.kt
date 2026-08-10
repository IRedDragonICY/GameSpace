package com.ireddragonicy.gamespace.gamebar

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.ireddragonicy.gamespace.data.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSOT for session-scoped UI state. Absorbs gamebar/SessionState.kt AND the
 * duplicate fields that lived in TileRepository. SessionState.kt is DELETED.
 */
@Singleton
class PanelStateStore @Inject constructor(
    private val appSettings: AppSettings,
) {
    // ── Screen record ──
    val screenRecordingActive = mutableStateOf(false)
    val screenRecordingStarting = mutableStateOf(false)
    val screenRecordingStartElapsedMs = mutableStateOf(0L)
    val showScreenRecordChooser = mutableStateOf(false)

    // ── FPS Stats recording ──
    val fpsStatsRecordingState = mutableStateOf(false)
    val fpsStatsElapsedSeconds = mutableStateOf(0)

    // ── Panel navigation / overlays ──
    val showPingDetails = mutableStateOf(false)
    val showPerfTuner = mutableStateOf(false)
    val touchTesterExpanded = mutableStateOf(false)
    val keyboardFocusRequested = mutableStateOf(false)
    val gestureLockState = mutableStateOf(false)

    // ── One-shot tuner navigation argument (not snapshot state) ──
    private var requestedTunerTab: TunerTab? = null
    fun requestTuner(tab: TunerTab) {
        requestedTunerTab = tab
        showPerfTuner.value = true
    }
    fun consumeRequestedTunerTab(): TunerTab? =
        requestedTunerTab.also { requestedTunerTab = null }

    // ── Panel display prefs (persisted) ──
    val isBrightnessVisible: MutableState<Boolean> = mutableStateOf(appSettings.brightnessEnabled)
    val isFpsGraphVisible: MutableState<Boolean> = mutableStateOf(appSettings.fpsGraphEnabled)
    val isBlurEnabled: MutableState<Boolean> = mutableStateOf(appSettings.enableBlur)
    val blurRadius: MutableState<Int> = mutableStateOf(appSettings.blurRadius)

    fun setBrightnessEnabled(enabled: Boolean) {
        isBrightnessVisible.value = enabled; appSettings.brightnessEnabled = enabled
    }
    fun setFpsGraphEnabled(enabled: Boolean) {
        isFpsGraphVisible.value = enabled; appSettings.fpsGraphEnabled = enabled
    }
    fun setBlurEnabled(enabled: Boolean) {
        isBlurEnabled.value = enabled; appSettings.enableBlur = enabled
    }
    fun setBlurRadius(radius: Int) {
        blurRadius.value = radius; appSettings.blurRadius = radius
    }

    // ── Floating monitor overlay handles (set by SessionService) ──
    var monitorSettings: com.ireddragonicy.gamespace.gamebar.monitor.MonitorSettings? = null
    var monitorOverlayManager: com.ireddragonicy.gamespace.gamebar.monitor.MonitorOverlayManager? = null

    // ── Callbacks wired by GameSidebar / SessionService ──
    var onGestureLockChanged: ((Boolean) -> Unit)? = null
    var onShowFpsChanged: ((Boolean) -> Unit)? = null
    var onMapControls: (() -> Unit)? = null
    var onFpsStatsToggle: ((Boolean) -> Unit)? = null
    var bringSidebarToFront: (() -> Unit)? = null
}
