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

/**
 * Thermal profile catalog — single source of truth for the whole app.
 * (Previously duplicated between MiThermalService, GamePanelCard and
 * PerAppSettingsViewModel; the framework copy is gone.)
 *
 * All sconfig values were reverse-engineered from the vendor
 * thermal-map.conf; the SS names match ss.rs in mi_thermal_engine.
 */
object ThermalProfiles {

    /** Settings.System keys — shared contract with SystemUI QS tiles. */
    const val KEY_THERMAL_PROFILE = "mithermal_thermal_profile"
    const val KEY_TEMP_LIMIT = "mithermal_temp_limit"
    const val KEY_CHARGE_MAX_WATT = "mithermal_charge_max_watt"
    const val KEY_CHARGE_MIN_WATT = "mithermal_charge_min_watt"
    const val KEY_APP_PROFILES = "mithermal_app_profiles"
    const val KEY_CUSTOM_PROFILES = "mithermal_custom_profiles"
    const val KEY_BYPASS_CHARGING = "mithermal_bypass_charging"
    const val KEY_HBM_ENABLED = "hbm_force_enabled"
    const val KEY_HBM_TIMEOUT = "hbm_timeout_minutes"
    const val KEY_DC_DIMMING = "dc_dimming_enabled"

    /** Properties consumed by the mi_thermal_engine Rust daemon (1 Hz loop). */
    const val PROP_TEMP_LIMIT = "persist.sys.mithermal.temp_limit"
    const val PROP_MAX_FCC = "persist.sys.mithermal.max_fcc"
    const val PROP_MIN_FCC = "persist.sys.mithermal.min_fcc"
    const val PROP_SS_PROFILE = "persist.sys.mithermal.ss_profile"
    const val PROP_CUSTOM_SEQ = "persist.sys.mithermal.custom.seq"
    const val PROP_CUSTOM_COUNT = "persist.sys.mithermal.custom.n"
    const val PROP_CUSTOM_CHUNK_PREFIX = "persist.sys.mithermal.custom."

    /** Property values are capped at 92 bytes — chunk payloads below that. */
    const val PROP_CHUNK_SIZE = 88
    const val PROP_CHUNK_MAX = 64

    /** Profile indices >= this reference user-created custom profiles. */
    const val CUSTOM_PROFILE_BASE = 1000

    const val PROFILE_AUTO = 0
    const val PROFILE_BATTERY_SAVER = 16

    /** index → sconfig; -1 = Auto (don't override), -2 = Battery Saver. */
    val PROFILE_SCONFIG = intArrayOf(
        -1, //  0: Auto
        0, //  1: Default (GLOBAL)
        6, //  2: Performance (NOLIMITS)
        19, //  3: Balanced Gaming (MGAME, throttle @36°C)
        18, //  4: Heavy Gaming (TGAME, throttle @43.5°C)
        20, //  5: Genshin Impact (YUANSHEN)
        25, //  6: Honkai Star Rail (XINGTIE)
        26, //  7: High FPS (HIGHFPS)
        15, //  8: Camera
        16, //  9: 4K Recording
        11, // 10: Video
        14, // 11: Video Chat
        10, // 12: Navigation
        5, // 13: Phone Call
        9, // 14: AR/VR
        1, // 15: Data Transfer (HUANJI)
        -2, // 16: Battery Saver (powersave_mode=1)
    )

    /** index → SS profile name for the Rust daemon (must match ss.rs). */
    val PROFILE_SS_NAMES = arrayOf(
        "default", "default", "performance", "gaming", "heavy_gaming",
        "genshin", "hsr", "high_fps", "camera", "recording_4k", "video",
        "video_chat", "navigation", "phone_call", "arvr", "data_transfer",
        "battery_saver",
    )

    /** Human-readable names (logging + UI fallback). */
    val PROFILE_NAMES = arrayOf(
        "Auto", "Default", "Performance", "Balanced Gaming", "Heavy Gaming",
        "Genshin Impact", "Honkai Star Rail", "High FPS", "Camera",
        "4K Recording", "Video", "Video Chat", "Navigation", "Phone Call",
        "AR/VR", "Data Transfer", "Battery Saver",
    )

    /** Profiles that benefit from migt/metis frame boost + WALT sched boost. */
    fun isGamingSconfig(sconfig: Int): Boolean = when (sconfig) {
        6, 18, 19, 20, 25, 26 -> true
        else -> false
    }

    fun isValidIndex(index: Int): Boolean =
        (index >= 0 && index < PROFILE_SCONFIG.size) || index >= CUSTOM_PROFILE_BASE

    // Watt → µA at ~5.8 V average MCA voltage (1 W ≈ 172,000 µA)
    const val UA_PER_WATT = 172_000
    const val MIN_WATT = 5
    const val MAX_WATT = 90
    const val DEFAULT_TEMP_LIMIT_DECI_C = 380 // 38.0°C
}
