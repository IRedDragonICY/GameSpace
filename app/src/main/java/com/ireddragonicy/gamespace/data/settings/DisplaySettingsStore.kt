package com.ireddragonicy.gamespace.data.settings

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.ireddragonicy.gamespace.data.ActiveSessionStore
import com.ireddragonicy.gamespace.data.PerAppJson
import com.ireddragonicy.gamespace.data.SettingsIO
import com.ireddragonicy.gamespace.display.DisplayColorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DisplaySettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val io: SettingsIO,
    private val activeSession: ActiveSessionStore,
) {
    companion object { const val KEY_DISPLAY_STYLE = "gamespace_display_style" }

    private val displayColorManager by lazy { DisplayColorManager.get(context) }
    private fun legacyKey(pkg: String) = "game_color_mode_$pkg"

    fun style(pkg: String): Int {
        val json = io.read(KEY_DISPLAY_STYLE)
        if (PerAppJson.has(json, pkg)) return PerAppJson.getInt(json, pkg, 0)
        val legacy = runCatching {
            Settings.System.getIntForUser(context.contentResolver, legacyKey(pkg), 0, UserHandle.USER_CURRENT)
        }.getOrDefault(0)
        if (legacy != 0) io.write(KEY_DISPLAY_STYLE, PerAppJson.put(json, pkg, legacy))
        return legacy
    }

    fun setStyle(pkg: String, mode: Int) {
        io.putPerApp(KEY_DISPLAY_STYLE, pkg, mode.takeIf { it > 0 })
        if (activeSession.currentPackage == pkg) displayColorManager.applyStyle(mode)
    }

    fun applyForGame(pkg: String) = displayColorManager.applyForGame(style(pkg))
    fun restore() = displayColorManager.restore()

    fun removeAll(pkg: String) {
        io.putPerApp(KEY_DISPLAY_STYLE, pkg, null)
        runCatching {
            Settings.System.putStringForUser(context.contentResolver, legacyKey(pkg), null, UserHandle.USER_CURRENT)
        }
    }
}
