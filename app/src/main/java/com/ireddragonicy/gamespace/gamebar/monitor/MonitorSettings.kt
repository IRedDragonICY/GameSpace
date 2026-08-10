/* --- gamespace/gamebar/monitor/MonitorSettings.kt --- */
package com.ireddragonicy.gamespace.gamebar.monitor

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonitorSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("monitor_settings", Context.MODE_PRIVATE)

    private val _classical = mutableStateOf(prefs.getBoolean("classical_monitor_enabled", false))
    var isClassicalMonitorEnabled: Boolean
        get() = _classical.value
        set(value) { _classical.value = value; prefs.edit().putBoolean("classical_monitor_enabled", value).apply() }

    private val _mini = mutableStateOf(prefs.getBoolean("mini_monitor_enabled", false))
    var isMiniMonitorEnabled: Boolean
        get() = _mini.value
        set(value) { _mini.value = value; prefs.edit().putBoolean("mini_monitor_enabled", value).apply() }

    private val _processes = mutableStateOf(prefs.getBoolean("processes_monitor_enabled", false))
    var isProcessesMonitorEnabled: Boolean
        get() = _processes.value
        set(value) { _processes.value = value; prefs.edit().putBoolean("processes_monitor_enabled", value).apply() }

    private val _temp = mutableStateOf(prefs.getBoolean("temp_monitor_enabled", false))
    var isTempMonitorEnabled: Boolean
        get() = _temp.value
        set(value) { _temp.value = value; prefs.edit().putBoolean("temp_monitor_enabled", value).apply() }

    fun isAnyMonitorEnabled(): Boolean =
        isClassicalMonitorEnabled || isMiniMonitorEnabled || isProcessesMonitorEnabled || isTempMonitorEnabled

    fun getMonitorPosition(key: String, defaultX: Float, defaultY: Float): Offset {
        val x = prefs.getFloat("${key}_x", defaultX)
        val y = prefs.getFloat("${key}_y", defaultY)
        return Offset(x, y)
    }

    fun saveMonitorPosition(key: String, offset: Offset) {
        prefs.edit()
            .putFloat("${key}_x", offset.x)
            .putFloat("${key}_y", offset.y)
            .apply()
    }

    // Pinned = Ghost Mode (Passthrough touch)
    private val _pinned = mutableStateOf(prefs.getBoolean("monitors_pinned", true))
    var isPinned: Boolean
        get() = _pinned.value
        set(value) { _pinned.value = value; prefs.edit().putBoolean("monitors_pinned", value).apply() }
        
    private val _opacity = mutableStateOf(prefs.getFloat("monitors_opacity", 0.85f))
    var opacity: Float
        get() = _opacity.value
        set(value) { _opacity.value = value; prefs.edit().putFloat("monitors_opacity", value).apply() }

    // --- Granular Toggles ---
    // Classical Monitor
    private val _classicalShowCpu = mutableStateOf(prefs.getBoolean("classical_show_cpu", true))
    var classicalShowCpu: Boolean get() = _classicalShowCpu.value; set(v) { _classicalShowCpu.value = v; prefs.edit().putBoolean("classical_show_cpu", v).apply() }

    private val _classicalShowGpu = mutableStateOf(prefs.getBoolean("classical_show_gpu", true))
    var classicalShowGpu: Boolean get() = _classicalShowGpu.value; set(v) { _classicalShowGpu.value = v; prefs.edit().putBoolean("classical_show_gpu", v).apply() }

    private val _classicalShowRam = mutableStateOf(prefs.getBoolean("classical_show_ram", true))
    var classicalShowRam: Boolean get() = _classicalShowRam.value; set(v) { _classicalShowRam.value = v; prefs.edit().putBoolean("classical_show_ram", v).apply() }

    private val _classicalShowFps = mutableStateOf(prefs.getBoolean("classical_show_fps", true))
    var classicalShowFps: Boolean get() = _classicalShowFps.value; set(v) { _classicalShowFps.value = v; prefs.edit().putBoolean("classical_show_fps", v).apply() }

    private val _classicalShowTemp = mutableStateOf(prefs.getBoolean("classical_show_temp", true))
    var classicalShowTemp: Boolean get() = _classicalShowTemp.value; set(v) { _classicalShowTemp.value = v; prefs.edit().putBoolean("classical_show_temp", v).apply() }

    private val _classicalShowNetwork = mutableStateOf(prefs.getBoolean("classical_show_network", true))
    var classicalShowNetwork: Boolean get() = _classicalShowNetwork.value; set(v) { _classicalShowNetwork.value = v; prefs.edit().putBoolean("classical_show_network", v).apply() }

    // Mini Monitor
    private val _miniShowCpu = mutableStateOf(prefs.getBoolean("mini_show_cpu", true))
    var miniShowCpu: Boolean get() = _miniShowCpu.value; set(v) { _miniShowCpu.value = v; prefs.edit().putBoolean("mini_show_cpu", v).apply() }

    private val _miniShowGpu = mutableStateOf(prefs.getBoolean("mini_show_gpu", true))
    var miniShowGpu: Boolean get() = _miniShowGpu.value; set(v) { _miniShowGpu.value = v; prefs.edit().putBoolean("mini_show_gpu", v).apply() }

    private val _miniShowRam = mutableStateOf(prefs.getBoolean("mini_show_ram", true))
    var miniShowRam: Boolean get() = _miniShowRam.value; set(v) { _miniShowRam.value = v; prefs.edit().putBoolean("mini_show_ram", v).apply() }

    private val _miniShowFps = mutableStateOf(prefs.getBoolean("mini_show_fps", true))
    var miniShowFps: Boolean get() = _miniShowFps.value; set(v) { _miniShowFps.value = v; prefs.edit().putBoolean("mini_show_fps", v).apply() }

    // Temperature Monitor
    private val _tempShowCpu = mutableStateOf(prefs.getBoolean("temp_show_cpu", true))
    var tempShowCpu: Boolean get() = _tempShowCpu.value; set(v) { _tempShowCpu.value = v; prefs.edit().putBoolean("temp_show_cpu", v).apply() }

    private val _tempShowGpu = mutableStateOf(prefs.getBoolean("temp_show_gpu", true))
    var tempShowGpu: Boolean get() = _tempShowGpu.value; set(v) { _tempShowGpu.value = v; prefs.edit().putBoolean("temp_show_gpu", v).apply() }

    private val _tempShowBattery = mutableStateOf(prefs.getBoolean("temp_show_battery", true))
    var tempShowBattery: Boolean get() = _tempShowBattery.value; set(v) { _tempShowBattery.value = v; prefs.edit().putBoolean("temp_show_battery", v).apply() }

    // Processes Monitor
    private val _processesShowSystem = mutableStateOf(prefs.getBoolean("processes_show_system", false))
    var processesShowSystem: Boolean get() = _processesShowSystem.value; set(v) { _processesShowSystem.value = v; prefs.edit().putBoolean("processes_show_system", v).apply() }
}
