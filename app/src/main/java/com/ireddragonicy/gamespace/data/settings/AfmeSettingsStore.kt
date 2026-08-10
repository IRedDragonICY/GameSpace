package com.ireddragonicy.gamespace.data.settings

import com.ireddragonicy.gamespace.data.SettingsIO
import javax.inject.Inject
import javax.inject.Singleton

/** Raw per-app AFME persistence. Live-prop publishing stays in the facade. */
@Singleton
class AfmeSettingsStore @Inject constructor(private val io: SettingsIO) {

    companion object {
        const val KEY_AFME_ENABLED   = "gamespace_afme_enabled"
        const val KEY_AFME_MULTIPLIER= "gamespace_afme_multiplier"
        const val KEY_AFME_FACTOR    = "gamespace_afme_factor"
        const val KEY_AFME_METHOD    = "gamespace_afme_method"
        const val KEY_AFME_VRS_FG    = "gamespace_afme_vrs_fg"
        const val KEY_AFME_HUD_MASK  = "gamespace_afme_hud_mask"
        const val KEY_SMOOTH_MOTION  = "gamespace_smooth_motion"
    }

    fun hasEnabledKey(pkg: String) = io.hasPerApp(KEY_AFME_ENABLED, pkg)
    fun hasMultiplier(pkg: String) = io.hasPerApp(KEY_AFME_MULTIPLIER, pkg)

    fun enabled(pkg: String): Boolean =
        if (hasEnabledKey(pkg)) io.getPerAppBoolean(KEY_AFME_ENABLED, pkg, false)
        else hasMultiplier(pkg) // legacy: multiplier present == on

    fun setEnabled(pkg: String, enabled: Boolean) {
        io.putPerApp(KEY_AFME_ENABLED, pkg, enabled)
        io.setProp("persist.sys.afme.optin.$pkg", if (enabled) "1" else "0")
    }

    fun multiplier(pkg: String): Int = io.getPerAppInt(KEY_AFME_MULTIPLIER, pkg, 2).coerceIn(2, 4)
    fun setMultiplier(pkg: String, m: Int) = io.putPerApp(KEY_AFME_MULTIPLIER, pkg, m.coerceIn(2, 4))

    fun factor(pkg: String): String = io.getPerAppString(KEY_AFME_FACTOR, pkg, "auto")
    fun setFactor(pkg: String, factor: String) = io.putPerApp(KEY_AFME_FACTOR, pkg, factor.takeIf { it != "auto" })

    fun method(pkg: String): Int = io.getPerAppInt(KEY_AFME_METHOD, pkg, 0)
    fun setMethod(pkg: String, method: Int) = io.putPerApp(KEY_AFME_METHOD, pkg, method.takeIf { it > 0 })


    fun vrsFg(pkg: String): Boolean = io.getPerAppBoolean(KEY_AFME_VRS_FG, pkg, true)
    fun setVrsFg(pkg: String, enabled: Boolean) = io.putPerApp(KEY_AFME_VRS_FG, pkg, enabled)

    fun hudMask(pkg: String): Boolean = io.getPerAppBoolean(KEY_AFME_HUD_MASK, pkg, true)
    fun setHudMask(pkg: String, enabled: Boolean) = io.putPerApp(KEY_AFME_HUD_MASK, pkg, enabled)

    fun smoothMotion(pkg: String): Boolean = io.getPerAppBoolean(KEY_SMOOTH_MOTION, pkg, false)
    fun setSmoothMotion(pkg: String, enabled: Boolean) = io.putPerApp(KEY_SMOOTH_MOTION, pkg, enabled)

    fun removeAll(pkg: String) = listOf(
        KEY_AFME_ENABLED, KEY_AFME_MULTIPLIER, KEY_AFME_FACTOR, KEY_AFME_METHOD,
        KEY_AFME_VRS_FG, KEY_AFME_HUD_MASK, KEY_SMOOTH_MOTION,
    ).forEach { io.putPerApp(it, pkg, null) }
}
