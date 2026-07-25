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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.ui.graphics.vector.ImageVector
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import kotlin.math.abs

/**
 * Smart curve generator. Translates a simple user intent (target temperature
 * plus a performance-vs-cool priority) into a concrete, valid throttle curve
 * using the real kernel frequency tables from [PerfTuner].
 *
 * Design principles:
 *  1. The user picks a target temperature and a priority slider; the engine
 *     produces the full curve automatically.
 *  2. Every frequency is snapped to an OPP the kernel actually supports.
 *  3. Hysteresis (clear < trigger) and ascending triggers are guaranteed by
 *     construction, so the output is always valid.
 *  4. Frequencies are forced to decrease across levels so throttling is
 *     genuinely progressive.
 */

/** User intent expressed in simple, understandable terms. */
data class CurveConfig(
    /** Target sustained temperature in degrees C. */
    val targetTempC: Float = 42f,
    /** How many degrees below target throttling begins (the spread). */
    val startOffsetC: Float = 6f,
    /** 0 = relaxed (few levels), 100 = aggressive (many steep levels). */
    val aggressiveness: Int = 40,
    /** Minimum frequency as a percentage of hardware max (floor). */
    val floorPercent: Int = 45,
)

/** A one-click preset: a named [CurveConfig] with a Material icon and description. */
data class CurvePreset(
    val id: String,
    val icon: ImageVector,
    val name: String,
    val description: String,
    val config: CurveConfig,
)

/** Sumber seed: scene vendor (intent perilaku, BUKAN freq hardcoded) atau profil user. */
sealed class ProfileSource {
    abstract val id: String
    abstract val name: String
    abstract val description: String

    data class Vendor(
        override val id: String,
        override val name: String,
        override val description: String,
        val sconfig: Int,
        val config: CurveConfig?,      // null = unrestricted (Performance/NOLIMITS)
        val includeGpu: Boolean,
    ) : ProfileSource()

    data class Custom(
        override val id: String,
        override val name: String,
        override val description: String,
    ) : ProfileSource()
}

// Intent perilaku per sconfig scene. Frekuensi TIDAK ada di sini —
// di-generate dari tabel OPP kernel (PerfTuner) saat materialize.
private val SCENE_CONFIGS: Map<Int, CurveConfig?> = mapOf(
    0   to CurveConfig(40f, 14f, 65, 25),  // GLOBAL
    6   to null,                            // NOLIMITS
    19  to CurveConfig(42f, 6f, 45, 45),   // MGAME
    18  to CurveConfig(46f, 3f, 30, 60),   // TGAME
    20  to CurveConfig(49f, 2f, 20, 72),   // YUANSHEN
    25  to CurveConfig(46f, 6f, 30, 55),   // XINGTIE
    26  to CurveConfig(45f, 5f, 25, 65),   // HIGHFPS
    15  to CurveConfig(43f, 5f, 40, 40),   // CAMERA
    16  to CurveConfig(45f, 5f, 30, 55),   // 4K
    11  to CurveConfig(43f, 8f, 45, 40),   // VIDEO
    14  to CurveConfig(42f, 8f, 45, 40),   // VIDEOCHAT
    10  to CurveConfig(42f, 7f, 45, 40),   // NAVIGATION
    5   to CurveConfig(41f, 10f, 55, 30),  // PHONE
    9   to CurveConfig(43f, 6f, 40, 45),   // ARVR
    1   to CurveConfig(41f, 6f, 45, 40),   // HUANJI
    -2  to CurveConfig(37f, 8f, 70, 20),   // powersave
)
private val GPU_SCENES = setOf(19, 18, 20, 25, 26, 9)

object CurveSuggestionEngine {

    /** 16 scene vendor, nama & sconfig diambil dari katalog ThermalProfiles (bukan list paralel). */
    val VENDOR_TEMPLATES: List<ProfileSource.Vendor> =
        ThermalProfiles.PROFILE_NAMES.indices
            .filter { it != ThermalProfiles.PROFILE_AUTO }   // skip "Auto"
            .map { i ->
                val sconfig = ThermalProfiles.PROFILE_SCONFIG[i]
                ProfileSource.Vendor(
                    id = "vendor_$i",
                    name = ThermalProfiles.PROFILE_NAMES[i],
                    description = "Vendor scene (sconfig $sconfig)",
                    sconfig = sconfig,
                    config = SCENE_CONFIGS[sconfig],
                    includeGpu = sconfig in GPU_SCENES,
                )
            }

    /** Materialize scene vendor → profil penuh. Kurva di-snap ke tabel kernel. */
    fun generateFromVendor(
        perfTuner: PerfTuner,
        vendor: ProfileSource.Vendor,
        name: String,
        id: String = "custom_" + System.currentTimeMillis(),
    ): CustomThermalProfile {
        val profile = CustomThermalProfile(id = id, name = name)
        val cfg = vendor.config ?: return profile   // NOLIMITS → kosong = unrestricted
        perfTuner.cpuClusters.forEach { cluster ->
            val idx = cluster.index
            if (idx in profile.clusters.indices) {
                profile.clusters[idx].clear()
                profile.clusters[idx].addAll(suggestCpuCurve(cluster, cfg))
            }
        }
        if (vendor.includeGpu) {
            profile.gpuLevels.clear()
            profile.gpuLevels.addAll(suggestGpuCurve(perfTuner.gpuInfo, cfg))
        }
        return profile
    }

    /** Four primary presets covering the vast majority of use cases. */
    val PRESETS = listOf(
        CurvePreset(
            id = "cool",
            icon = Icons.Rounded.AcUnit,
            name = "Cool and Quiet",
            description = "Keeps the device cold and efficient. Throttles early. Good for charging while gaming.",
            config = CurveConfig(targetTempC = 38f, startOffsetC = 6f, aggressiveness = 70, floorPercent = 30),
        ),
        CurvePreset(
            id = "balanced",
            icon = Icons.Rounded.Balance,
            name = "Balanced",
            description = "Middle ground between performance and temperature. Recommended for most games.",
            config = CurveConfig(targetTempC = 42f, startOffsetC = 6f, aggressiveness = 40, floorPercent = 45),
        ),
        CurvePreset(
            id = "performance",
            icon = Icons.Rounded.RocketLaunch,
            name = "Performance",
            description = "Prioritizes high FPS. Throttle only kicks in when genuinely hot.",
            config = CurveConfig(targetTempC = 45f, startOffsetC = 5f, aggressiveness = 25, floorPercent = 60),
        ),
        CurvePreset(
            id = "unlimited",
            icon = Icons.Rounded.LocalFireDepartment,
            name = "Unlimited",
            description = "Minimal throttling, emergency brake only. Requires external cooling.",
            config = CurveConfig(targetTempC = 48f, startOffsetC = 4f, aggressiveness = 15, floorPercent = 78),
        ),
    )

    private fun snapToTable(value: Long, table: List<Long>): Long {
        if (table.isEmpty()) return value
        return table.minByOrNull { abs(it - value) } ?: value
    }

    private fun indexAtOrBelow(value: Long, table: List<Long>): Int {
        if (table.isEmpty()) return 0
        var idx = 0
        for (i in table.indices) if (table[i] <= value) idx = i
        return idx
    }

    /**
     * Generate a throttle curve for one CPU cluster.
     * Frequencies come from [PerfTuner.CpuCluster.availableKhz] (real kernel table).
     */
    fun suggestCpuCurve(cluster: PerfTuner.CpuCluster, cfg: CurveConfig): List<ThrottleLevel> {
        val table = cluster.availableKhz.sorted()
        if (table.size < 2) return emptyList()

        val hwMax = cluster.hwMaxKhz
        val hwMin = cluster.hwMinKhz
        val floorKhz = snapToTable(
            hwMin + ((hwMax - hwMin) * cfg.floorPercent / 100f).toLong(), table
        )

        val numLevels = (2 + cfg.aggressiveness / 20).coerceIn(2, 6)
        val startTemp = cfg.targetTempC - cfg.startOffsetC
        val tempStep = cfg.startOffsetC / numLevels

        val raw = mutableListOf<ThrottleLevel>()
        for (i in 0 until numLevels) {
            val trigC = startTemp + tempStep * (i + 1)
            val frac = if (numLevels == 1) 1f else i.toFloat() / (numLevels - 1)
            val freqKhz = (hwMax - (hwMax - floorKhz) * frac).toLong()
            raw.add(
                ThrottleLevel(
                    trigMc = trigC.cToMc(),
                    clrMc = (trigC - 2.0f).cToMc(),
                    freq = snapToTable(freqKhz, table),
                )
            )
        }
        return enforceDescendingFreq(raw, table)
    }

    /**
     * Generate a GPU throttle curve (max [CustomProfileContract.GPU_MAX_LEVELS]).
     * Frequency is stored in MHz per the daemon contract, sourced from
     * [PerfTuner.GpuInfo.availableHz].
     */
    fun suggestGpuCurve(gpu: PerfTuner.GpuInfo, cfg: CurveConfig): List<ThrottleLevel> {
        val tableHz = gpu.availableHz.sorted()
        if (tableHz.size < 2) return emptyList()

        val hwMaxHz = gpu.hwMaxHz
        val hwMinHz = gpu.hwMinHz
        val floorHz = snapToTable(
            hwMinHz + ((hwMaxHz - hwMinHz) * cfg.floorPercent / 100f).toLong(), tableHz
        )

        val numLevels = (2 + cfg.aggressiveness / 30).coerceIn(2, CustomProfileContract.GPU_MAX_LEVELS)
        val startTemp = cfg.targetTempC - cfg.startOffsetC + 1f
        val tempStep = cfg.startOffsetC / numLevels

        val raw = mutableListOf<ThrottleLevel>()
        for (i in 0 until numLevels) {
            val trigC = startTemp + tempStep * (i + 1)
            val frac = if (numLevels == 1) 1f else i.toFloat() / (numLevels - 1)
            val freqHz = (hwMaxHz - (hwMaxHz - floorHz) * frac).toLong()
            val freqMhz = snapToTable(freqHz, tableHz) / 1_000_000L
            raw.add(
                ThrottleLevel(
                    trigMc = trigC.cToMc(),
                    clrMc = (trigC - 2.0f).cToMc(),
                    freq = freqMhz,
                )
            )
        }
        val tableMhz = tableHz.map { it / 1_000_000L }
        return enforceDescendingFreq(raw, tableMhz)
    }

    /**
     * Force strictly descending frequencies across levels. Snapping can cause
     * two adjacent levels to land on the same OPP; this pushes the later level
     * down to the next table step.
     */
    private fun enforceDescendingFreq(
        levels: List<ThrottleLevel>,
        table: List<Long>,
    ): List<ThrottleLevel> {
        if (levels.size < 2 || table.isEmpty()) return levels
        val sortedTable = table.sorted()
        val out = mutableListOf(levels[0])
        for (i in 1 until levels.size) {
            val prev = out.last().freq
            var freq = levels[i].freq
            if (freq >= prev) {
                val prevIdx = indexAtOrBelow(prev, sortedTable)
                freq = if (prevIdx > 0) sortedTable[prevIdx - 1] else sortedTable.first()
            }
            out.add(levels[i].copy(freq = freq))
        }
        return out
    }

    /**
     * Generate a full profile (all 4 CPU clusters plus GPU) from one config.
     * Called by the preset buttons and the wizard Generate action.
     */
    fun generateFullProfile(
        perfTuner: PerfTuner,
        cfg: CurveConfig,
        name: String,
        id: String = "custom_" + System.currentTimeMillis(),
    ): CustomThermalProfile {
        val profile = CustomThermalProfile(id = id, name = name)
        perfTuner.cpuClusters.forEach { cluster ->
            val idx = cluster.index
            if (idx in profile.clusters.indices) {
                profile.clusters[idx].clear()
                profile.clusters[idx].addAll(suggestCpuCurve(cluster, cfg))
            }
        }
        profile.gpuLevels.clear()
        profile.gpuLevels.addAll(suggestGpuCurve(perfTuner.gpuInfo, cfg))
        return profile
    }

    /**
     * Human-readable summary of what a cluster curve does, e.g.:
     * "Full 2.96 GHz until 42.0 C, drops to 1.79 GHz at 46.0 C"
     */
    fun describeCluster(cluster: PerfTuner.CpuCluster, levels: List<ThrottleLevel>): String {
        if (levels.isEmpty()) return "Unrestricted (full ${formatKhz(cluster.hwMaxKhz)})"
        val first = levels.first()
        val last = levels.last()
        val base = "${formatKhz(cluster.hwMaxKhz)} until ${first.trigMc.mcToTempString()}, " +
                "caps at ${formatKhz(first.freq)}"
        return if (levels.size > 1) {
            "$base, down to ${formatKhz(last.freq)} at ${last.trigMc.mcToTempString()}"
        } else base
    }

    /** Highest trigger temperature used anywhere in the profile. */
    fun peakTriggerC(profile: CustomThermalProfile): Float {
        val all = profile.clusters.flatten().map { it.trigMc } +
                profile.gpuLevels.map { it.trigMc }
        return (all.maxOrNull() ?: 0L).mcToC()
    }
}
