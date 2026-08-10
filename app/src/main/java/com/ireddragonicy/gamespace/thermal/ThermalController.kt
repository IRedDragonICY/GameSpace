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

import android.app.ActivityManager
import android.app.IProcessObserver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mithermald control plane — lives entirely inside GameSpace.
 *
 * This replaces the former framework MiThermalService (system_server). The
 * process is persistent (`android:persistent="true"`, android.uid.system), so
 * this controller has the same lifetime guarantees system_server offered,
 * without polluting the framework.
 *
 * Split of responsibilities:
 *   GameSpace (here)      — policy: profile selection, per-app switching,
 *                           battery-saver integration, charging limits, HBM,
 *                           DC dimming; writes sysfs + persist.sys.mithermal.*
 *   mi_thermal_engine     — mechanism: 1 Hz SS CPU throttling + SIC charging
 *   (Rust vendor daemon)    PID, driven by the properties set here
 *   kernel thermal_core   — hardware safety net (emergency throttling)
 *
 * All work is confined to a dedicated handler thread; sysfs writes go through
 * the fd-cached [KernelNodeIO] (zero open/close on the hot path).
 */
import com.ireddragonicy.gamespace.charging.ChargingProfileRepository
import com.ireddragonicy.gamespace.charging.ChargingProfiles
import com.ireddragonicy.gamespace.thermal.custom.CustomProfileRepository

@Singleton
class ThermalController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chargingRepo: ChargingProfileRepository,
    private val customRepo: CustomProfileRepository,
) {
    private val thread = HandlerThread("GameSpaceThermal").apply { start() }
    private val handler = Handler(thread.looper)
    private val resolver = context.contentResolver

    // ── Cached settings ─────────────────────────────────────────────────────
    private var thermalProfile = 0
    private var tempLimit = ThermalProfiles.DEFAULT_TEMP_LIMIT_DECI_C
    private var tempOffset = 0 // Feature B: aggressiveness headroom, signed deci-°C
    private var chargeMaxWatt = ThermalProfiles.MAX_WATT
    private var chargeMinWatt = ThermalProfiles.MIN_WATT
    private val appProfiles = HashMap<String, Int>()

    // ── Applied state ───────────────────────────────────────────────────────
    private var foregroundPackage = ""
    private var activeProfileIndex = -1
    private var appliedSconfig = -1
    private var originalSconfig = -1
    private var gameBoostActive = false

    // ── Battery saver integration ───────────────────────────────────────────
    private var batterySaverActive = false
    private var savedProfileBeforeBatterySaver = -1
    private var savedPeakRefreshRate = 120f

    // ── HBM / DC dimming state ──────────────────────────────────────────────
    private var hbmActive = false
    private var savedBrightness = 128
    private var savedAutoBrightness = 0
    private var hbmTimeoutMinutes = 5
    private var dcDimmingActive = false

    private val hbmTimeoutRunnable = Runnable {
        if (hbmActive) {
            Log.i(TAG, "HBM auto-timeout after $hbmTimeoutMinutes minutes")
            Settings.System.putIntForUser(
                resolver, ThermalProfiles.KEY_HBM_ENABLED, 0, UserHandle.USER_CURRENT
            ) // observer applies the change
        }
    }

    @Volatile
    private var started = false

    /** Idempotent — called from the Application once the process is up. */
    fun start() {
        if (started) return
        started = true
        handler.post { init() }
    }

    private fun init() {
        Log.i(TAG, "ThermalController starting (mithermald control plane)")

        originalSconfig = KernelNodeIO.readInt(ThermalNodes.SCONFIG) ?: 0

        // Clear stale powersave_mode if Battery Saver is off after a crash/reboot
        val lowPower = Settings.Global.getInt(resolver, Settings.Global.LOW_POWER_MODE, 0) == 1
        if (!lowPower && KernelNodeIO.read(ThermalNodes.POWERSAVE_MODE) == "1") {
            KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, "0")
            Log.w(TAG, "Cleared stale powersave_mode=1 (Battery Saver is OFF)")
        }

        loadSettings()
        loadAppProfiles()
        registerForegroundObserver()
        registerObservers()
        registerScreenStateReceiver()
        registerChargerReceiver()

        // Prime the Rust daemon
        SystemProperties.set(ThermalProfiles.PROP_TEMP_LIMIT, tempLimit.toString())
        SystemProperties.set(
            ThermalProfiles.PROP_MAX_FCC,
            ChargingProfiles.wattToUa(chargeMaxWatt.toFloat()).toString()
        )
        SystemProperties.set(
            ThermalProfiles.PROP_MIN_FCC,
            ChargingProfiles.wattToUa(chargeMinWatt.toFloat()).toString()
        )
        SystemProperties.set(ThermalProfiles.PROP_TEMP_OFFSET, tempOffset.toString())

        applyAll()
    }

    // ── Settings loading ────────────────────────────────────────────────────

    private fun loadSettings() {
        thermalProfile = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_THERMAL_PROFILE, 0, UserHandle.USER_CURRENT
        )
        tempLimit = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_TEMP_LIMIT,
            ThermalProfiles.DEFAULT_TEMP_LIMIT_DECI_C, UserHandle.USER_CURRENT
        )
        chargeMaxWatt = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_CHARGE_MAX_WATT,
            ThermalProfiles.MAX_WATT, UserHandle.USER_CURRENT
        ).coerceIn(ThermalProfiles.MIN_WATT, ThermalProfiles.MAX_WATT)
        chargeMinWatt = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_CHARGE_MIN_WATT,
            ThermalProfiles.MIN_WATT, UserHandle.USER_CURRENT
        ).coerceIn(ThermalProfiles.MIN_WATT, chargeMaxWatt)
        if (tempLimit !in 250..480) tempLimit = ThermalProfiles.DEFAULT_TEMP_LIMIT_DECI_C
        if (!ThermalProfiles.isValidIndex(thermalProfile)) thermalProfile = 0
        tempOffset = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_TEMP_OFFSET, 0, UserHandle.USER_CURRENT
        ).coerceIn(-150, 150) // ±15.0°C in deci-°C
    }

    private fun loadAppProfiles() {
        appProfiles.clear()
        val json = Settings.System.getStringForUser(
            resolver, ThermalProfiles.KEY_APP_PROFILES, UserHandle.USER_CURRENT
        ) ?: return
        if (json.isEmpty()) return
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { pkg ->
                val idx = obj.getInt(pkg)
                if (ThermalProfiles.isValidIndex(idx)) appProfiles[pkg] = idx
            }
            Log.i(TAG, "Loaded ${appProfiles.size} per-app profiles")
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse app profiles JSON", e)
        }
    }

    // ── Foreground app detection ────────────────────────────────────────────

    private fun registerForegroundObserver() {
        try {
            ActivityManager.getService().registerProcessObserver(
                object : IProcessObserver.Stub() {
                    override fun onProcessStarted(
                        pid: Int, processUid: Int, packageUid: Int,
                        packageName: String?, processName: String?,
                    ) = Unit

                    override fun onForegroundActivitiesChanged(
                        pid: Int, uid: Int, foregroundActivities: Boolean,
                    ) {
                        if (foregroundActivities) handler.post { onForegroundChanged(uid) }
                    }

                    override fun onForegroundServicesChanged(
                        pid: Int, uid: Int, serviceTypes: Int,
                    ) = Unit

                    override fun onProcessDied(pid: Int, uid: Int) = Unit
                }
            )
            Log.i(TAG, "Foreground observer registered for per-app profiles")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register process observer", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun onForegroundChanged(uid: Int) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = am.getRunningTasks(1)
        val topPackage = tasks.firstOrNull()?.topActivity?.packageName ?: return

        val profileIdx = appProfiles[topPackage]

        if (topPackage != foregroundPackage) {
            foregroundPackage = topPackage
            if (!batterySaverActive) {
                if (profileIdx != null) {
                    applyProfileIndex(profileIdx)
                    Log.i(TAG, "App foreground: $topPackage → profile index $profileIdx")
                } else {
                    applyGlobalProfile()
                    Log.i(TAG, "App foreground: $topPackage → global profile")
                }
            }
        } else {
            // Re-apply if the configuration was changed dynamically
            if (!batterySaverActive && profileIdx != null && activeProfileIndex != profileIdx) {
                applyProfileIndex(profileIdx)
            }
        }
    }

    // ── Apply logic ─────────────────────────────────────────────────────────

    private fun applyAll() {
        if (!batterySaverActive) applyGlobalProfile()
        applyTempLimit()
        applyTempOffset()
        applyChargingProfile()
        applyBypassCharging()
        applyHbm()
        applyDcDimming()
    }

    private fun applyChargingProfile() {
        val activeId = chargingRepo.resolveActiveProfileId(foregroundPackage)
        val profile = activeId?.let { chargingRepo.getById(it) }
        chargingRepo.applyToDaemon(profile)
        if (profile == null) {
            applyChargeSpeed()
        }
    }

    private fun applyGlobalProfile() = applyProfileIndex(thermalProfile)

    /** Single entry point: applies built-in or custom (>= 1000) profiles. */
    private fun applyProfileIndex(index: Int) {
        if (index >= ThermalProfiles.CUSTOM_PROFILE_BASE) {
            val customIdx = index - ThermalProfiles.CUSTOM_PROFILE_BASE
            if (!applyCustomProfileByIndex(customIdx)) {
                Log.w(TAG, "Custom profile #$customIdx not found, falling back to Default")
                applySconfig(1)
            }
        } else {
            applySconfig(index)
        }
    }

    private fun applySconfig(profileIndex: Int) {
        val sconfig = ThermalProfiles.PROFILE_SCONFIG[profileIndex]

        if (sconfig == -1) { // Auto → restore original
            if (appliedSconfig != -1 && originalSconfig >= 0) {
                KernelNodeIO.write(ThermalNodes.SCONFIG, originalSconfig)
                KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, "0")
                appliedSconfig = -1
                activeProfileIndex = -1
                applyGameBoost(false)
                SystemProperties.set(ThermalProfiles.PROP_SS_PROFILE, "default")
                Log.i(TAG, "Auto → restored sconfig=$originalSconfig ss=default")
            }
            return
        }

        val targetSconfig = if (sconfig == -2) 0 else sconfig
        val powersave = sconfig == -2

        if (targetSconfig != appliedSconfig || activeProfileIndex != profileIndex) {
            KernelNodeIO.write(ThermalNodes.SCONFIG, targetSconfig)
            KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, powersave)
            appliedSconfig = targetSconfig
            activeProfileIndex = profileIndex

            val ssName = ThermalProfiles.PROFILE_SS_NAMES[profileIndex]
            SystemProperties.set(ThermalProfiles.PROP_SS_PROFILE, ssName)

            val boost = ThermalProfiles.isGamingSconfig(sconfig)
            applyGameBoost(boost)

            Log.i(
                TAG, "Profile: ${ThermalProfiles.PROFILE_NAMES[profileIndex]}" +
                    " (sconfig=$targetSconfig ss=$ssName" +
                    (if (powersave) " +powersave" else "") +
                    (if (boost) " +gameBoost" else "") + ")"
            )
        }
    }

    /**
     * Apply a user-created custom profile by array index.
     *
     * The chunked-property handshake, the GPU/monitor/SIC side channels and the
     * payload shape all live in [CustomProfileRepository] — this used to
     * re-implement every one of them against raw JSON, which meant two
     * serializers for one wire format that had to be kept in sync by hand.
     * Here we only own the part the repository cannot: the vendor sconfig
     * scene and the game-boost knobs.
     */
    private fun applyCustomProfileByIndex(arrayIndex: Int): Boolean {
        val profile = customRepo.loadAll().getOrNull(arrayIndex) ?: return false
        customRepo.applyToDaemon(profile)

        // Custom profiles use the Performance vendor-thermal scene (sconfig=6).
        val customSconfig = ThermalProfiles.PROFILE_SCONFIG[ThermalProfiles.PROFILE_PERFORMANCE]
        KernelNodeIO.write(ThermalNodes.SCONFIG, customSconfig)
        KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, "0")
        appliedSconfig = customSconfig
        activeProfileIndex = ThermalProfiles.CUSTOM_PROFILE_BASE + arrayIndex
        applyGameBoost(true)
        Log.i(TAG, "Custom profile '${profile.name}' applied (#$arrayIndex)")
        return true
    }

    /**
     * migt/metis/WALT game boost. Kernel-side effects:
     *  frame_boost_enable=1 — CPU freq boosted from frame load tracking
     *  flt_target_fps=120   — frame-load-tracker target
     *  flt_preboost=1       — boost before frame deadline
     *  glk_disable=0        — GPU game-load tracking on
     *  mi_fboost_enable=1   — metis frame boost
     *  sched_boost=1        — WALT scheduler boost
     */
    private fun applyGameBoost(enable: Boolean) {
        if (enable == gameBoostActive) return
        gameBoostActive = enable

        KernelNodeIO.write(ThermalNodes.MIGT_FRAME_BOOST, enable)
        KernelNodeIO.write(ThermalNodes.MIGT_FLT_TARGET_FPS, if (enable) 120 else 0)
        KernelNodeIO.write(ThermalNodes.MIGT_FLT_PREBOOST, enable)
        KernelNodeIO.write(ThermalNodes.MIGT_GLK_DISABLE, !enable)
        KernelNodeIO.write(ThermalNodes.METIS_FBOOST, enable)
        KernelNodeIO.write(ThermalNodes.SCHED_BOOST, enable)
        KernelNodeIO.write(ThermalNodes.THERMAL_BOOST, enable)
        Log.i(TAG, "Game boost ${if (enable) "ENABLED" else "DISABLED"}")
    }

    private fun applyTempLimit() {
        SystemProperties.set(ThermalProfiles.PROP_TEMP_LIMIT, tempLimit.toString())
    }

    private fun applyTempOffset() {
        SystemProperties.set(ThermalProfiles.PROP_TEMP_OFFSET, tempOffset.toString())
    }

    /**
     * Publish the user's charge-current band to the daemon.
     *
     * Properties only — deliberately no sysfs write. `wired_chg_curr`/`_curr2`
     * are owned by the SIC algo in mi_thermal_engine, which rewrites them every
     * cycle and has readback-based desync detection; a second writer here just
     * loses the race and produces confusing logs.
     */
    private fun applyChargeSpeed() {
        val maxUa = ChargingProfiles.wattToUa(chargeMaxWatt.toFloat())
        val minUa = ChargingProfiles.wattToUa(chargeMinWatt.toFloat())
        SystemProperties.set(ThermalProfiles.PROP_MAX_FCC, maxUa.toString())
        SystemProperties.set(ThermalProfiles.PROP_MIN_FCC, minUa.toString())
        Log.d(TAG, "Charge range: $chargeMinWatt-$chargeMaxWatt W ($minUa-$maxUa µA)")
    }

    /**
     * Real bypass takes the pack out of the charge loop while the adapter keeps
     * running the system. SMART_NIGHT is the fallback, not an equivalent: it
     * only caps soc and votes VBUS down to 5V, which pins the path at 10W where
     * bypass leaves 9V/14.4W up — the difference between a heavy title breaking
     * even and bleeding charge.
     *
     * FORCE rather than AUTO because the toggle is an explicit user action; the
     * kernel's AUTO mode keys off game scenes and would ignore the tap outside
     * one.
     */
    private fun applyBypassCharging() {
        val bypass = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_BYPASS_CHARGING, 0, UserHandle.USER_CURRENT
        ) == 1

        if (KernelNodeIO.exists(ThermalNodes.BYPASS_CHARGE)) {
            KernelNodeIO.write(
                ThermalNodes.BYPASS_CHARGE,
                if (bypass) ThermalNodes.BYPASS_MODE_FORCE
                else ThermalNodes.BYPASS_MODE_OFF
            )
        } else {
            KernelNodeIO.write(ThermalNodes.SMART_NIGHT, bypass)
        }
    }

    /**
     * Bypass state as reported by the kernel: `state to ibatMilliAmps`, or null
     * when the node is absent (prebuilt kernel) or unreadable. Positive current
     * is drain — in DEFICIT the load has outrun what the adapter can carry.
     */
    fun readBypassState(): Pair<Int, Int>? {
        val parts = KernelNodeIO.read(ThermalNodes.BYPASS_STATE)?.split(" ")
            ?: return null
        if (parts.size < 2) return null
        val state = parts[0].toIntOrNull() ?: return null
        val ibat = parts[1].toIntOrNull() ?: return null
        return state to ibat
    }

    // ── HBM (High Brightness Mode) ──────────────────────────────────────────

    private fun applyHbm() {
        val enable = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_HBM_ENABLED, 0, UserHandle.USER_CURRENT
        ) == 1
        hbmTimeoutMinutes = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_HBM_TIMEOUT, 5, UserHandle.USER_CURRENT
        )

        if (enable && !hbmActive) {
            savedBrightness = Settings.System.getIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS, 128, UserHandle.USER_CURRENT
            )
            savedAutoBrightness = Settings.System.getIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, UserHandle.USER_CURRENT
            )
            Settings.System.putIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT
            )
            KernelNodeIO.write(ThermalNodes.DISP_PARAM, ThermalNodes.DISP_PARAM_HBM_ON)
            Settings.System.putIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS, 255, UserHandle.USER_CURRENT
            )
            hbmActive = true

            handler.removeCallbacks(hbmTimeoutRunnable)
            if (hbmTimeoutMinutes > 0) {
                handler.postDelayed(hbmTimeoutRunnable, hbmTimeoutMinutes * 60_000L)
            }
            Log.i(TAG, "HBM ON (saved brightness=$savedBrightness, timeout=${hbmTimeoutMinutes}min)")
        } else if (!enable && hbmActive) {
            KernelNodeIO.write(ThermalNodes.DISP_PARAM, ThermalNodes.DISP_PARAM_HBM_OFF)
            Settings.System.putIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS, savedBrightness,
                UserHandle.USER_CURRENT
            )
            Settings.System.putIntForUser(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, savedAutoBrightness,
                UserHandle.USER_CURRENT
            )
            hbmActive = false
            handler.removeCallbacks(hbmTimeoutRunnable)
            Log.i(TAG, "HBM OFF (restored brightness=$savedBrightness)")
        }
    }

    private fun applyDcDimming() {
        val enable = Settings.System.getIntForUser(
            resolver, ThermalProfiles.KEY_DC_DIMMING, 0, UserHandle.USER_CURRENT
        ) == 1
        if (enable != dcDimmingActive) {
            KernelNodeIO.write(
                ThermalNodes.DISP_PARAM,
                if (enable) ThermalNodes.DISP_PARAM_DC_ON else ThermalNodes.DISP_PARAM_DC_OFF
            )
            dcDimmingActive = enable
        }
    }

    // ── Battery Saver integration ───────────────────────────────────────────

    private fun onBatterySaverChanged() {
        val lowPower = Settings.Global.getInt(resolver, Settings.Global.LOW_POWER_MODE, 0) == 1

        if (lowPower && !batterySaverActive) {
            batterySaverActive = true
            savedProfileBeforeBatterySaver =
                if (activeProfileIndex >= 0) activeProfileIndex else thermalProfile

            KernelNodeIO.write(ThermalNodes.SCONFIG, 0)
            KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, "1")
            appliedSconfig = 0
            activeProfileIndex = ThermalProfiles.PROFILE_BATTERY_SAVER
            applyGameBoost(false)
            SystemProperties.set(ThermalProfiles.PROP_SS_PROFILE, "battery_saver")
            Log.i(TAG, "Battery Saver ON (saved profile=$savedProfileBeforeBatterySaver)")

            runCatching {
                savedPeakRefreshRate = Settings.System.getFloatForUser(
                    resolver, "peak_refresh_rate", 120f, UserHandle.USER_CURRENT
                )
                Settings.System.putFloatForUser(
                    resolver, "peak_refresh_rate", 60f, UserHandle.USER_CURRENT
                )
            }.onFailure { Log.w(TAG, "Failed to force 60Hz", it) }
        } else if (!lowPower && batterySaverActive) {
            batterySaverActive = false
            KernelNodeIO.write(ThermalNodes.POWERSAVE_MODE, "0")

            when {
                savedProfileBeforeBatterySaver == ThermalProfiles.PROFILE_BATTERY_SAVER -> {
                    applySconfig(1) // was already Battery Saver — settle on Default
                }
                savedProfileBeforeBatterySaver >= 0 &&
                    ThermalProfiles.isValidIndex(savedProfileBeforeBatterySaver) -> {
                    // Force re-apply (appliedSconfig may equal the target)
                    activeProfileIndex = -1
                    applyProfileIndex(savedProfileBeforeBatterySaver)
                }
                else -> {
                    KernelNodeIO.write(ThermalNodes.SCONFIG, originalSconfig)
                    appliedSconfig = -1
                    activeProfileIndex = -1
                    SystemProperties.set(ThermalProfiles.PROP_SS_PROFILE, "default")
                }
            }
            Log.i(TAG, "Battery Saver OFF → restored profile $savedProfileBeforeBatterySaver")

            runCatching {
                Settings.System.putFloatForUser(
                    resolver, "peak_refresh_rate", savedPeakRefreshRate, UserHandle.USER_CURRENT
                )
            }.onFailure { Log.w(TAG, "Failed to restore refresh rate", it) }
        }
    }

    /**
     * Report screen state to the thermal .ko (thermal_message/screen_state).
     * The Rust daemon reads it every cycle and drops to a slow 3 s loop with
     * reduced work while the display is off — lower idle drain than the
     * vendor mi_thermald/joyose stack, which keeps polling at full rate.
     */
    private fun registerScreenStateReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: android.content.Intent) {
                    val on = intent.action == android.content.Intent.ACTION_SCREEN_ON
                    handler.post { KernelNodeIO.write(ThermalNodes.SCREEN_STATE, on) }
                }
            },
            filter,
            null,
            handler,
        )
        KernelNodeIO.write(ThermalNodes.SCREEN_STATE, true)
    }

    private fun registerChargerReceiver() {
        val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    // Re-publish the band after PD negotiation settles
                    handler.postDelayed({
                        Log.i(TAG, "Charger connected: re-publishing charge band")
                        applyChargeSpeed()
                    }, 2000)
                }
            },
            filter,
            null,
            handler
        )
    }

    // ── Observers ───────────────────────────────────────────────────────────

    private fun registerObservers() {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onSettingChanged(uri?.toString().orEmpty())
            }
        }
        arrayOf(
            ThermalProfiles.KEY_THERMAL_PROFILE,
            ThermalProfiles.KEY_TEMP_LIMIT,
            ThermalProfiles.KEY_TEMP_OFFSET,
            ThermalProfiles.KEY_CHARGE_MAX_WATT,
            ThermalProfiles.KEY_CHARGE_MIN_WATT,
            ThermalProfiles.KEY_APP_PROFILES,
            ThermalProfiles.KEY_CUSTOM_PROFILES,
            ThermalProfiles.KEY_BYPASS_CHARGING,
            ThermalProfiles.KEY_HBM_ENABLED,
            ThermalProfiles.KEY_HBM_TIMEOUT,
            ThermalProfiles.KEY_DC_DIMMING,
        ).forEach {
            resolver.registerContentObserver(
                Settings.System.getUriFor(it), false, observer, UserHandle.USER_ALL
            )
        }

        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE), false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = onBatterySaverChanged()
            },
            UserHandle.USER_ALL
        )
    }

    private fun onSettingChanged(uri: String) {
        loadSettings()

        when {
            uri.contains(ThermalProfiles.KEY_APP_PROFILES) -> {
                loadAppProfiles()
                // Re-apply for the current foreground app before falling back
                val fgProfile = appProfiles[foregroundPackage]
                if (fgProfile != null) {
                    if (!batterySaverActive) applyProfileIndex(fgProfile)
                    return
                }
            }
            uri.contains(ThermalProfiles.KEY_CUSTOM_PROFILES) -> {
                if (thermalProfile >= ThermalProfiles.CUSTOM_PROFILE_BASE &&
                    !batterySaverActive
                ) {
                    applyGlobalProfile()
                    return
                }
            }
            uri.contains(ThermalProfiles.KEY_BYPASS_CHARGING) -> {
                applyBypassCharging(); return
            }
            uri.contains(ThermalProfiles.KEY_HBM_ENABLED) ||
                uri.contains(ThermalProfiles.KEY_HBM_TIMEOUT) -> {
                applyHbm(); return
            }
            uri.contains(ThermalProfiles.KEY_DC_DIMMING) -> {
                applyDcDimming(); return
            }
        }
        applyAll()
    }

    companion object {
        private const val TAG = "GameSpaceThermal"
    }
}
