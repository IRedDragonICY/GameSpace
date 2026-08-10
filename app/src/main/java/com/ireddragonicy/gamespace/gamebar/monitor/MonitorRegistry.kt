/* --- gamespace/gamebar/monitor/MonitorRegistry.kt --- */
package com.ireddragonicy.gamespace.gamebar.monitor

import androidx.compose.runtime.Composable

data class MonitorDef(
    val key: String,
    val enabled: (MonitorSettings) -> Boolean,
    val content: @Composable (MonitorDataSource, MonitorSettings) -> Unit,
)

val MONITORS = listOf(
    MonitorDef("classical", { it.isClassicalMonitorEnabled }, { src, s -> ClassicalMonitorWidget(src, s) }),
    MonitorDef("mini", { it.isMiniMonitorEnabled }, { src, s -> MiniMonitorWidget(src, s) }),
    MonitorDef("processes", { it.isProcessesMonitorEnabled }, { src, s -> ProcessesMonitorWidget(src, s) }),
    MonitorDef("temp", { it.isTempMonitorEnabled }, { src, s -> TempMonitorWidget(src, s) }),
)