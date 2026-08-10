/*
 * Copyright (C) 2021 Chaldeaprjkt
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
package com.ireddragonicy.gamespace

import android.app.Application
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import dagger.hilt.android.HiltAndroidApp
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.gamebar.GameSpaceService
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.thermal.ThermalController
import javax.inject.Inject

@HiltAndroidApp(Application::class)
class GameSpace : Hilt_GameSpace() {

    private val TAG = "GameSpace"

    /**
     * How long the process must survive before a freshly applied global perf
     * profile counts as safe. Long enough to cover the thermal ramp that an
     * over-aggressive tuning would fall over on, short enough that a normal
     * boot clears the guard well before the user could reboot again.
     */
    private val PERF_STABLE_MS = 90_000L

    @Inject lateinit var thermalController: ThermalController
    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var perfTuner: PerfTuner

    /**
     * EROFS + adb install -r defense.
     *
     * TIDAK bisa catch GraphicsEnvironment.setupAngle() NPE — itu terjadi
     * di handleBindApplication() SEBELUM attachBaseContext().
     *
     * Yang BISA di-catch di sini:
     * - Resources corrupt setelah setupAngle() lolos tapi AssetManager stale
     * - Hilt component mismatch (old APK classes + new APK resources)
     * - Any exception selama super.attachBaseContext()
     *
     * Strategy: kalau Resources broken → kill self → AMS respawn →
     * second attempt punya LoadedApk fresh → berhasil.
     * Rate-limited 3× untuk prevent bootloop.
     */
    override fun attachBaseContext(base: Context) {
        try {
            super.attachBaseContext(base)
        } catch (e: Exception) {
            Log.e(TAG, "attachBaseContext failed — stale overlay?", e)
            killForRestart("attachBaseContext")
            return
        }

        // Pre-warm: force AssetManager resolve resource table NOW.
        // Kalau EROFS overlay belum synced, ini crash DI SINI
        // (bukan di setupAngle yang sudah lewat), dan kita bisa kill + respawn.
        try {
            val res = resources
            if (res == null) {
                Log.e(TAG, "Resources null after attach — killing for respawn")
                killForRestart("null-resources")
                return
            }
            // Touch framework string → force resource table mmap
            res.getString(android.R.string.ok)
        } catch (e: Exception) {
            Log.e(TAG, "Resource pre-warm failed", e)
            killForRestart("resource-prewarm")
            return
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application created")

        // Reset recovery counter on successful boot
        getSharedPreferences("gamespace_recovery", MODE_PRIVATE)
            .edit().putInt("restart_attempts", 0).apply()

        // Skin state harus terisi SEBELUM activity pertama compose
        appSettings.syncThemeState()

        thermalController.start()
        applyGlobalPerfProfile()
        startGameSpaceService()
    }

    /**
     * Apply the user's global CPU/GPU baseline, guarded against a profile that
     * makes the device unusable.
     *
     * The counter is bumped *before* the profile lands and only cleared once the
     * process has survived [PERF_STABLE_MS]. A tuning that hangs or panics the
     * device therefore never reaches the clear, so two bad boots in a row drop
     * the profile instead of leaving a bootloop only recovery can fix.
     *
     * Note this runs post-unlock, so an unstable profile can never block boot
     * itself — the guard exists for the window after the user is in.
     */
    private fun applyGlobalPerfProfile() {
        val prefs = getSharedPreferences("gamespace_recovery", MODE_PRIVATE)
        val attempts = prefs.getInt("perf_apply_attempts", 0)

        if (attempts >= 2) {
            prefs.edit()
                .putInt("perf_apply_attempts", 0)
                .apply()
            perfTuner.saveGlobalProfile(PerfTuner.PerfProfile())
            Log.e(TAG, "Global perf profile reset: $attempts unstable boots in a row")
            return
        }

        prefs.edit().putInt("perf_apply_attempts", attempts + 1).apply()
        perfTuner.applyGlobal()

        Handler(Looper.getMainLooper()).postDelayed({
            getSharedPreferences("gamespace_recovery", MODE_PRIVATE)
                .edit().putInt("perf_apply_attempts", 0).apply()
            Log.d(TAG, "Global perf profile considered stable")
        }, PERF_STABLE_MS)
    }

    private fun startGameSpaceService() {
        try {
            val intent = Intent(this, GameSpaceService::class.java)
            startServiceAsUser(intent, UserHandle.CURRENT)
            Log.i(TAG, "GameSpaceService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GameSpaceService", e)
        }
    }

    private fun killForRestart(reason: String) {
        val prefs = getSharedPreferences("gamespace_recovery", MODE_PRIVATE)
        val attempts = prefs.getInt("restart_attempts", 0)
        if (attempts < 3) {
            prefs.edit().putInt("restart_attempts", attempts + 1).apply()
            Log.w(TAG, "Self-kill for respawn ($reason, attempt ${attempts + 1}/3)")
            Process.killProcess(Process.myPid())
        } else {
            prefs.edit().putInt("restart_attempts", 0).apply()
            Log.e(TAG, "3 restarts exhausted ($reason). NOT killing — " +
                "service broken until next reboot or manual restart.")
        }
    }
}
