package com.ireddragonicy.gamespace.gamebar

import android.content.Context
import android.util.Log
import com.ireddragonicy.gamespace.charging.ChargingProfileRepository
import com.ireddragonicy.gamespace.data.ActiveSessionStore
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.data.SettingsIO
import com.ireddragonicy.gamespace.display.DisplayColorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of side-effects on session start/stop/mode-change. Extracted from
 * SessionService so the service is just an IPC / foreground-notification shell.
 */
@Singleton
class SessionOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeSession: ActiveSessionStore,
    private val perAppStore: PerAppSettingStore,
    private val network: NetworkTelemetry,
    private val chargingRepo: ChargingProfileRepository,
    private val io: SettingsIO,
) {

    private val displayColorManager by lazy { DisplayColorManager.get(context) }

    companion object {
        private const val PROP_SCHED_LIB_EXTRA = "persist.sys.gamespace.sched_lib_extra"
        private const val SCHED_LIB_NONE = "none"
    }

    fun onSessionStart(packageName: String, mode: Int, gameUid: Int) {
        val currentMode = activeSession.mode
        if (activeSession.isLive && activeSession.currentPackage == packageName && currentMode == mode) return
        Log.d("SessionOrchestrator", "start: $packageName mode=$mode uid=$gameUid")

        activeSession.start(packageName, mode)
        io.setProp("persist.sys.gamespace.game_uid",
            if (mode == SidebarMode.MODE_GAME) gameUid.toString() else "0")
        io.setProp(PROP_SCHED_LIB_EXTRA,
            if (mode == SidebarMode.MODE_GAME) packageName else SCHED_LIB_NONE)

        if (mode == SidebarMode.MODE_GAME) {
            perAppStore.publishForSession(packageName)
            network.start(context, gameUid)
            applyProfilesForGame(packageName)
        }
    }

    fun onSessionModeChanged(newMode: Int) {
        val pkg = activeSession.currentPackage ?: return
        val currentMode = activeSession.mode
        if (currentMode == newMode) return
        Log.d("SessionOrchestrator", "modeChange: $pkg -> $newMode")

        activeSession.start(pkg, newMode)
        io.setProp(PROP_SCHED_LIB_EXTRA,
            if (newMode == SidebarMode.MODE_GAME) pkg else SCHED_LIB_NONE)

        if (newMode == SidebarMode.MODE_GAME) {
            perAppStore.publishForSession(pkg)
            applyProfilesForGame(pkg)
        } else {
            network.stop()
            restoreDefaults()
        }
    }

    fun onSessionEnd() {
        val pkg = activeSession.currentPackage
        Log.d("SessionOrchestrator", "end: $pkg")

        activeSession.stop()
        network.stop()
        io.setProp("persist.sys.gamespace.game_uid", "0")
        io.setProp(PROP_SCHED_LIB_EXTRA, SCHED_LIB_NONE)
        pkg?.let { perAppStore.setFilterLive(false) }
        restoreDefaults()
    }

    private fun applyProfilesForGame(pkg: String) {
        displayColorManager.applyForGame(perAppStore.displayStyle(pkg))
        chargingRepo.applyActiveProfile(pkg)
        val factor = perAppStore.resolution(pkg)
        if (factor != "1.0") {
            val scale = factor.toFloatOrNull() ?: 1.0f
            perAppStore.applyResolutionForSession(pkg, scale)
        }
        // ThermalController is a persistent singleton that observes
        // Settings.System via ContentObserver — it applies the per-app
        // thermal profile automatically when mithermal_app_profiles changes.
        // No direct call needed here.
    }

    private fun restoreDefaults() {
        displayColorManager.restore()
        chargingRepo.applyActiveProfile(null)
        activeSession.currentPackage?.let { pkg ->
            perAppStore.resetResolutionForSession(pkg)
        }
        // ThermalController restores default via its own observer when
        // the foreground app changes to a non-game.
    }
}