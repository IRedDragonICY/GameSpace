/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.charging

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.ui.graphics.vector.ImageVector

/** User intent for the wizard. */
data class ChargingConfig(
    /** Skin temp the PID tries to hold (°C). */
    val targetTempC: Float = 38f,
    /** Max charge current (Watt). */
    val maxWatt: Float = 90f,
    /** 0 = relaxed (few tiers, high current), 100 = aggressive (cool battery). */
    val aggressiveness: Int = 40,
)

sealed class ChargingSource {
    abstract val id: String
    abstract val name: String
    abstract val description: String

    data class Preset(
        override val id: String,
        val icon: ImageVector,
        override val name: String,
        override val description: String,
        val config: ChargingConfig,
    ) : ChargingSource()

    /** Vendor SIC table reverse-engineered from sconfig_*.txt (currents in µA). */
    data class Vendor(
        override val id: String,
        override val name: String,
        override val description: String,
        val tiers: List<ChargingTier>,
    ) : ChargingSource()
}

object ChargingSuggestionEngine {
    // ---- One-click presets ----
    val PRESETS: List<ChargingSource.Preset> = listOf(
        ChargingSource.Preset(
            "fast", Icons.Rounded.Speed, "Fast Charge",
            "Maximum speed. Loose tiers, high current. Device will warm up.",
            ChargingConfig(targetTempC = 43f, maxWatt = 90f, aggressiveness = 15),
        ),
        ChargingSource.Preset(
            "balanced", Icons.Rounded.BatteryChargingFull, "Balanced",
            "Vendor-like. Good speed with sensible thermal protection.",
            ChargingConfig(targetTempC = 39f, maxWatt = 60f, aggressiveness = 45),
        ),
        ChargingSource.Preset(
            "cool", Icons.Rounded.Thermostat, "Cool & Battery-Friendly",
            "Prioritizes battery longevity. Lower current, tighter tiers.",
            ChargingConfig(targetTempC = 36f, maxWatt = 30f, aggressiveness = 75),
        ),
        ChargingSource.Preset(
            "night", Icons.Rounded.Bedtime, "Night Trickle",
            "Ultra-slow overnight charging. Minimal heat, max battery health.",
            ChargingConfig(targetTempC = 33f, maxWatt = 10f, aggressiveness = 90),
        ),
    )

    // ---- Vendor templates (RE'd from thermal-profiles.json / sconfig_*.txt) ----
    val VENDOR_TEMPLATES: List<ChargingSource.Vendor> = listOf(
        ChargingSource.Vendor(
            "vendor_global", "Vendor Default (GLOBAL)", "sconfig=0 daily profile",
            listOf(
                tier(15000, 14000, 0,     15600, 15600, 0,   0,  0),
                tier(31800, 25000, 0,     12400, 12400, 0,   0,  0),
                tier(33500, 30800, 32000, 9000,  9000,  0,   0,  0),
                tier(34600, 33000, 36000, 9000,  5000,  200, 5,  5),
                tier(37000, 35500, 38000, 8000,  5000,  200, 5,  5),
                tier(39000, 37400, 40500, 5000,  2500,  200, 50, 50),
                tier(44500, 44000, 44500, 2000,  2000,  350, 3,  25),
                tier(45000, 44500, 45000, 1000,  1000,  350, 3,  25),
                tier(46000, 45500, 46000, 600,   600,   350, 3,  25),
            ),
        ),
        ChargingSource.Vendor(
            "vendor_mgame", "Vendor Gaming (MGAME)", "sconfig=19 balanced gaming",
            listOf(
                tier(15000, 14000, 0,     15600, 15600, 0,   0,  0),
                tier(34500, 33500, 0,     10000, 10000, 0,   0,  0),
                tier(37500, 36500, 38000, 6000,  6000,  350, 25, 25),
                tier(38500, 38000, 39000, 3500,  2200,  350, 25, 15),
                tier(41000, 40500, 42000, 3000,  2200,  350, 25, 25),
                tier(42500, 41500, 43500, 3000,  2200,  350, 25, 25),
                tier(44000, 43500, 45000, 1500,  1500,  350, 25, 25),
                tier(45000, 44000, 45500, 500,   500,   350, 25, 25),
            ),
        ),
        ChargingSource.Vendor(
            "vendor_video", "Vendor Video", "sconfig=11 video playback",
            listOf(
                tier(15000, 14000, 0,     15600, 15600, 0,      0,     0),
                tier(33000, 32000, 32000, 9000,  9000,  0,      0,     0),
                tier(35000, 34500, 35700, 6000,  2500,  3500,   40,    60),
                tier(35800, 35200, 36700, 2500,  2500,  3000,   100,   400),
                tier(37000, 36500, 38000, 2500,  2500,  3000,   100,   400),
                tier(42000, 41000, 42000, 2500,  2500,  3500,   25,    25),
                tier(42500, 42000, 43000, 2000,  2000,  3500,   25,    25),
                tier(44000, 43000, 44000, 1350,  1350,  3500,   25,    250),
                tier(45000, 44500, 45000, 600,   600,   3500,   25,    250),
            ),
        ),
    )

    private fun tier(trig: Long, clr: Long, target: Long, maxMa: Long, minMa: Long,
                     ks: Long, ki: Long, kc: Long) = ChargingTier(
        trigMc = trig, clrMc = clr, targetMc = target,
        maxUa = maxMa * 1000, minUa = minMa * 1000,
        ks = ks, ki = ki, kc = kc,
    )

    fun suggestTiers(cfg: ChargingConfig): List<ChargingTier> {
        val maxUa = ChargingProfiles.wattToUa(cfg.maxWatt)
            .coerceIn(ChargingProfiles.FCC_HARD_MIN_UA, ChargingProfiles.FCC_HARD_MAX_UA)
        val floorUa = (maxUa * 0.10f).toLong()
            .coerceAtLeast(ChargingProfiles.FCC_HARD_MIN_UA)

        val numTiers = (3 + cfg.aggressiveness / 20).coerceIn(3, 8)
        val startOffsetC = 8f - (cfg.aggressiveness / 100f) * 5f
        val startTempC = cfg.targetTempC - startOffsetC
        val tempSpan = (cfg.targetTempC + 6f) - startTempC
        val stepC = tempSpan / numTiers

        val ks = 200L + (cfg.aggressiveness / 100f * 150).toLong()
        val ki = 5L + (cfg.aggressiveness / 100f * 45).toLong()
        val kc = ki

        val tiers = mutableListOf<ChargingTier>()
        for (i in 0 until numTiers) {
            val trigC = startTempC + stepC * (i + 1)
            val frac = if (numTiers == 1) 1f else i.toFloat() / (numTiers - 1)
            val curUa = (maxUa - (maxUa - floorUa) * frac).toLong()
            val nextUa = (maxUa - (maxUa - floorUa) *
                ((i + 1).toFloat() / (numTiers - 1).coerceAtLeast(1))).toLong()
            tiers.add(
                ChargingTier(
                    trigMc = ChargingProfiles.cToMc(trigC),
                    clrMc = ChargingProfiles.cToMc(trigC - 1.5f),
                    targetMc = ChargingProfiles.cToMc(trigC - 0.5f),
                    maxUa = curUa.coerceAtLeast(floorUa),
                    minUa = nextUa.coerceIn(ChargingProfiles.FCC_HARD_MIN_UA, curUa),
                    ks = ks, ki = ki, kc = kc,
                )
            )
        }
        return tiers
    }

    fun generateProfile(cfg: ChargingConfig, name: String,
                        id: String = CustomChargingProfile.newId()) =
        CustomChargingProfile(
            id = id, name = name,
            tiers = suggestTiers(cfg),
            emergencySuspendMc = ChargingProfiles.cToMc(cfg.targetTempC + 10f),
            emergencyResumeMc = ChargingProfiles.cToMc(cfg.targetTempC + 7f),
        )
}
