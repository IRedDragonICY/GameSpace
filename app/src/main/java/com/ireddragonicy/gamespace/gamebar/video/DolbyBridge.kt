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
package com.ireddragonicy.gamespace.gamebar.video

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Thin client for LunarisDolby.
 *
 * Writes go out as explicit broadcasts to `DolbyControlReceiver`; reads come back from
 * `Settings.Global`, which LunarisDolby publishes on every change. GameSpace deliberately
 * never touches the `dap_hw` AudioEffect itself — that effect is a single global session,
 * and a second owner attaching to it is what tears the DAP down mid-playback.
 */
object DolbyBridge {

    private const val TAG = "DolbyBridge"

    private const val DOLBY_PKG = "org.lunaris.dolby"
    private const val RECEIVER = "org.lunaris.dolby.receiver.DolbyControlReceiver"

    private const val ACTION_SET_ENABLED = "org.lunaris.dolby.action.SET_ENABLED"
    private const val ACTION_SET_PROFILE = "org.lunaris.dolby.action.SET_PROFILE"
    private const val EXTRA_ENABLED = "enabled"
    private const val EXTRA_PROFILE = "profile"

    /** Mirrors of LunarisDolby's published state (public: DolbyController observes them). */
    const val SETTINGS_DAP_ACTIVE = "dolby_dap_active"
    const val SETTINGS_PROFILE_ACTIVE = "dolby_profile_active"

    // Matches R.array.dolby_profile_values in LunarisDolby.
    const val PROFILE_DYNAMIC = 0
    const val PROFILE_MOVIE = 1
    const val PROFILE_MUSIC = 2
    const val PROFILE_GAME = 3

    /** False when LunarisDolby is not installed — the toolbox hides its Dolby tiles. */
    fun isAvailable(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(DOLBY_PKG, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, SETTINGS_DAP_ACTIVE, 0) != 0

    /** The live profile, or [PROFILE_DYNAMIC] if Dolby has never published one. */
    fun currentProfile(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, SETTINGS_PROFILE_ACTIVE, PROFILE_DYNAMIC)

    fun setEnabled(context: Context, enabled: Boolean) {
        send(context, ACTION_SET_ENABLED) { putExtra(EXTRA_ENABLED, enabled) }
    }

    /** Selecting a profile also turns Dolby on, LunarisDolby-side. */
    fun setProfile(context: Context, profile: Int) {
        send(context, ACTION_SET_PROFILE) { putExtra(EXTRA_PROFILE, profile) }
    }

    private inline fun send(context: Context, action: String, build: Intent.() -> Unit) {
        try {
            val intent = Intent(action).apply {
                component = ComponentName(DOLBY_PKG, RECEIVER)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                build()
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send $action", e)
        }
    }
}
