package com.ireddragonicy.gamespace.gamebar.monitor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.ireddragonicy.gamespace.telemetry.TelemetrySnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlin.random.Random

enum class DemoScenario { IDLE, GAMING, THERMAL, CHARGING }

private data class Baseline(
    val cpu: Float, val gpu: Float, val cpuT: Float, val gpuT: Float, val batT: Float,
    val cpuF: Int, val gpuF: Int, val ram: Float, val watt: Float, val bat: Int,
    val charging: Boolean,
)

private fun baseline(s: DemoScenario) = when (s) {
    DemoScenario.IDLE     -> Baseline( 8f,  4f, 33f, 31f, 29f,  691, 315, 3.1f,  1.4f, 88, false)
    DemoScenario.GAMING   -> Baseline(62f, 78f, 41f, 43f, 37f, 2419, 900, 5.8f,  6.2f, 64, false)
    DemoScenario.THERMAL  -> Baseline(91f, 99f, 47f, 49f, 41f, 1804, 608, 6.4f,  7.8f, 41, false)
    DemoScenario.CHARGING -> Baseline(24f, 30f, 38f, 36f, 34f, 1324, 480, 4.2f, -5.5f, 73, true)
}

/** A3: demo source emits a full [TelemetrySnapshot], same contract as live. */
@Composable
fun rememberDemoSource(scenario: DemoScenario): MonitorDataSource {
    val src = remember { DemoSource() }
    LaunchedEffect(scenario) {
        val b = baseline(scenario)
        val fpsBuf = ArrayDeque<Float>(60).apply { addAll(List(60) { b.cpu * 0.9f + 20f }) }
        while (isActive) {
            val j = { n: Float -> (Random.nextFloat() - 0.5f) * n }
            val cpuVal = (b.cpu + j(6f)).coerceIn(0f, 100f)
            val gpuVal = (b.gpu + j(8f)).coerceIn(0f, 100f)
            val cpuFMhz = (b.cpuF + j(40f)).toInt()

            val f0 = (b.cpuF * 0.4f).toInt()
            val f1 = (b.cpuF * 0.6f).toInt()
            val f2 = (b.cpuF * 0.85f).toInt()
            val f3 = b.cpuF
            val coreFreqsMhz = List(8) { idx -> (b.cpuF * (0.5f + idx * 0.07f)).toInt() }

            fpsBuf.addLast((b.cpu * 0.9f + 20f + j(4f)).coerceAtLeast(0f))
            if (fpsBuf.size > 60) fpsBuf.removeFirst()

            src.snapshot.value = TelemetrySnapshot(
                cpuUsageTotal = cpuVal.toInt(),
                gpuUsage = gpuVal.toInt(),
                cpuFreqMaxKhz = cpuFMhz * 1000L,
                gpuFreqMhz = (b.gpuF + j(30f)).toInt(),
                cpuTempC = b.cpuT + j(1.2f),
                gpuTempC = b.gpuT + j(1.2f),
                batteryTempC = b.batT + j(0.6f),
                cpuFreqPerClusterMhz = listOf(f0, f1, f2, f3),
                cpuCoreUsage = List(8) { idx -> (cpuVal * (0.6f + idx * 0.1f)).coerceIn(0f, 100f) },
                cpuCoreFreqKhz = coreFreqsMhz.map { it * 1000 },
                ramUsedGb = b.ram + j(0.2f),
                ramTotalGb = 12f,
                ramPct = (b.ram / 12f * 100f).coerceIn(0f, 100f),
                batteryPowerW = b.watt + j(0.4f),
                batteryCapacity = b.bat,
                batteryCharging = b.charging,
                gpuUsageHistory = List(30) { gpuVal.toInt() },
            )
            src.fpsHistory.value = fpsBuf.toList()
            delay(1000)
        }
    }
    return src
}

private class DemoSource : MonitorDataSource {
    override val snapshot = MutableStateFlow(TelemetrySnapshot())
    override val fpsHistory = MutableStateFlow<List<Float>>(emptyList())
    override val topProcesses = MutableStateFlow(listOf(
        ProcessUsage("com.miHoYo.GenshinImpact", 142.1f),
        ProcessUsage("com.android.systemui", 5.7f),
        ProcessUsage("com.ireddragonicy.gamespace", 1.7f),
        ProcessUsage("com.whatsapp", 0.3f),
        ProcessUsage("surfaceflinger", 4.5f),
        ProcessUsage("media.hwcodec", 3.2f),
        ProcessUsage("logcat", 1.0f),
    ))
}