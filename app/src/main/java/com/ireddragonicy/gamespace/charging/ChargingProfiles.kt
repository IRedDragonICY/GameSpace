/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.charging

/**
 * Charging profile catalog — single source of truth, parallel to ThermalProfiles.
 *
 * All currents are stored/transmitted in µA (daemon-native). UI converts to
 * Watt via [UA_PER_WATT] (≈5.8V effective MCA conversion voltage). Gains are
 * stored in daemon-internal units (vendor_raw / 10000) — the same scale as
 * SIC_TIERS_GLOBAL in sic.rs (ks:200 == vendor 2000000).
 *
 * This object owns the ONLY Watt<->µA conversion in the app. Do not reintroduce
 * a second one; see [UA_PER_WATT] for why a naive P=V*I on battery voltage is
 * the wrong model for this charge topology.
 */
object ChargingProfiles {
    // ---- Settings.System keys ----
    /** JSON array of all user profiles: [{id,name,suspend,resume,tiers:[...]}] */
    const val KEY_CUSTOM_PROFILES = "mithermal_charging_profiles"
    /** Global default charging profile id ("" = vendor GLOBAL). */
    const val KEY_GLOBAL_PROFILE = "mithermal_global_charging_profile"
    /** Per-app override: {"com.pkg":"profile_id", ...} */
    const val KEY_APP_PROFILES = "mithermal_charging_app_profiles"

    // ---- Daemon IPC props (must match sic.rs) ----
    const val PROP_MODE   = "persist.sys.mithermal.charging_profile" // "global"|"custom"
    const val PROP_SEQ    = "persist.sys.mithermal.charging.seq"
    const val PROP_COUNT  = "persist.sys.mithermal.charging.n"
    const val PROP_CHUNK_PREFIX = "persist.sys.mithermal.charging."
    const val PROP_CHUNK_SIZE = 88
    const val PROP_CHUNK_MAX = 64

    const val MAX_TIERS = 12


    // ---- Unit conversion ----
    const val FCC_HARD_MIN_UA = 100_000L      // 100 mA
    const val FCC_HARD_MAX_UA = 20_000_000L   // 20 A

    /** Charger rating in Watt, as printed on the box. */
    const val MIN_WATT = 1f
    const val MAX_WATT = 90f

    /**
     * Watt -> µA, calibrated against the vendor SIC table rather than derived
     * from battery voltage.
     *
     * `wired_chg_curr` is a *battery-side* current, but the Watt figure users
     * think in ("90W charging") is *charger-side* input power. The MCA charge
     * pump sits between them, so P = V_batt * I_batt does not hold: at 4.0V it
     * would claim 90W == 22.5A, which the hardware never delivers.
     *
     * The vendor GLOBAL table pins the real ratio — its unthrottled tier is
     * 15_600_000 µA and corresponds to the 90W rating, giving an effective
     * 5.77V conversion, i.e. 172_000 µA per Watt. Cross-checks: 12.4A -> 72W,
     * 2.0A -> 11.6W, 0.6A -> 3.5W, all sensible rungs of the vendor ladder.
     */
    const val UA_PER_WATT = 172_000f

    fun wattToUa(w: Float): Long =
        (w.toDouble() * UA_PER_WATT).toLong().coerceIn(FCC_HARD_MIN_UA, FCC_HARD_MAX_UA)

    fun uaToWatt(ua: Long): Float = ua / UA_PER_WATT

    fun mcToC(mc: Long): Float = mc / 1000f
    fun cToMc(c: Float): Long = (c * 1000f).toLong()
}
