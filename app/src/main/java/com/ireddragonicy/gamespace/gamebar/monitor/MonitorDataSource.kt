package com.ireddragonicy.gamespace.gamebar.monitor

import com.ireddragonicy.gamespace.telemetry.TelemetrySnapshot
import kotlinx.coroutines.flow.StateFlow

/** One ranked process row for the Processes monitor. */
data class ProcessUsage(val name: String, val usagePercent: Float)

/**
 * Read contract for every monitor widget.
 *
 * A3: instead of the many individual StateFlows [MonitorTelemetry] used to
 * re-project from the bus, we expose the single immutable [TelemetrySnapshot]
 * the bus already emits. Widgets read only the fields they actually render,
 * so one emission -> one recomposition, not many.
 *
 * [MonitorTelemetry] (live) and [DemoMonitorSource] (preview) both implement
 * this, so widgets stay 1:1 regardless of data origin.
 */
interface MonitorDataSource {
    /** One whole-device frame. */
    val snapshot: StateFlow<TelemetrySnapshot>
    /** Real game FPS history (drives the FPS cell / gauge). */
    val fpsHistory: StateFlow<List<Float>>
    /** Top processes by CPU (Processes monitor only). */
    val topProcesses: StateFlow<List<ProcessUsage>>
}