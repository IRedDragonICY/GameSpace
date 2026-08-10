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
package com.ireddragonicy.gamespace.thermal.custom

import com.ireddragonicy.gamespace.profile.DaemonProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for a custom SS thermal profile.
 *
 * ## Addressing
 * CPU curves are keyed by **daemon cluster slot** (0 until
 * [CustomProfileContract.MAX_CLUSTERS]), never by the kernel cpufreq policy
 * number. The daemon's `CPU_SCALING_PATHS` array in ss.rs is positional
 * (slot 0 = policy0, 1 = policy2, 2 = policy5, 3 = policy7), so a slot is the
 * only thing the wire format can express. Get the slot from
 * [com.ireddragonicy.gamespace.thermal.PerfTuner.CpuCluster.slot] — the field
 * is named differently from `policyIndex` precisely so the two cannot be
 * swapped silently.
 *
 * ## Units (must match ThermalController and the Rust daemon exactly)
 *  - CPU frequency   : kHz
 *  - GPU frequency   : MHz (both in the JSON "freq" field and the CSV prop)
 *  - All temperatures: milli-degrees-Celsius (mC)
 *
 * ## Shape
 * Everything here is immutable. Edits go through `copy`/[withCpu], so a profile
 * handed to the repository, the daemon and the UI can never be mutated behind
 * any of their backs — the previous mutable-list model aliased the same
 * `MutableList` into the editor, the store and the chart at once.
 *
 * Serialization is manual (not Gson reflection) to stay safe under R8, which
 * strips generic Signature attributes.
 */

/** A single throttle step: trigger temp (mC), clear/hysteresis temp (mC), max frequency. */
data class ThrottleLevel(
    val trigMc: Long = 40_000L,
    val clrMc: Long = 38_000L,
    val freq: Long = 0L,
)

/** Monitor configuration (Feature C). Zero means no limit / follow global. */
data class MonitorLimits(
    val boostMc: Long = 0L,
    val hotplugMc: Long = 0L,
    val backlightMc: Long = 0L,
    val backlightCap: Int = 0,
)

/** SIC charging overrides (Feature D). Zero means follow global config. */
data class SicOverrides(
    val targetMc: Long = 0L,
    val maxFccUa: Long = 0L,
)

/** A complete custom profile. */
data class CustomThermalProfile(
    override val id: String = newId(),
    override val name: String = "",
    /** Throttle curve per daemon cluster slot. A missing slot = unthrottled. */
    val cpu: Map<Int, List<ThrottleLevel>> = emptyMap(),
    val gpu: List<ThrottleLevel> = emptyList(),
    val monitor: MonitorLimits = MonitorLimits(),
    val sic: SicOverrides = SicOverrides(),
) : DaemonProfile {

    val totalCpuLevels: Int get() = cpu.values.sumOf { it.size }

    val hasCurves: Boolean get() = totalCpuLevels > 0 || gpu.isNotEmpty()

    val isEmpty: Boolean
        get() = !hasCurves && monitor == MonitorLimits() && sic == SicOverrides()

    /** Curve for [slot], or empty when that cluster is unthrottled. */
    fun cpuAt(slot: Int): List<ThrottleLevel> = cpu[slot].orEmpty()

    /** Replace one cluster's curve. An empty list removes the entry entirely. */
    fun withCpu(slot: Int, levels: List<ThrottleLevel>): CustomThermalProfile {
        if (slot !in 0 until CustomProfileContract.MAX_CLUSTERS) return this
        val next = cpu.toMutableMap()
        if (levels.isEmpty()) next.remove(slot) else next[slot] = levels
        return copy(cpu = next)
    }

    /** Edit one cluster's curve in place-style, without exposing mutability. */
    inline fun mapCpu(slot: Int, edit: (List<ThrottleLevel>) -> List<ThrottleLevel>) =
        withCpu(slot, edit(cpuAt(slot)))

    /**
     * Full persistence form. Clusters are written **positionally** as a
     * [CustomProfileContract.MAX_CLUSTERS]-element array — that is both the
     * on-disk format of already-saved profiles and the exact shape ss.rs
     * parses, so the map representation stays an internal detail.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("clusters", clustersJson())
        put("gpu", levelsJson(gpu))
        put("monitor", JSONObject().apply {
            put("boost", monitor.boostMc)
            put("hotplug", monitor.hotplugMc)
            put("bl", monitor.backlightMc)
            put("blcap", monitor.backlightCap)
        })
        put("sic", JSONObject().apply {
            put("target", sic.targetMc)
            put("maxfcc", sic.maxFccUa)
        })
    }

    /** The `clusters` array on its own — this is the daemon's chunked payload. */
    fun clustersJson(): JSONArray = JSONArray().apply {
        for (slot in 0 until CustomProfileContract.MAX_CLUSTERS) {
            put(JSONObject().put("levels", levelsJson(cpuAt(slot))))
        }
    }

    private fun levelsJson(levels: List<ThrottleLevel>): JSONArray = JSONArray().apply {
        levels.forEach { lv ->
            put(JSONObject().apply {
                put("trig", lv.trigMc)
                put("clr", lv.clrMc)
                put("freq", lv.freq)
            })
        }
    }

    companion object {
        fun newId(): String = "custom_" + System.currentTimeMillis()

        fun fromJson(obj: JSONObject): CustomThermalProfile {
            val cpu = mutableMapOf<Int, List<ThrottleLevel>>()
            obj.optJSONArray("clusters")?.let { clusters ->
                val n = minOf(CustomProfileContract.MAX_CLUSTERS, clusters.length())
                for (slot in 0 until n) {
                    val levels = clusters.optJSONObject(slot)?.optJSONArray("levels")
                        ?.let(::parseLevels).orEmpty()
                    if (levels.isNotEmpty()) cpu[slot] = levels
                }
            }
            return CustomThermalProfile(
                id = obj.optString("id", newId()),
                name = obj.optString("name", ""),
                cpu = cpu,
                gpu = obj.optJSONArray("gpu")?.let(::parseLevels).orEmpty(),
                monitor = obj.optJSONObject("monitor")?.let { m ->
                    MonitorLimits(
                        boostMc = m.optLong("boost"),
                        hotplugMc = m.optLong("hotplug"),
                        backlightMc = m.optLong("bl"),
                        backlightCap = m.optInt("blcap"),
                    )
                } ?: MonitorLimits(),
                sic = obj.optJSONObject("sic")?.let { s ->
                    SicOverrides(
                        targetMc = s.optLong("target"),
                        maxFccUa = s.optLong("maxfcc"),
                    )
                } ?: SicOverrides(),
            )
        }

        private fun parseLevels(arr: JSONArray): List<ThrottleLevel> =
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    ThrottleLevel(
                        trigMc = it.optLong("trig"),
                        clrMc = it.optLong("clr"),
                        freq = it.optLong("freq"),
                    )
                }
            }
    }
}

/** Daemon contract constants. Mirrors ss.rs on the Rust side exactly. */
object CustomProfileContract {
    const val KEY_CUSTOM_PROFILES = "mithermal_custom_profiles"
    const val CUSTOM_PROFILE_BASE = 1000

    /** Positional cluster slots the daemon exposes (`MAX_CLUSTERS` in ss.rs). */
    const val MAX_CLUSTERS = 4
    const val GPU_MAX_LEVELS = 4
    const val MAX_CPU_LEVELS = 16

    const val PROP_CHUNK_SIZE = 88
    const val PROP_CHUNK_MAX = 64
    const val PROP_PREFIX = "persist.sys.mithermal.custom."
    const val PROP_COUNT = "persist.sys.mithermal.custom.n"
    const val PROP_GPU = "persist.sys.mithermal.custom.gpu"
    const val PROP_MON = "persist.sys.mithermal.custom.mon"
    const val PROP_SIC = "persist.sys.mithermal.custom.sic"
    const val PROP_SEQ = "persist.sys.mithermal.custom.seq"
    const val PROP_SS_PROFILE = "persist.sys.mithermal.ss_profile"
}

/** milli-Celsius to display string, e.g. "42.0 C". */
fun Long.mcToTempString(): String = "%.1f C".format(this / 1000.0)

/** milli-Celsius to degrees Celsius as Float. */
fun Long.mcToC(): Float = this / 1000f

/** Degrees Celsius to milli-Celsius. */
fun Float.cToMc(): Long = (this * 1000f).toLong()

/** kHz to human-readable frequency string. */
fun formatKhz(khz: Long): String =
    if (khz >= 1_000_000L) "%.2f GHz".format(khz / 1_000_000.0)
    else "%.0f MHz".format(khz / 1000.0)

/** MHz to human-readable frequency string. */
fun formatMhz(mhz: Long): String =
    if (mhz >= 1000L) "%.2f GHz".format(mhz / 1000.0)
    else "$mhz MHz"
