/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2026 crDroid Android Project
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
package com.ireddragonicy.gamespace.preferences

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat

import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.GameConfig
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.data.UserGame
import com.ireddragonicy.gamespace.settings.PerAppSettingsActivity
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import com.ireddragonicy.gamespace.utils.entryPointOf

class AppListPreferences @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    PreferenceCategory(context, attrs), Preference.OnPreferenceClickListener {

    private val apps = mutableListOf<UserGame>()

    private val systemSettings by lazy {
        context.entryPointOf<ServiceViewEntryPoint>().systemSettings()
    }

    private val gameModeUtils by lazy {
        context.entryPointOf<ServiceViewEntryPoint>().gameModeUtils()
    }

    private lateinit var registeredAppClickAction: (String) -> Unit

    init {
        isOrderingAsAdded = false
    }

    private val makeAddPref by lazy {
        Preference(context).apply {
            title = context.getString(R.string.add_game)
            key = KEY_ADD_GAME
            setIcon(R.drawable.materialsymbols_ic_add_rounded_filled)
            isPersistent = false
            onPreferenceClickListener = this@AppListPreferences
        }
    }

    private val autoDetectPref by lazy {
        SwitchPreferenceCompat(context).apply {
            key = KEY_AUTO_GAME_DETECT
            title = context.getString(R.string.auto_game_detect_title)
            summary = context.getString(R.string.auto_game_detect_summary)
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                    systemSettings.autoGameDetect = newValue as Boolean
                true
            }
        }
    }

    private fun getAppInfo(packageName: String): ApplicationInfo? = try {
        val flags = PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        context.packageManager.getApplicationInfo(packageName, flags)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun isGameCategory(packageName: String): Boolean =
        getAppInfo(packageName)?.category == ApplicationInfo.CATEGORY_GAME

    private fun readDeniedList(): MutableSet<String> {
        val raw = Settings.System.getStringForUser(
            context.contentResolver, KEY_DENIED_LIST,
            UserHandle.USER_CURRENT
        ) ?: return mutableSetOf()
        return raw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
    }

    private fun writeDeniedList(set: Set<String>) {
        Settings.System.putStringForUser(
            context.contentResolver,
            KEY_DENIED_LIST,
            set.joinToString(";"),
            UserHandle.USER_CURRENT
        )
    }

    private fun launchGame(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // No-op
        }
    }

    private fun buildGamePref(game: UserGame): Preference {
        val info = getAppInfo(game.packageName)
        val pm = context.packageManager

        return object : Preference(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)

                val canLaunch = pm.getLaunchIntentForPackage(game.packageName) != null
                holder.findViewById(R.id.launch_icon)?.let { play ->
                    play.visibility = if (canLaunch) View.VISIBLE else View.GONE
                    play.setOnClickListener { launchGame(game.packageName) }
                }

                holder.findViewById(R.id.settings_icon)?.setOnClickListener {
                    if (::registeredAppClickAction.isInitialized) {
                        registeredAppClickAction(game.packageName)
                    }
                }
            }
        }.apply {
            key = game.packageName
            title = info?.loadLabel(pm)
            summary = describeGameConfig(game.packageName)
            icon = info?.loadIcon(pm)
            layoutResource = R.layout.library_item
            isPersistent = false
        }
    }

    /**
     * Build a meaningful summary for the game list, showing which features are active
     * instead of the old AOSP GameManager mode labels (Performance/Battery/etc).
     */
    private fun describeGameConfig(packageName: String): String {
        val features = mutableListOf<String>()

        // Check thermal profile
        try {
            val json = Settings.System.getStringForUser(
                context.contentResolver, "mithermal_app_profiles", UserHandle.USER_CURRENT)
            if (json != null) {
                val obj = org.json.JSONObject(json)
                if (obj.has(packageName)) {
                    val profileIdx = obj.getInt(packageName)
                    val profileNames = arrayOf(
                        "", "Default", "Performance", "Heavy Gaming", "Light Gaming",
                        "Genshin Impact", "Honkai Star Rail", "High FPS", "Camera",
                        "4K Recording", "Video Playback", "Video Chat", "Navigation",
                        "Phone Call", "AR/VR", "Data Transfer", "Battery Saver"
                    )
                    if (profileIdx in profileNames.indices && profileNames[profileIdx].isNotEmpty()) {
                        features.add(profileNames[profileIdx])
                    }
                }
            }
        } catch (_: Exception) {}

        // Check AFME
        try {
            val json = Settings.System.getStringForUser(
                context.contentResolver, "gamespace_afme_multiplier", UserHandle.USER_CURRENT)
            if (json != null) {
                val obj = org.json.JSONObject(json)
                val mult = obj.optInt(packageName, 0)
                if (mult > 0) features.add("${mult}× Frame Gen")
            }
        } catch (_: Exception) {}

        // Check resolution
        try {
            val json = Settings.System.getStringForUser(
                context.contentResolver, "gamespace_game_resolution", UserHandle.USER_CURRENT)
            if (json != null) {
                val obj = org.json.JSONObject(json)
                if (obj.has(packageName)) {
                    val factor = obj.getString(packageName)
                    if (factor != "1.0") features.add("Res: ${factor}x")
                }
            }
        } catch (_: Exception) {}

        // Check VRS
        try {
            val json = Settings.System.getStringForUser(
                context.contentResolver, "gamespace_vrs_level", UserHandle.USER_CURRENT)
            if (json != null) {
                val obj = org.json.JSONObject(json)
                val level = obj.optInt(packageName, 0)
                if (level > 0) {
                    val vrsLabels = arrayOf("", "VRS 2×1", "VRS 2×2", "VRS 4×4")
                    features.add(if (level in vrsLabels.indices) vrsLabels[level] else "VRS")
                }
            }
        } catch (_: Exception) {}

        // Check Color Enhance
        try {
            val json = Settings.System.getStringForUser(
                context.contentResolver, "gamespace_color_enhance", UserHandle.USER_CURRENT)
            if (json != null) {
                val obj = org.json.JSONObject(json)
                if (obj.optBoolean(packageName, false)) features.add("Color+")
            }
        } catch (_: Exception) {}

        // Check Graphics Enhancement (SGSR/MobFGSR)
        try {
            val json = Settings.System.getStringForUser(
                context.contentResolver, "gamespace_sgsr_mode", UserHandle.USER_CURRENT)
            if (json != null) {
                val obj = org.json.JSONObject(json)
                val mode = obj.optInt(packageName, 0)
                when (mode) {
                    1 -> features.add("SGSR1")
                    2 -> features.add("SGSR2")
                    3 -> features.add("MobFGSR")
                }
            }
        } catch (_: Exception) {}

        return if (features.isEmpty()) {
            context.getString(R.string.game_mode_balanced)
        } else {
            features.joinToString(" · ")
        }
    }

    fun updateAppList() {
        apps.clear()
        systemSettings.userGames?.let { apps.addAll(it) }

        removeAll()
        addPreference(autoDetectPref)
        addPreference(makeAddPref)

        apps
            .filter { getAppInfo(it.packageName) != null }
            .map(::buildGamePref)
            .sortedBy { it.title.toString().lowercase() }
            .forEach(::addPreference)
    }

    private fun registerApp(packageName: String) {
        // Lift any prior deny so the framework will auto-keep this on reinstall.
        val denied = readDeniedList()
        if (denied.remove(packageName)) writeDeniedList(denied)

        if (apps.none { it.packageName == packageName }) {
            apps.add(UserGame(packageName))
        }
        systemSettings.userGames = apps
        gameModeUtils.setIntervention(packageName, GameConfig.ModeBuilder.build())
        updateAppList()
    }

    private fun unregisterApp(packageName: String) {
        // Persist the user's "no" so the framework's auto-detect skips this package.
        if (isGameCategory(packageName)) {
            val denied = readDeniedList()
            if (denied.add(packageName)) writeDeniedList(denied)
        }

        apps.removeIf { it.packageName == packageName }
        systemSettings.userGames = apps
        gameModeUtils.setIntervention(packageName, null)
        updateAppList()
    }

    override fun onAttached() {
        super.onAttached()
        updateAppList()
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        return true
    }

    fun onRegisteredAppClick(action: (String) -> Unit) {
        registeredAppClickAction = action
    }

    fun usePerAppResult(result: ActivityResult?) {
        result?.takeIf { it.resultCode == Activity.RESULT_OK }
            ?.data?.getStringExtra(PerAppSettingsActivity.PREF_UNREGISTER)
            ?.let { unregisterApp(it) }
    }

    fun useSelectorResult(result: ActivityResult?) {
        result?.takeIf { it.resultCode == Activity.RESULT_OK }
            ?.data?.getStringExtra(EXTRA_APP)
            ?.let { registerApp(it) }
    }

    companion object {
        const val KEY_ADD_GAME = "add_game"
        const val EXTRA_APP = "selected_app"
        const val KEY_DENIED_LIST = "gamespace_denied_list"
        const val KEY_AUTO_GAME_DETECT = "gamespace_auto_game_detect"
    }
}
