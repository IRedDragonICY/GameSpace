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
import com.ireddragonicy.gamespace.device.DeviceProfile
import com.ireddragonicy.gamespace.device.DeviceProfiles
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
 * ## Sharing scaling_max_freq with the thermal daemon
 *
 * `scaling_max_freq` is NOT a min-vote aggregation the way an in-kernel
 * `freq_qos` request is — every userspace writer lands in the same
 * `policy->max_freq_req` slot, so the last write simply wins. The
 * mi_thermal_engine SS algo writes that node every time its throttle level
 * changes and then caches the value in `last_written_freq` *without reading
 * the node back* (see `run_predictive()` in ss.rs).
 *
 * That makes a blind write from here actively destructive: raising the node to
 * hw-max while SS believes it still holds a throttle silently disables thermal
 * throttling for that cluster until the temperature happens to cross another
 * level boundary. So this class follows two rules:
 *
 *  1. **Never touch a node we have no opinion about.** A null field means
 *     "inherit", and inherit means *don't write* — not "write the hardware
 *     default". Only a knob the user actually moved is ever written, and it is
 *     released (restored to hw default) exactly once when the user clears it.
 *  2. **Only ever tighten.** [syncCeilingGuard] re-asserts a user ceiling when
 *     the node drifts *above* it — which only happens when SS releases its own
 *     throttle — and never when it sits below, so a live thermal throttle is
 *     never fought.
 */
@Singleton
class PerfTuner @Inject constructor(@ApplicationContext context: Context) {

    /**
     * Immutable hardware description of one cpufreq policy (cluster).
     *
     * Two different "cluster numbers" exist on this platform and conflating
     * them is a real bug source, so they are separate fields with names that
     * cannot be mixed up by accident:
     *
     *  - [policyIndex] is the *kernel* number in `cpufreq/policyN` — 0, 2, 5, 7
     *    on SM8735. It addresses sysfs and keys [PerfProfile.clusters].
     *  - [slot] is the *ordinal* 0..3 position in the mi_thermal_engine cluster
     *    array (`CPU_SCALING_PATHS` in ss.rs, which is positional). It addresses
     *    the throttle curves in a [com.ireddragonicy.gamespace.thermal.custom.CustomThermalProfile].
     *
     * Using policyIndex where a slot is expected reads past the 4-element
     * daemon array (policy 5 / 7 → index 5 / 7) — that was the CPU-tab crash.
     */
    data class CpuCluster(
        val slot: Int,
        val policyIndex: Int,
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

    /** Full per-game tuning profile. Cluster map is keyed by [CpuCluster.policyIndex]. */
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

    /**
     * Lazy, not eager: Hilt builds this singleton from Application.onCreate,
     * which on a cold boot runs before user 0 is unlocked — and credential
     * encrypted storage throws there, taking the whole app down before
     * [GameSpace.killForRestart] can even react. Every read below happens on
     * behalf of a foreground game, so first touch is always post-unlock.
     */
    private val prefs by lazy {
        context.getSharedPreferences("perf_tuner", Context.MODE_PRIVATE)
    }
    /**
     * Single-threaded on purpose. Every apply path mutates [ownedNodes] and
     * orders sysfs writes against a readback, both of which are only coherent
     * if applies are serialised — and applies arrive concurrently (session
     * start, a slider commit and the global save can all land at once).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * Hardware topology is owned by [DeviceProfiles] — the single discovery
     * source (dynamic policy scan, no hardcoded ThermalNodes tables). PerfTuner
     * only layers the tuning-specific views (available OPPs / governors) on top,
     * read from the policy dirs DeviceProfile already found.
     */
    private val deviceProfile: DeviceProfile by lazy { DeviceProfiles.get(context) }

    /**
     * Detected clusters — derived from DeviceProfile on first use (IO-safe).
     *
     * DeviceProfiles scans `cpufreq/policyN` in ascending N, which is exactly
     * the order the daemon's positional cluster array uses, so the list
     * position *is* the daemon slot.
     */
    val cpuClusters: List<CpuCluster> by lazy {
        deviceProfile.cpuClusters.mapIndexed { slot, spec ->
            CpuCluster(
                slot = slot,
                policyIndex = spec.policyIndex,
                name = spec.name,
                policyDir = spec.policyDir,
                availableKhz = KernelNodeIO
                    .readLongList("${spec.policyDir}/scaling_available_frequencies")
                    .sorted(),
                governors = KernelNodeIO
                    .readTokenList("${spec.policyDir}/scaling_available_governors"),
                hwMinKhz = spec.hwMinKhz,
                hwMaxKhz = spec.hwMaxKhz,
                defaultGovernor =
                    KernelNodeIO.read("${spec.policyDir}/scaling_governor") ?: "walt",
            )
        }
    }

    /** Detected GPU capabilities — derived from DeviceProfile's kgsl/devfreq node. */
    val gpuInfo: GpuInfo by lazy {
        val gpu = deviceProfile.gpu
        val freqs = gpu?.availableFreqsPath
            ?.let { KernelNodeIO.readLongList(it) }
            ?.sorted() ?: emptyList()
        GpuInfo(
            availableHz = freqs,
            governors = gpu?.availableGovernorsPath
                ?.let { KernelNodeIO.readTokenList(it) } ?: emptyList(),
            hwMinHz = freqs.firstOrNull() ?: 0L,
            hwMaxHz = freqs.lastOrNull() ?: 0L,
            defaultGovernor =
                gpu?.governorPath?.let { KernelNodeIO.read(it) } ?: "msm-adreno-tz",
        )
    }

    private val _activeProfile = MutableStateFlow<PerfProfile?>(null)
    val activeProfile: StateFlow<PerfProfile?> = _activeProfile.asStateFlow()

    private var activePackage: String? = null

    // ── Live telemetry (cheap fd-cached reads, safe at 1 Hz+) ───────────────

    fun cpuCurKhz(cluster: CpuCluster): Long =
        KernelNodeIO.readLong(ThermalNodes.cpuCurFreq(cluster.policyDir)) ?: 0L

    /**
     * The ceiling actually in force, which is not necessarily the one we asked
     * for: mi_thermal_engine writes scaling_max_freq as well and the kernel
     * keeps whichever is lower. Reading it back is the only way to see how far
     * throttling has pulled the cluster down.
     */
    fun cpuLiveMaxKhz(cluster: CpuCluster): Long =
        KernelNodeIO.readLong(ThermalNodes.cpuScalingMax(cluster.policyDir)) ?: 0L

    fun gpuLiveMaxHz(): Long = KernelNodeIO.readLong(ThermalNodes.GPU_DEVFREQ_MAX) ?: 0L

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
            applyProfile(profile.over(loadGlobalProfile()))
            _activeProfile.value = profile
            if (!profile.isDefault) Log.i(TAG, "Applied perf profile for $packageName")
        }
    }

    /** Update + persist + apply the profile of the active game (from UI). */
    fun updateActiveProfile(profile: PerfProfile) {
        val pkg = activePackage ?: return
        scope.launch {
            saveProfile(pkg, profile)
            applyProfile(profile.over(loadGlobalProfile()))
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
                applyProfile(profile.over(loadGlobalProfile()))
                _activeProfile.value = profile
            }
        }
    }

    // ── Global baseline ─────────────────────────────────────────────────────

    /**
     * The always-on profile. Per-game profiles layer *over* this rather than
     * replacing it, and a session ending falls back here instead of to hardware
     * defaults — that is what makes the global profile behave like a kernel
     * manager instead of a game-only override.
     */
    fun loadGlobalProfile(): PerfProfile = loadProfile(GLOBAL_KEY) ?: PerfProfile()

    fun saveGlobalProfile(profile: PerfProfile) {
        scope.launch {
            saveProfile(GLOBAL_KEY, profile)
            // Outside a session the baseline *is* the live state. Inside one it
            // still shows through every field the game did not override, so the
            // edit has to be re-layered rather than deferred to session end.
            val pkg = activePackage
            applyProfile(
                if (pkg == null) profile
                else (loadProfile(pkg) ?: PerfProfile()).over(profile)
            )
            Log.i(TAG, "Global perf profile saved (default=${profile.isDefault})")
        }
    }

    /** Apply the global baseline — boot, and whenever a game session ends. */
    fun applyGlobal() {
        scope.launch {
            activePackage = null
            applyProfile(loadGlobalProfile())
            _activeProfile.value = null
            Log.i(TAG, "Perf tuning restored to global baseline")
        }
    }

    /**
     * Field-wise overlay: null means "inherit", so a game profile only has to
     * state what it wants to differ. Without this a game with no saved profile
     * would silently wipe the global baseline for the whole session.
     */
    private fun PerfProfile.over(base: PerfProfile): PerfProfile = PerfProfile(
        clusters = (base.clusters.keys + clusters.keys).associateWith { key ->
            val b = base.clusters[key] ?: ClusterTuning()
            val o = clusters[key] ?: ClusterTuning()
            ClusterTuning(
                minKhz = o.minKhz ?: b.minKhz,
                maxKhz = o.maxKhz ?: b.maxKhz,
                governor = o.governor ?: b.governor,
            )
        },
        gpuMinHz = gpuMinHz ?: base.gpuMinHz,
        gpuMaxHz = gpuMaxHz ?: base.gpuMaxHz,
        gpuGovernor = gpuGovernor ?: base.gpuGovernor,
    )

    private fun applyProfile(profile: PerfProfile) {
        cpuClusters.forEach { cluster ->
            val tuning = profile.clusters[cluster.policyIndex] ?: ClusterTuning()
            applyRange(
                minNode = ThermalNodes.cpuScalingMin(cluster.policyDir),
                maxNode = ThermalNodes.cpuScalingMax(cluster.policyDir),
                minReq = snap(tuning.minKhz, cluster.availableKhz),
                maxReq = snap(tuning.maxKhz, cluster.availableKhz),
                hwMin = cluster.hwMinKhz,
                hwMax = cluster.hwMaxKhz,
            )
            applyGovernor(
                node = ThermalNodes.cpuGovernor(cluster.policyDir),
                requested = tuning.governor,
                hwDefault = cluster.defaultGovernor,
                supported = cluster.governors,
            )
        }

        if (gpuInfo.availableHz.isNotEmpty()) {
            val minHz = snap(profile.gpuMinHz, gpuInfo.availableHz)
            val maxHz = snap(profile.gpuMaxHz, gpuInfo.availableHz)
            applyRange(
                minNode = ThermalNodes.GPU_DEVFREQ_MIN,
                maxNode = ThermalNodes.GPU_DEVFREQ_MAX,
                minReq = minHz,
                maxReq = maxHz,
                hwMin = gpuInfo.hwMinHz,
                hwMax = gpuInfo.hwMaxHz,
            )
            // kgsl MHz mirrors keep the vendor pwrlevel logic consistent
            applyRange(
                minNode = ThermalNodes.GPU_MIN_CLOCK_MHZ,
                maxNode = ThermalNodes.GPU_MAX_CLOCK_MHZ,
                minReq = minHz?.div(1_000_000),
                maxReq = maxHz?.div(1_000_000),
                hwMin = gpuInfo.hwMinHz / 1_000_000,
                hwMax = gpuInfo.hwMaxHz / 1_000_000,
            )
            applyGovernor(
                node = ThermalNodes.GPU_GOVERNOR,
                requested = profile.gpuGovernor,
                hwDefault = gpuInfo.defaultGovernor,
                supported = gpuInfo.governors,
            )
        }

        syncCeilingGuard(profile)
    }

    // ── Node ownership (see the class kdoc for why this matters) ────────────

    /**
     * node -> the exact value we last wrote to it.
     *
     * Presence means "we are holding this node"; the value is what lets
     * [release] tell "still ours" from "another writer has taken it over since",
     * which decides whether handing it back is safe.
     */
    private val heldNodes = HashMap<String, String>()

    /**
     * Write a floor/ceiling pair, ordered so the kernel never rejects the write.
     *
     * cpufreq validates each write against the *current* value of the other end
     * of the range: raising the floor above the live ceiling, or lowering the
     * ceiling below the live floor, is refused. Widening therefore has to move
     * the far end first, which is what the read of [maxNode] decides.
     */
    private fun applyRange(
        minNode: String,
        maxNode: String,
        minReq: Long?,
        maxReq: Long?,
        hwMin: Long,
        hwMax: Long,
    ) {
        // A ceiling below the floor is not expressible; the ceiling wins because
        // it is the thermally meaningful half.
        val min = minReq?.coerceAtMost(maxReq ?: hwMax)
        val max = maxReq?.coerceAtLeast(minReq ?: hwMin)
        val liveMax = KernelNodeIO.readLong(maxNode) ?: hwMax
        if (min != null && min > liveMax) {
            applyNode(maxNode, max, hwMax)
            applyNode(minNode, min, hwMin)
        } else {
            applyNode(minNode, min, hwMin)
            applyNode(maxNode, max, hwMax)
        }
    }

    /**
     * Reconcile one node with [requested]; null means the user has no opinion.
     *
     * "No opinion" is deliberately *not* "write [hwDefault]": a node we never
     * claimed belongs to whoever else is driving it (the SS daemon), and writing
     * it would blow away a live thermal throttle.
     */
    private fun applyNode(node: String, requested: Long?, hwDefault: Long) =
        if (requested == null) release(node, hwDefault.toString())
        else hold(node, requested.toString())

    /** Same ownership contract as [applyNode], for string-valued nodes. */
    private fun applyGovernor(
        node: String,
        requested: String?,
        hwDefault: String,
        supported: List<String>,
    ) = when {
        requested == null -> release(node, hwDefault)
        requested in supported -> hold(node, requested)
        else -> Unit // kernel doesn't offer it; nothing to say about this node
    }

    /** Claim [node] and drive it to [value]. */
    private fun hold(node: String, value: String) {
        heldNodes[node] = value
        // Readback instead of a blind write: skips the syscall in the steady
        // state, and still re-asserts after another writer moved the node.
        if (KernelNodeIO.read(node) != value) KernelNodeIO.write(node, value)
    }

    /**
     * Hand [node] back, restoring [hwDefault] — but only while we are still the
     * one holding it.
     *
     * If the node has drifted from our last write, someone more authoritative
     * has taken it over (SS throttling below our ceiling is the case that
     * matters) and restoring the hardware default would silently cancel their
     * limit. Dropping the claim without writing leaves them in charge, which is
     * both the safe direction and the correct one: our request is gone either
     * way, and theirs was the more restrictive of the two.
     */
    private fun release(node: String, hwDefault: String) {
        val held = heldNodes.remove(node) ?: return
        if (KernelNodeIO.read(node) == held) KernelNodeIO.write(node, hwDefault)
    }

    /**
     * Keep a user ceiling in force across a thermal release.
     *
     * SS restores `hw_max_freq` when its throttle level clears, which wipes a
     * ceiling set here — without this the per-game limit quietly stops existing
     * the first time the device gets warm. The guard only writes when the node
     * sits *above* what we asked for, so a live SS throttle (always lower) is
     * left alone and the two writers converge on the more restrictive value
     * instead of ping-ponging.
     *
     * Costs nothing unless a ceiling is actually set: no ceilings, no job.
     */
    private fun syncCeilingGuard(profile: PerfProfile) {
        val ceilings = buildList {
            cpuClusters.forEach { cluster ->
                snap(profile.clusters[cluster.policyIndex]?.maxKhz, cluster.availableKhz)
                    ?.let { add(ThermalNodes.cpuScalingMax(cluster.policyDir) to it) }
            }
            snap(profile.gpuMaxHz, gpuInfo.availableHz)
                ?.let { add(ThermalNodes.GPU_DEVFREQ_MAX to it) }
        }
        guardJob?.cancel()
        if (ceilings.isEmpty()) return
        guardJob = scope.launch {
            while (isActive) {
                delay(GUARD_INTERVAL_MS)
                ceilings.forEach { (node, ceiling) ->
                    val live = KernelNodeIO.readLong(node) ?: return@forEach
                    if (live > ceiling) KernelNodeIO.write(node, ceiling)
                }
            }
        }
    }

    private var guardJob: Job? = null

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

        /**
         * Ceiling re-assert cadence. SS runs at 1 Hz, so 2 s guarantees we see
         * a release within one extra tick while halving the readback traffic —
         * and the latency is invisible next to a DVFS ramp.
         */
        private const val GUARD_INTERVAL_MS = 2_000L

        /**
         * Prefs key for the global baseline. Has no dot, so it can never
         * collide with a real Android package name.
         */
        const val GLOBAL_KEY = "__global__"
    }
}
