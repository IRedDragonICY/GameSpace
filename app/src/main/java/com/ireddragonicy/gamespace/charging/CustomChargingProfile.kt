/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.charging

import com.ireddragonicy.gamespace.profile.DaemonProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * One SIC throttle tier. Mirrors `SicTier` in sic.rs EXACTLY.
 *  - temps in milli-°C
 *  - currents in µA
 *  - gains in daemon-internal units (vendor_raw / 10000)
 *
 * Immutable, like [com.ireddragonicy.gamespace.thermal.custom.ThrottleLevel]:
 * a tier handed to the repository can never be edited afterwards by whoever
 * still holds the editor's copy.
 *
 * Manual JSON (not Gson reflection) → R8-safe.
 */
data class ChargingTier(
    val trigMc: Long = 35_000L,    // engage threshold
    val clrMc: Long = 33_000L,     // release threshold (hysteresis, < trig)
    val targetMc: Long = 36_000L,  // PID setpoint (0 = pure cap, no PID)
    val maxUa: Long = 9_000_000L,  // FCC ceiling
    val minUa: Long = 5_000_000L,  // FCC floor
    val ks: Long = 200L,           // proportional gain
    val ki: Long = 5L,             // integral gain
    val kc: Long = 5L,             // derivative/damping gain
) {
    /** Pure cap = no PID, just clamp to max. */
    val isPureCap: Boolean get() = targetMc == 0L || maxUa == minUa

    fun toJson(): JSONObject = JSONObject().apply {
        put("trig", trigMc); put("clr", clrMc); put("target", targetMc)
        put("max", maxUa); put("min", minUa)
        put("ks", ks); put("ki", ki); put("kc", kc)
    }

    companion object {
        fun fromJson(o: JSONObject): ChargingTier = ChargingTier(
            trigMc   = o.optLong("trig", 35_000L),
            clrMc    = o.optLong("clr", 33_000L),
            targetMc = o.optLong("target", 0L),
            maxUa    = o.optLong("max", 9_000_000L),
            minUa    = o.optLong("min", 5_000_000L),
            ks       = o.optLong("ks", 0L),
            ki       = o.optLong("ki", 0L),
            kc       = o.optLong("kc", 0L),
        )
    }
}

/** A complete user charging profile. */
data class CustomChargingProfile(
    override val id: String = newId(),
    override val name: String = "",
    val tiers: List<ChargingTier> = emptyList(),
    val emergencySuspendMc: Long = 48_000L,
    val emergencyResumeMc: Long = 45_000L,
) : DaemonProfile {
    val isEmpty: Boolean get() = tiers.isEmpty()

    /** Highest current any tier allows, in Watt — the headline figure for lists. */
    val peakWatt: Float
        get() = tiers.maxOfOrNull { it.maxUa }?.let { ChargingProfiles.uaToWatt(it) } ?: 0f

    /** Full persistence form (includes id/name). */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("suspend", emergencySuspendMc)
        put("resume", emergencyResumeMc)
        put("tiers", tiersJson())
    }

    /** Daemon form (no id/name — only what sic.rs consumes). */
    fun toDaemonJson(): JSONObject = JSONObject().apply {
        put("suspend", emergencySuspendMc)
        put("resume", emergencyResumeMc)
        put("tiers", tiersJson())
    }

    private fun tiersJson(): JSONArray =
        JSONArray().apply { tiers.forEach { put(it.toJson()) } }

    companion object {
        fun newId(): String = "charging_" + System.currentTimeMillis()

        fun fromJson(o: JSONObject): CustomChargingProfile = CustomChargingProfile(
            id = o.optString("id", newId()),
            name = o.optString("name", ""),
            emergencySuspendMc = o.optLong("suspend", 48_000L),
            emergencyResumeMc = o.optLong("resume", 45_000L),
            tiers = o.optJSONArray("tiers")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let(ChargingTier::fromJson)
                }
            }.orEmpty(),
        )
    }
}
