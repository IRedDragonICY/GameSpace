package com.ireddragonicy.gamespace.gamebar.monitor

import android.content.Context
import com.ireddragonicy.gamespace.telemetry.TelemetryBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

data class ProcessUsage(val name: String, val usagePercent: Float)

/**
 * Floating‑monitor telemetry. Now a thin projection of [TelemetryBus]: the bus
 * owns every load/temp/power number, this class only reshapes it into the
 * StateFlows the monitor widgets already collect, plus the unique `top`‑based
 * process list and the DDR clock (not duplicated elsewhere).
 */
@Singleton
class MonitorTelemetry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: TelemetryBus,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _cpuUsage = MutableStateFlow(0f);      val cpuUsage: StateFlow<Float> = _cpuUsage.asStateFlow()
    private val _gpuUsage = MutableStateFlow(0f);      val gpuUsage: StateFlow<Float> = _gpuUsage.asStateFlow()
    private val _ramUsage = MutableStateFlow(0f);      val ramUsage: StateFlow<Float> = _ramUsage.asStateFlow()
    private val _cpuTemp = MutableStateFlow(0f);       val cpuTemp: StateFlow<Float> = _cpuTemp.asStateFlow()
    private val _gpuTemp = MutableStateFlow(0f);       val gpuTemp: StateFlow<Float> = _gpuTemp.asStateFlow()
    private val _batteryTemp = MutableStateFlow(0f);   val batteryTemp: StateFlow<Float> = _batteryTemp.asStateFlow()
    private val _cpuFreq = MutableStateFlow(0);        val cpuFreq: StateFlow<Int> = _cpuFreq.asStateFlow()
    private val _gpuFreq = MutableStateFlow(0);        val gpuFreq: StateFlow<Int> = _gpuFreq.asStateFlow()
    private val _ramUsedGb = MutableStateFlow(0f);     val ramUsedGb: StateFlow<Float> = _ramUsedGb.asStateFlow()
    private val _powerWatt = MutableStateFlow(0f);     val powerWatt: StateFlow<Float> = _powerWatt.asStateFlow()
    private val _freqC0 = MutableStateFlow(0);         val freqC0: StateFlow<Int> = _freqC0.asStateFlow()
    private val _freqC1 = MutableStateFlow(0);         val freqC1: StateFlow<Int> = _freqC1.asStateFlow()
    private val _freqC2 = MutableStateFlow(0);         val freqC2: StateFlow<Int> = _freqC2.asStateFlow()
    private val _freqC3 = MutableStateFlow(0);         val freqC3: StateFlow<Int> = _freqC3.asStateFlow()
    private val _coreFreqs = MutableStateFlow<List<Int>>(List(8) { 0 });  val coreFreqs: StateFlow<List<Int>> = _coreFreqs.asStateFlow()
    private val _coreUsages = MutableStateFlow<List<Float>>(List(8) { 0f }); val coreUsages: StateFlow<List<Float>> = _coreUsages.asStateFlow()
    private val _batteryPct = MutableStateFlow(0);     val batteryPct: StateFlow<Int> = _batteryPct.asStateFlow()
    private val _ddrFreq = MutableStateFlow(6389);     val ddrFreq: StateFlow<Int> = _ddrFreq.asStateFlow()
    private val _topProcesses = MutableStateFlow<List<ProcessUsage>>(emptyList()); val topProcesses: StateFlow<List<ProcessUsage>> = _topProcesses.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        bus.start()
        job = scope.launch {
            // (a) mirror the bus
            launch {
                bus.snapshot.collect { s ->
                    _cpuUsage.value = s.cpuUsageTotal.toFloat()
                    _gpuUsage.value = s.gpuUsage.toFloat()
                    _ramUsage.value = s.ramPct
                    _cpuTemp.value = s.cpuTempC
                    _gpuTemp.value = s.gpuTempC
                    _batteryTemp.value = s.batteryTempC
                    _cpuFreq.value = (s.cpuFreqMaxKhz / 1000L).toInt()
                    _gpuFreq.value = s.gpuFreqMhz
                    _ramUsedGb.value = s.ramUsedGb
                    _powerWatt.value = s.batteryPowerW
                    _batteryPct.value = s.batteryCapacity
                    val cf = s.cpuClusters
                    _freqC0.value = cf.getOrNull(0)?.curKhz?.div(1000)?.toInt() ?: 0
                    _freqC1.value = cf.getOrNull(1)?.curKhz?.div(1000)?.toInt() ?: 0
                    _freqC2.value = cf.getOrNull(2)?.curKhz?.div(1000)?.toInt() ?: 0
                    _freqC3.value = cf.getOrNull(3)?.curKhz?.div(1000)?.toInt() ?: 0
                    _coreUsages.value = (s.cpuCoreUsage + List(8) { 0f }).take(8)
                    _coreFreqs.value = (s.cpuCoreFreqKhz.map { (it / 1000L).toInt() } + List(8) { 0 }).take(8)
                }
            }
            // (b) unique work: DDR clock + process list
            launch {
                while (isActive) {
                    runCatching {
                        val k = java.io.File("/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq")
                            .takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 6389000
                        _ddrFreq.value = (k / 1000) * 2
                    }
                    runCatching {
                        val proc = ProcessBuilder("top", "-n", "1", "-m", "4", "-s", "9").start()
                        val out = mutableListOf<ProcessUsage>()
                        BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                            val p = line.trim().split("\\s+".toRegex())
                            if (p.size >= 9 && p[8].toFloatOrNull() != null && p.last() !in setOf("top", "COMMAND"))
                                out += ProcessUsage(p.last(), p[8].toFloat())
                        }
                        proc.waitFor()
                        _topProcesses.value = out.take(3)
                    }
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        bus.stop()
    }
}
