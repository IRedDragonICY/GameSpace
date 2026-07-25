/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.telemetry

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.ireddragonicy.gamespace.device.DeviceProfile
import com.ireddragonicy.gamespace.device.DeviceProfiles
import com.ireddragonicy.gamespace.thermal.KernelNodeIO
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
import javax.inject.Inject
import javax.inject.Singleton

/** One cluster's live DVFS state. `scalingMax < hwMax` ⇒ a thermal ceiling is in force. */
data class ClusterSnap(
    val name: String,
    val cpuCount: Int,
    val curKhz: Long,        // avg scaling_cur_freq of the cluster
    val scalingMaxKhz: Long, // current ceiling (thermal .ko / governor)
    val hwMaxKhz: Long,      // silicon max (cpuinfo_max_freq)
)

/** Immutable, whole‑device telemetry frame. Everything the UI shows lives here. */
data class TelemetrySnapshot(
    val cpuUsageTotal: Int = 0,
    val cpuUsagePerCluster: List<Float> = emptyList(),
    val cpuCoreUsage: List<Float> = emptyList(),     // per logical core, 0..100
    val cpuFreqPerClusterMhz: List<Int> = emptyList(),
    val cpuCoreFreqKhz: List<Int> = emptyList(),     // per logical core
    val cpuFreqMaxKhz: Long = 0,                     // busiest core (panel "x.x GHz" cell)
    val cpuTempC: Float = 0f,                        // max over cpu‑typed zones
    val cpuHeat: Float = 0f,                         // 0..1+ vs trip point
    val cpuCeilingCapped: Boolean = false,
    val gpuUsage: Int = 0,
    val gpuFreqMhz: Int = 0,
    val gpuTempC: Float = 0f,                        // max over gpu‑typed zones
    val gpuHeat: Float = 0f,
    val gpuCeilingCapped: Boolean = false,
    val thermalHeadroomReduced: Boolean = false,     // chip‑fact: any ceiling pulled
    val ddrTempC: Float = 0f,
    val batteryTempC: Float = 0f,
    val batteryPowerW: Float = 0f,
    val batteryCapacity: Int = 0,
    val batteryCharging: Boolean = false,
    val ramTotalGb: Float = 0f,
    val ramUsedGb: Float = 0f,
    val ramPct: Float = 0f,
    val swapTotalGb: Float = 0f,
    val swapUsedGb: Float = 0f,
    val gpuUsageHistory: List<Int> = emptyList(),    // 30‑sample ring for the sparkline
    val cpuClusters: List<ClusterSnap> = emptyList(),
    val tickMs: Long = 0,
)

/**
 * Single reader for every thermal / power / load quantity in GameSpace.
 *
 * Why this exists: the panel, the floating monitors and the FPS recorder used
 * to each poll `/proc/stat`, kgsl and the thermal zones on their own cadences,
 * and each *guessed* zone indices (cpu = 9, gpu = 24). On POCO F7 zone 24 is a
 * CX/PA sensor, not the GPU die, so the panel printed 104 °C while the battery
 * sat at 34 °C — a readout nobody could trust. Here the topology comes from
 * [DeviceProfile] (which reads every zone's `type` file), so the GPU number is
 * the real shader‑die sensor, and every consumer sees the *same* value.
 *
 * Aggregation rule: a domain's temperature is the **max** of its resolved zones,
 * because throttling is driven by the hottest sensor in the domain — exactly the
 * value the thermal .ko trips on. Averaging (the old recorder behaviour) dilutes
 * a hot prime / a hot GPU cluster into a comforting lie.
 *
 * Two‑layer honesty for the GPU number: (1) type‑resolution excludes PA/CX zones
 * from the GPU set; (2) if a vendor mislabels a hot zone as "gpu", the trip‑
 * relative [TelemetrySnapshot.gpuHeat] + [TelemetrySnapshot.gpuCeilingCapped]
 * make the colour and the throttle flag agree with reality instead of crying
 * wolf at a fixed 78 °C.
 */
@Singleton
class TelemetryBus @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(TelemetrySnapshot())
    val snapshot: StateFlow<TelemetrySnapshot> = _snapshot.asStateFlow()

    private val lock = Any()
    private var refs = 0
    private var job: Job? = null

    private val profile: DeviceProfile by lazy { DeviceProfiles.get(context) }
    private val am by lazy { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }

    // /proc/stat delta state
    private var statPrimed = false
    private var prevAggTotal = 0L
    private var prevAggIdle = 0L
    private var prevCoreTotal = LongArray(0)
    private var prevCoreIdle = LongArray(0)

    // GPU history ring (feeds the panel sparkline; lives here so it never resets
    // when the header recomposes or the graph is collapsed).
    private val gpuHist = ArrayDeque<Int>(GPU_HIST)

    // Trip‑point references (°C), refreshed occasionally — they don't change at runtime.
    private var cpuTripRef = 0f
    private var gpuTripRef = 0f
    private var tripRefreshIn = 0

    /** Ref‑counted start: the loop runs only while something is observing. */
    fun start() = synchronized(lock) {
        if (refs++ == 0) job = scope.launch { loop() }
    }

    fun stop() = synchronized(lock) {
        if (refs > 0 && --refs == 0) {
            job?.cancel(); job = null
        }
    }

    private suspend fun loop() {
        primeStat()                 // seed the delta baseline so tick #2 is already valid
        delay(TICK_MS)
        while (scope.coroutineContext.isActive) {
            _snapshot.value = buildSnapshot()
            delay(TICK_MS)
        }
    }

    // ── snapshot assembly ──────────────────────────────────────────────────
    private fun buildSnapshot(): TelemetrySnapshot {
        val now = android.os.SystemClock.elapsedRealtime()
        val cpu = computeCpu()

        // Per‑cluster views, aligned to DeviceProfile order (== recorder order).
        val clusterUsage = mutableListOf<Float>()
        val clusterFreqMhz = mutableListOf<Int>()
        val clusterSnaps = mutableListOf<ClusterSnap>()
        var cpuCeiling = false
        for (c in profile.cpuClusters) {
            val usages = c.cpuIds.map { cpu.perCore.getOrNull(it) ?: 0f }
            clusterUsage += if (usages.isNotEmpty()) usages.average().toFloat() else 0f
            val curKhz = c.cpuIds.map { readKhz(coreCurPath(it)) }.filter { it > 0 }
            val avgCur = if (curKhz.isNotEmpty()) curKhz.average().toLong() else 0L
            val scalingMax = KernelNodeIO.readLong("${c.policyDir}/scaling_max_freq") ?: c.hwMaxKhz
            if (scalingMax in 1 until c.hwMaxKhz - 1_000L) cpuCeiling = true
            clusterFreqMhz += (avgCur / 1000L).toInt()
            clusterSnaps += ClusterSnap(c.name, c.cpuIds.size, avgCur, scalingMax, c.hwMaxKhz)
        }

        // Domain temps = max of resolved zones (hottest sensor wins).
        val cpuTemp = maxTemp(profile.cpuTempPaths)
        val gpuTemp = maxTemp(profile.gpuTempPaths)
        val ddrTemp = profile.ddrTempPath?.let { readTempC(it) } ?: 0f

        // Trip‑relative heat (silicon only — battery stays absolute, see heatColor).
        if (tripRefreshIn <= 0) {
            cpuTripRef = resolveTrip(profile.cpuTempPaths, CPU_TRIP_FALLBACK)
            gpuTripRef = resolveTrip(profile.gpuTempPaths, GPU_TRIP_FALLBACK)
            tripRefreshIn = TRIP_REFRESH_TICKS
        } else tripRefreshIn--
        val cpuHeat = if (cpuTripRef > 0f && cpuTemp > 0f) cpuTemp / cpuTripRef else 0f
        val gpuHeat = if (gpuTripRef > 0f && gpuTemp > 0f) gpuTemp / gpuTripRef else 0f

        // GPU load / clock / ceiling.
        val gpu = profile.gpu
        val gpuUsage = readGpuBusy(gpu?.busyPercentPath)
        val gpuFreqMhz = readGpuFreqMhz(gpu)
        val gpuScalingMax = gpu?.devfreqMaxPath?.let { KernelNodeIO.readLong(it) } ?: 0L
        val gpuHwMax = gpuHwMaxHz(gpu)
        val gpuCeiling = gpuScalingMax in 1 until gpuHwMax - 1_000_000L

        // Battery (single reader; the old broadcast receiver is gone).
        val batTemp = profile.batteryTempPath?.let { readTempC(it, deci = true) } ?: 0f
        val curUa = profile.batteryCurrentNowPath?.let { KernelNodeIO.readLong(it) } ?: 0L
        val volUv = profile.batteryVoltageNowPath?.let { KernelNodeIO.readLong(it) } ?: 0L
        val powerW = if (volUv > 0)
            (kotlin.math.abs(curUa.toDouble() * volUv.toDouble()) / 1_000_000_000_000.0).toFloat() else 0f
        val cap = profile.batteryCapacityPath?.let { KernelNodeIO.readInt(it) } ?: 0
        val charging = isCharging()

        // RAM + swap.
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalGb = mem.totalMem / GIB
        val usedGb = (mem.totalMem - mem.availMem) / GIB
        val mi = meminfoMap()
        val swapTotal = mi["SwapTotal"]?.kbToGb() ?: 0f
        val swapUsed = ((mi["SwapTotal"] ?: 0L) - (mi["SwapFree"] ?: 0L)).coerceAtLeast(0).kbToGb()

        gpuHist.addLast(gpuUsage)
        while (gpuHist.size > GPU_HIST) gpuHist.removeFirst()

        return TelemetrySnapshot(
            cpuUsageTotal = cpu.total,
            cpuUsagePerCluster = clusterUsage,
            cpuCoreUsage = cpu.perCore.toList(),
            cpuFreqPerClusterMhz = clusterFreqMhz,
            cpuCoreFreqKhz = cpu.perCoreFreqKhz.map { it.toInt() },
            cpuFreqMaxKhz = cpu.maxFreqKhz,
            cpuTempC = cpuTemp, cpuHeat = cpuHeat, cpuCeilingCapped = cpuCeiling,
            gpuUsage = gpuUsage, gpuFreqMhz = gpuFreqMhz,
            gpuTempC = gpuTemp, gpuHeat = gpuHeat, gpuCeilingCapped = gpuCeiling,
            thermalHeadroomReduced = cpuCeiling || gpuCeiling,
            ddrTempC = ddrTemp,
            batteryTempC = batTemp, batteryPowerW = powerW,
            batteryCapacity = cap, batteryCharging = charging,
            ramTotalGb = totalGb, ramUsedGb = usedGb,
            ramPct = if (totalGb > 0f) (usedGb / totalGb * 100f).coerceIn(0f, 100f) else 0f,
            swapTotalGb = swapTotal, swapUsedGb = swapUsed,
            gpuUsageHistory = gpuHist.toList(),
            cpuClusters = clusterSnaps,
            tickMs = now,
        )
    }

    // ── /proc/stat (one read, fd‑cached, yields total + per‑core usage) ─────
    private data class CpuStat(
        val total: Int, val perCore: FloatArray,
        val perCoreFreqKhz: LongArray, val maxFreqKhz: Long,
    )

    private fun primeStat() {
        val parsed = parseStat() ?: return
        prevAggTotal = parsed.aggTotal; prevAggIdle = parsed.aggIdle
        prevCoreTotal = parsed.coreTotal; prevCoreIdle = parsed.coreIdle
        statPrimed = true
    }

    private fun computeCpu(): CpuStat {
        val p = parseStat()
        val nCores = p?.coreTotal?.size ?: 0
        val perCore = FloatArray(nCores)
        if (statPrimed && p != null) {
            val dT = p.aggTotal - prevAggTotal
            val dI = p.aggIdle - prevAggIdle
            val total = if (dT > 0) ((dT - dI) * 100 / dT).toInt().coerceIn(0, 100) else 0
            for (i in 0 until nCores) {
                val dt = p.coreTotal[i] - prevCoreTotal.getOrElse(i) { p.coreTotal[i] }
                val di = p.coreIdle[i] - prevCoreIdle.getOrElse(i) { p.coreIdle[i] }
                perCore[i] = if (dt > 0) ((dt - di) * 100f / dt).coerceIn(0f, 100f) else 0f
            }
            prevAggTotal = p.aggTotal; prevAggIdle = p.aggIdle
            prevCoreTotal = p.coreTotal; prevCoreIdle = p.coreIdle
            val freqs = LongArray(nCores) { readKhz(coreCurPath(it)) }
            return CpuStat(total, perCore, freqs, freqs.maxOrNull() ?: 0L)
        }
        // first tick after start: no delta yet
        statPrimed = true
        if (p != null) {
            prevAggTotal = p.aggTotal; prevAggIdle = p.aggIdle
            prevCoreTotal = p.coreTotal; prevCoreIdle = p.coreIdle
        }
        val freqs = LongArray(nCores) { readKhz(coreCurPath(it)) }
        return CpuStat(0, perCore, freqs, freqs.maxOrNull() ?: 0L)
    }

    private data class ParsedStat(
        val aggTotal: Long, val aggIdle: Long,
        val coreTotal: LongArray, val coreIdle: LongArray,
    )

    private fun parseStat(): ParsedStat? {
        val text = KernelNodeIO.read("/proc/stat") ?: return null
        var aggT = 0L; var aggI = 0L; var haveAgg = false
        val cT = LongArray(MAX_CORES); val cI = LongArray(MAX_CORES); var top = -1
        for (line in text.lineSequence()) {
            if (!line.startsWith("cpu")) continue
            val parts = line.split(WHITESPACE)
            if (parts.size < 8) continue
            val v0 = parts[1].toLongOrNull() ?: 0L
            val v1 = parts[2].toLongOrNull() ?: 0L
            val v2 = parts[3].toLongOrNull() ?: 0L
            val v3 = parts[4].toLongOrNull() ?: 0L
            val v4 = parts[5].toLongOrNull() ?: 0L
            val v5 = parts[6].toLongOrNull() ?: 0L
            val v6 = parts[7].toLongOrNull() ?: 0L
            val total = v0 + v1 + v2 + v3 + v4 + v5 + v6 + (parts.getOrNull(8)?.toLongOrNull() ?: 0L)
            val idle = v3 + v4
            if (parts[0] == "cpu") { aggT = total; aggI = idle; haveAgg = true }
            else {
                val idx = parts[0].removePrefix("cpu").toIntOrNull() ?: continue
                if (idx in 0 until MAX_CORES) { cT[idx] = total; cI[idx] = idle; if (idx > top) top = idx }
            }
        }
        if (!haveAgg) return null
        val n = (top + 1).coerceAtLeast(0)
        return ParsedStat(aggT, aggI, cT.copyOf(n), cI.copyOf(n))
    }

    // ── small sysfs helpers (all fd‑cached) ────────────────────────────────
    private fun readKhz(path: String): Long = KernelNodeIO.readLong(path) ?: 0L
    private fun readTempC(path: String, deci: Boolean = false): Float {
        val raw = KernelNodeIO.readLong(path) ?: return 0f
        return if (deci) raw / 10f else raw / 1000f
    }
    private fun maxTemp(paths: List<String>): Float {
        var m = 0f
        for (p in paths) { val t = readTempC(p); if (t > m) m = t }   // 0/invalid never wins
        return m
    }
    private fun readGpuBusy(path: String?): Int {
        if (path == null) return 0
        val t = KernelNodeIO.read(path)?.trim() ?: return 0
        // Two possible formats, disambiguated by '%' — NOT by whitespace, since
        // kgsl's percentage form ("47 %") also contains a space and was being
        // misread as busy=47/total=1 → pinned to 100%.
        //   kgsl gpu_busy_percentage: "nn %"       (a percentage)
        //   devfreq gpu load:         "busy total" (two integers, no '%')
        if (t.contains('%')) {
            return t.substringBefore('%').trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
        }
        val ps = t.split(WHITESPACE).filter { it.isNotEmpty() }
        return if (ps.size >= 2) {
            val b = ps[0].toLongOrNull() ?: 0L
            val tot = ps[1].toLongOrNull() ?: 1L
            ((b * 100) / tot.coerceAtLeast(1)).toInt().coerceIn(0, 100)
        } else {
            ps.firstOrNull()?.toIntOrNull()?.coerceIn(0, 100) ?: 0
        }
    }
    private fun readGpuFreqMhz(gpu: com.ireddragonicy.gamespace.device.GpuSpec?): Int {
        if (gpu == null) return 0
        for (p in gpu.curFreqPaths) {
            val raw = KernelNodeIO.readLong(p) ?: continue
            if (raw <= 0) continue
            return when {
                raw > 1_000_000L -> (raw / 1_000_000L).toInt()   // Hz
                raw > 1_000L -> (raw / 1_000L).toInt()           // kHz
                else -> raw.toInt()
            }
        }
        return 0
    }
    private var gpuHwMaxCache = -1L
    private fun gpuHwMaxHz(gpu: com.ireddragonicy.gamespace.device.GpuSpec?): Long {
        if (gpu == null) return 0L
        if (gpuHwMaxCache > 0) return gpuHwMaxCache
        val list = gpu.availableFreqsPath?.let { KernelNodeIO.readLongList(it) }.orEmpty()
        gpuHwMaxCache = list.maxOrNull() ?: 0L
        return gpuHwMaxCache
    }
    private fun isCharging(): Boolean {
        val statusPath = profile.batteryTempPath
            ?.substringBeforeLast('/')?.let { "$it/status" } ?: return false
        if (!KernelNodeIO.exists(statusPath)) return false
        val s = KernelNodeIO.read(statusPath) ?: return false
        // POWER_SUPPLY_STATUS is exactly one of:
        //   Unknown / Charging / Discharging / Not charging / Full
        // This MUST be an exact match: both "Discharging" and "Not charging"
        // *contain* the substring "charging", so contains() reported charging
        // while the battery was actually draining (bolt icon + "+W").
        return s.trim().equals("Charging", ignoreCase = true)
    }
    private fun meminfoMap(): Map<String, Long> = runCatching {
        (KernelNodeIO.read("/proc/meminfo") ?: "").lineSequence().mapNotNull { l ->
            val c = l.indexOf(':'); if (c < 0) null else
                l.substring(0, c) to (l.substring(c + 1).trim().removeSuffix(" kB").toLongOrNull() ?: 0L)
        }.toMap()
    }.getOrDefault(emptyMap())
    private fun Long.kbToGb(): Float = this / 1_048_576f

    /** Highest trip_point_*_temp across the domain (°C); fallback if the kernel exposes none. */
    private fun resolveTrip(paths: List<String>, fallback: Float): Float {
        var best = 0f
        for (p in paths) {
            val zone = p.substringAfterLast("thermal_zone").substringBefore("/")
            for (n in 0..3) {
                val tp = "/sys/class/thermal/thermal_zone$zone/trip_point_${n}_temp"
                if (!KernelNodeIO.exists(tp)) continue
                val v = (KernelNodeIO.readLong(tp) ?: 0L) / 1000f
                if (v > best) best = v
            }
        }
        return if (best > 1f) best else fallback
    }

    private fun coreCurPath(i: Int) = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"

    companion object {
        private const val TAG = "TelemetryBus"
        private const val TICK_MS = 1000L
        private const val MAX_CORES = 16
        private const val GPU_HIST = 30
        private const val TRIP_REFRESH_TICKS = 30
        // Silicon fallbacks used ONLY when no trip_point_* exists. Deliberately high so a
        // normal 75–85 °C session reads orange ("heavy"), not red ("throttling").
        private const val CPU_TRIP_FALLBACK = 95f
        private const val GPU_TRIP_FALLBACK = 95f
        private const val GIB = 1_073_741_824f
        private val WHITESPACE = Regex("\\s+")
    }
}
