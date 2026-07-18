/*
 * Copyright (C) 2026 IRedDragonICY
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ireddragonicy.gamespace.thermal

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Granular CPU/GPU tuning — direct control of cpufreq policies and the
 * Adreno kgsl DVFS from the overlay, per game.
 *
 * Frequencies are validated against the kernel-reported tables
 * (scaling_available_frequencies / gpu_available_frequencies) before any
 * write, so an out-of-range request can never reach the kernel.
 *
 * Interplay with thermal control: these are *ceiling/floor* requests. The
 * mi_thermal_engine SS algo writes scaling_max_freq too — the kernel clamps
 * to the most restrictive request, and the daemon restores hw-max when the
 * profile has no active throttle level, so a custom ceiling set here is
 * only ever tightened, never fought over.
 */
@Singleton
class PerfTuner @Inject constructor(@ApplicationContext context: Context) {

    /** Immutable hardware description of one cpufreq policy (cluster). */
    data class CpuCluster(
        val index: Int,
        val name: String,
        val policyDir: String,
        val availableKhz: List<Long>,
        val governors: List<String>,
        val hwMinKhz: Long,
        val hwMaxKhz: Long,
        val defaultGovernor: String,
    )

    /** Immutable hardware description of the GPU. */
    data class GpuInfo(
        val availableHz: List<Long>,
        val governors: List<String>,
        val hwMinHz: Long,
        val hwMaxHz: Long,
        val defaultGovernor: String,
    )

    /** One cluster's requested tuning; nulls mean "hardware default". */
    data class ClusterTuning(
        val minKhz: Long? = null,
        val maxKhz: Long? = null,
        val governor: String? = null,
    ) {
        val isDefault get() = minKhz == null && maxKhz == null && governor == null
    }

    /** Full per-game tuning profile. */
    data class PerfProfile(
        val clusters: Map<Int, ClusterTuning> = emptyMap(),
        val gpuMinHz: Long? = null,
        val gpuMaxHz: Long? = null,
        val gpuGovernor: String? = null,
    ) {
        val isDefault
            get() = clusters.values.all { it.isDefault } &&
                gpuMinHz == null && gpuMaxHz == null && gpuGovernor == null
    }

    private val prefs = context.getSharedPreferences("perf_tuner", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Detected clusters — probed lazily on first use (IO thread). */
    val cpuClusters: List<CpuCluster> by lazy { probeCpuClusters() }

    /** Detected GPU capabilities. */
    val gpuInfo: GpuInfo by lazy { probeGpu() }

    private val _activeProfile = MutableStateFlow<PerfProfile?>(null)
    val activeProfile: StateFlow<PerfProfile?> = _activeProfile.asStateFlow()

    private var activePackage: String? = null

    // ── Hardware probing ────────────────────────────────────────────────────

    private fun probeCpuClusters(): List<CpuCluster> =
        ThermalNodes.CPU_POLICY_DIRS.withIndex().mapNotNull { (i, dir) ->
            if (!KernelNodeIO.exists(dir)) return@mapNotNull null
            val hwMin = KernelNodeIO.readLong(ThermalNodes.cpuHwMin(dir)) ?: return@mapNotNull null
            val hwMax = KernelNodeIO.readLong(ThermalNodes.cpuHwMax(dir)) ?: return@mapNotNull null
            CpuCluster(
                index = i,
                name = ThermalNodes.CPU_CLUSTER_NAMES.getOrElse(i) { "Cluster$i" },
                policyDir = dir,
                availableKhz = KernelNodeIO
                    .readLongList(ThermalNodes.cpuAvailableFreqs(dir)).sorted(),
                governors = KernelNodeIO
                    .readTokenList(ThermalNodes.cpuAvailableGovernors(dir)),
                hwMinKhz = hwMin,
                hwMaxKhz = hwMax,
                defaultGovernor = KernelNodeIO.read(ThermalNodes.cpuGovernor(dir)) ?: "walt",
            )
        }

    private fun probeGpu(): GpuInfo {
        val freqs = KernelNodeIO.readLongList(ThermalNodes.GPU_AVAILABLE_FREQS).sorted()
        return GpuInfo(
            availableHz = freqs,
            governors = KernelNodeIO.readTokenList(ThermalNodes.GPU_AVAILABLE_GOVERNORS),
            hwMinHz = freqs.firstOrNull() ?: 0L,
            hwMaxHz = freqs.lastOrNull() ?: 0L,
            defaultGovernor = KernelNodeIO.read(ThermalNodes.GPU_GOVERNOR) ?: "msm-adreno-tz",
        )
    }

    // ── Live telemetry (cheap fd-cached reads, safe at 1 Hz+) ───────────────

    fun cpuCurKhz(cluster: CpuCluster): Long =
        KernelNodeIO.readLong(ThermalNodes.cpuCurFreq(cluster.policyDir)) ?: 0L

    fun gpuCurHz(): Long = KernelNodeIO.readLong(ThermalNodes.GPU_CUR_FREQ) ?: 0L

    fun gpuBusyPercent(): Int =
        KernelNodeIO.read(ThermalNodes.GPU_BUSY_PERCENT)
            ?.removeSuffix("%")?.trim()?.toIntOrNull() ?: 0

    // ── Application ─────────────────────────────────────────────────────────

    /** Apply the saved tuning profile for [packageName] (game session start). */
    fun applyForGame(packageName: String) {
        scope.launch {
            activePackage = packageName
            val profile = loadProfile(packageName) ?: PerfProfile()
            applyProfile(profile)
            _activeProfile.value = profile
            if (!profile.isDefault) Log.i(TAG, "Applied perf profile for $packageName")
        }
    }

    /** Update + persist + apply the profile of the active game (from UI). */
    fun updateActiveProfile(profile: PerfProfile) {
        val pkg = activePackage ?: return
        scope.launch {
            saveProfile(pkg, profile)
            applyProfile(profile)
            _activeProfile.value = profile
        }
    }

    /**
     * Persist a profile for any game (hub/per-app editor). Applied to the
     * kernel immediately only when that game is the active session.
     */
    fun saveProfileFor(packageName: String, profile: PerfProfile) {
        scope.launch {
            saveProfile(packageName, profile)
            if (packageName == activePackage) {
                applyProfile(profile)
                _activeProfile.value = profile
            }
        }
    }

    /** Restore hardware defaults (game session end). */
    fun restoreDefaults() {
        scope.launch {
            activePackage = null
            applyProfile(PerfProfile())
            _activeProfile.value = null
            Log.i(TAG, "Perf tuning restored to hardware defaults")
        }
    }

    private fun applyProfile(profile: PerfProfile) {
        cpuClusters.forEach { cluster ->
            val tuning = profile.clusters[cluster.index] ?: ClusterTuning()
            val minKhz = snap(tuning.minKhz, cluster.availableKhz) ?: cluster.hwMinKhz
            val maxKhz = snap(tuning.maxKhz, cluster.availableKhz) ?: cluster.hwMaxKhz
            // min first when lowering max below current min would be rejected
            KernelNodeIO.write(ThermalNodes.cpuScalingMin(cluster.policyDir), minKhz)
            KernelNodeIO.write(ThermalNodes.cpuScalingMax(cluster.policyDir), maxKhz.coerceAtLeast(minKhz))
            val governor = tuning.governor ?: cluster.defaultGovernor
            if (governor in cluster.governors) {
                KernelNodeIO.write(ThermalNodes.cpuGovernor(cluster.policyDir), governor)
            }
        }

        if (gpuInfo.availableHz.isNotEmpty()) {
            val minHz = snap(profile.gpuMinHz, gpuInfo.availableHz) ?: gpuInfo.hwMinHz
            val maxHz = snap(profile.gpuMaxHz, gpuInfo.availableHz) ?: gpuInfo.hwMaxHz
            KernelNodeIO.write(ThermalNodes.GPU_DEVFREQ_MIN, minHz)
            KernelNodeIO.write(ThermalNodes.GPU_DEVFREQ_MAX, maxHz.coerceAtLeast(minHz))
            // kgsl MHz mirrors keep the vendor pwrlevel logic consistent
            KernelNodeIO.write(ThermalNodes.GPU_MIN_CLOCK_MHZ, minHz / 1_000_000)
            KernelNodeIO.write(ThermalNodes.GPU_MAX_CLOCK_MHZ, maxHz.coerceAtLeast(minHz) / 1_000_000)
            val governor = profile.gpuGovernor ?: gpuInfo.defaultGovernor
            if (governor in gpuInfo.governors) {
                KernelNodeIO.write(ThermalNodes.GPU_GOVERNOR, governor)
            }
        }
    }

    /** Snap a requested frequency to the nearest kernel-supported step. */
    private fun snap(requested: Long?, table: List<Long>): Long? {
        if (requested == null || table.isEmpty()) return requested
        return table.minByOrNull { kotlin.math.abs(it - requested) }
    }

    // ── Persistence (app-private prefs, JSON per package) ───────────────────

    fun loadProfile(packageName: String): PerfProfile? {
        val raw = prefs.getString(packageName, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            PerfProfile(
                clusters = o.optJSONObject("cpu")?.let { cpu ->
                    cpu.keys().asSequence().associate { key ->
                        val c = cpu.getJSONObject(key)
                        key.toInt() to ClusterTuning(
                            minKhz = c.optLong("min").takeIf { it > 0 },
                            maxKhz = c.optLong("max").takeIf { it > 0 },
                            governor = c.optString("gov").takeIf { it.isNotEmpty() },
                        )
                    }
                } ?: emptyMap(),
                gpuMinHz = o.optLong("gpuMin").takeIf { it > 0 },
                gpuMaxHz = o.optLong("gpuMax").takeIf { it > 0 },
                gpuGovernor = o.optString("gpuGov").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    private fun saveProfile(packageName: String, profile: PerfProfile) {
        if (profile.isDefault) {
            prefs.edit().remove(packageName).apply()
            return
        }
        val o = JSONObject()
        val cpu = JSONObject()
        profile.clusters.forEach { (idx, t) ->
            if (!t.isDefault) {
                cpu.put(
                    idx.toString(),
                    JSONObject()
                        .put("min", t.minKhz ?: 0)
                        .put("max", t.maxKhz ?: 0)
                        .put("gov", t.governor ?: "")
                )
            }
        }
        o.put("cpu", cpu)
        profile.gpuMinHz?.let { o.put("gpuMin", it) }
        profile.gpuMaxHz?.let { o.put("gpuMax", it) }
        profile.gpuGovernor?.let { o.put("gpuGov", it) }
        prefs.edit().putString(packageName, o.toString()).apply()
    }

    companion object {
        private const val TAG = "PerfTuner"
    }
}
