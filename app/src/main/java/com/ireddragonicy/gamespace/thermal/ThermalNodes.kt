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
 * Kernel node map for POCO F7 (onyx) — every path exposed by the GKI .ko
 * modules that GameSpace controls directly (reverse-engineered from
 * mi_thermald / migt.ko / metis.ko / kgsl).
 *
 * Ownership after the mithermald decoupling:
 *   - GameSpace (this app, system_app) → policy decisions, all writes below
 *   - mi_thermal_engine (Rust, vendor) → 1 Hz SS/SIC control loops, reads
 *     persist.sys.mithermal.* properties written by [ThermalController]
 */
object ThermalNodes {

    // ── mi_thermald thermal_message interface (thermal .ko) ────────────────
    const val SCONFIG = "/sys/class/thermal/thermal_message/sconfig"
    const val THERMAL_BOOST = "/sys/class/thermal/thermal_message/boost"
    const val CPU_NOLIMIT_TEMP = "/sys/class/thermal/thermal_message/cpu_nolimit_temp"
    const val SCREEN_STATE = "/sys/class/thermal/thermal_message/screen_state"

    // ── mi_thermald battery saver interface ────────────────────────────────
    const val POWERSAVE_MODE = "/sys/class/thermal/power_save/powersave_mode"
    const val POWER_LEVEL = "/sys/class/thermal/power_save/power_level"

    // ── migt.ko — Xiaomi frame-boost scheduler module ───────────────────────
    const val MIGT_FRAME_BOOST = "/sys/module/migt/parameters/frame_boost_enable"
    const val MIGT_FLT_TARGET_FPS = "/sys/module/migt/parameters/flt_target_fps"
    const val MIGT_FLT_PREBOOST = "/sys/module/migt/parameters/flt_preboost_enable"
    const val MIGT_GLK_DISABLE = "/sys/module/migt/parameters/glk_disable"

    // ── metis.ko — Xiaomi VIP-task scheduler module ─────────────────────────
    const val METIS_FBOOST = "/sys/module/metis/parameters/mi_fboost_enable"

    // ── WALT scheduler (walt.ko, /proc interface) ───────────────────────────
    const val SCHED_BOOST = "/proc/sys/walt/sched_boost"

    // ── MCA charging (xm_power .ko) ─────────────────────────────────────────
    const val WIRED_CHG_CURR =
        "/sys/class/xm_power/charger/charger_thermal/wired_chg_curr"
    const val WIRED_CHG_CURR2 =
        "/sys/class/xm_power/charger/charger_thermal/wired_chg_curr2"
    const val SMART_NIGHT = "/sys/class/xm_power/charger/smart_charge/smart_night"
    const val INPUT_SUSPEND =
        "/sys/devices/platform/soc/soc:mca_charge_interface/input_suspend"

    // ── Display (mi_display .ko) ────────────────────────────────────────────
    const val DISP_PARAM = "/sys/class/mi_display/disp-DSI-0/disp_param"
    const val DISP_PARAM_HBM_ON = "01 1"
    const val DISP_PARAM_HBM_OFF = "01 0"
    const val DISP_PARAM_DC_ON = "08 1"
    const val DISP_PARAM_DC_OFF = "08 0"

    // ── CPU cpufreq (per-cluster policies: 2×A725 / 3×A725 / 2×A725 / X4) ──
    val CPU_POLICY_DIRS = arrayOf(
        "/sys/devices/system/cpu/cpufreq/policy0",
        "/sys/devices/system/cpu/cpufreq/policy2",
        "/sys/devices/system/cpu/cpufreq/policy5",
        "/sys/devices/system/cpu/cpufreq/policy7",
    )
    val CPU_CLUSTER_NAMES = arrayOf("Silver", "Gold", "Gold+", "Prime")

    fun cpuScalingMax(policyDir: String) = "$policyDir/scaling_max_freq"
    fun cpuScalingMin(policyDir: String) = "$policyDir/scaling_min_freq"
    fun cpuGovernor(policyDir: String) = "$policyDir/scaling_governor"
    fun cpuAvailableFreqs(policyDir: String) = "$policyDir/scaling_available_frequencies"
    fun cpuAvailableGovernors(policyDir: String) = "$policyDir/scaling_available_governors"
    fun cpuHwMax(policyDir: String) = "$policyDir/cpuinfo_max_freq"
    fun cpuHwMin(policyDir: String) = "$policyDir/cpuinfo_min_freq"
    fun cpuCurFreq(policyDir: String) = "$policyDir/scaling_cur_freq"

    // ── Adreno GPU (kgsl-3d0) ───────────────────────────────────────────────
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    const val GPU_MIN_CLOCK_MHZ = "$KGSL/min_clock_mhz"
    const val GPU_MAX_CLOCK_MHZ = "$KGSL/max_clock_mhz"
    const val GPU_AVAILABLE_FREQS = "$KGSL/gpu_available_frequencies" // Hz
    const val GPU_BUSY_PERCENT = "$KGSL/gpu_busy_percentage"
    const val GPU_CUR_FREQ = "$KGSL/devfreq/cur_freq" // Hz
    const val GPU_DEVFREQ_MIN = "$KGSL/devfreq/min_freq" // Hz
    const val GPU_DEVFREQ_MAX = "$KGSL/devfreq/max_freq" // Hz
    const val GPU_GOVERNOR = "$KGSL/devfreq/governor"
    const val GPU_AVAILABLE_GOVERNORS = "$KGSL/devfreq/available_governors"

    // ── Telemetry (read-only) ───────────────────────────────────────────────
    const val CPU_TEMP = "/sys/class/thermal/thermal_zone9/temp" // cpu-big m°C
    const val GPU_TEMP = "/sys/class/thermal/thermal_zone24/temp" // gpu m°C
}
