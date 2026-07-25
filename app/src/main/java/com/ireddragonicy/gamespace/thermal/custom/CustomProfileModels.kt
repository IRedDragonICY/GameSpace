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

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for a custom SS thermal profile.
 *
 * Storage contract (must match ThermalController and the mi_thermal_engine
 * Rust daemon exactly):
 *  - CPU frequency  : kHz
 *  - GPU frequency  : MHz (both in JSON "freq" field and in the CSV prop)
 *  - All temperatures: milli-degrees-Celsius (mC)
 *  - clusters[0..3] : cpu0 (Silver) / cpu2 (Gold) / cpu5 (Gold+) / cpu7 (Prime)
 *
 * Serialization is done manually (not via Gson reflection) to stay safe under
 * R8, which strips generic Signature attributes. Same pattern as
 * FpsStatsRepository in this codebase.
 */

/** A single throttle step: trigger temp (mC), clear/hysteresis temp (mC), max frequency. */
data class ThrottleLevel(
    var trigMc: Long = 40_000L,
    var clrMc: Long = 38_000L,
    var freq: Long = 0L,
)

/** Monitor configuration (Feature C). Zero means no limit / follow global. */
data class MonitorLimits(
    var boostMc: Long = 0L,
    var hotplugMc: Long = 0L,
    var backlightMc: Long = 0L,
    var backlightCap: Int = 0,
)

/** SIC charging overrides (Feature D). Zero means follow global config. */
data class SicOverrides(
    var targetMc: Long = 0L,
    var maxFccUa: Long = 0L,
)

/**
 * A complete custom profile. [clusters] always has exactly 4 entries (one per
 * CPU cluster); a cluster with no throttle has an empty list.
 */
data class CustomThermalProfile(
    var id: String = "custom_" + System.currentTimeMillis(),
    var name: String = "",
    val clusters: List<MutableList<ThrottleLevel>> = List(4) { mutableListOf() },
    val gpuLevels: MutableList<ThrottleLevel> = mutableListOf(),
    var monitor: MonitorLimits = MonitorLimits(),
    var sic: SicOverrides = SicOverrides(),
) {
    val totalCpuLevels: Int get() = clusters.sumOf { it.size }

    val isEmpty: Boolean
        get() = clusters.all { it.isEmpty() } && gpuLevels.isEmpty() &&
                monitor == MonitorLimits() && sic == SicOverrides()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("clusters", JSONArray().apply {
            clusters.forEach { levels ->
                put(JSONObject().apply {
                    put("levels", JSONArray().apply {
                        levels.forEach { lv ->
                            put(JSONObject().apply {
                                put("trig", lv.trigMc)
                                put("clr", lv.clrMc)
                                put("freq", lv.freq)
                            })
                        }
                    })
                })
            }
        })
        put("gpu", JSONArray().apply {
            gpuLevels.forEach { lv ->
                put(JSONObject().apply {
                    put("trig", lv.trigMc)
                    put("clr", lv.clrMc)
                    put("freq", lv.freq)
                })
            }
        })
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

    companion object {
        fun fromJson(obj: JSONObject): CustomThermalProfile {
            val p = CustomThermalProfile(
                id = obj.optString("id", "custom_" + System.currentTimeMillis()),
                name = obj.optString("name", ""),
            )
            obj.optJSONArray("clusters")?.let { clusters ->
                for (c in 0 until minOf(4, clusters.length())) {
                    val levels = clusters.getJSONObject(c).optJSONArray("levels") ?: continue
                    for (l in 0 until levels.length()) {
                        val lv = levels.getJSONObject(l)
                        p.clusters[c].add(
                            ThrottleLevel(
                                trigMc = lv.optLong("trig"),
                                clrMc = lv.optLong("clr"),
                                freq = lv.optLong("freq"),
                            )
                        )
                    }
                }
            }
            obj.optJSONArray("gpu")?.let { gpu ->
                for (l in 0 until gpu.length()) {
                    val lv = gpu.getJSONObject(l)
                    p.gpuLevels.add(
                        ThrottleLevel(
                            trigMc = lv.optLong("trig"),
                            clrMc = lv.optLong("clr"),
                            freq = lv.optLong("freq"),
                        )
                    )
                }
            }
            obj.optJSONObject("monitor")?.let { m ->
                p.monitor = MonitorLimits(
                    boostMc = m.optLong("boost"),
                    hotplugMc = m.optLong("hotplug"),
                    backlightMc = m.optLong("bl"),
                    backlightCap = m.optInt("blcap"),
                )
            }
            obj.optJSONObject("sic")?.let { s ->
                p.sic = SicOverrides(
                    targetMc = s.optLong("target"),
                    maxFccUa = s.optLong("maxfcc"),
                )
            }
            return p
        }
    }
}

/** Daemon contract constants. Mirrors ThermalProfiles on the Rust side exactly. */
object CustomProfileContract {
    const val KEY_CUSTOM_PROFILES = "mithermal_custom_profiles"
    const val CUSTOM_PROFILE_BASE = 1000
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

    const val UA_PER_WATT = 172_000L
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
