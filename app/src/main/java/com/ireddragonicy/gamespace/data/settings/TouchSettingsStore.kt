package com.ireddragonicy.gamespace.data.settings

import com.ireddragonicy.gamespace.data.SettingsIO
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-app touch settings.
 *
 * The report rate uses the vendor HAL's verified NT36532 path. Edge and grip
 * settings use GameSpace's companion OSS input-side filter, which applies to
 * the real event stream after TouchReport has decoded a point.  Old AIDL
 * tuner keys remain retired because mode 2-7/15 are merely cached by the
 * closed THP HAL on this device.
 */
@Singleton
class TouchSettingsStore @Inject constructor(private val io: SettingsIO) {

    companion object {
        const val KEY_TOUCH_SUPER_REPORT = "gamespace_touch_super_report"
        const val KEY_TOUCH_EDGE_FILTER_V2 = "gamespace_touch_edge_filter_v2"
        const val KEY_TOUCH_GRIP_SUPPRESSION = "gamespace_touch_grip_suppression"

        /** Keys written by older builds for controls that did nothing. */
        private val RETIRED_KEYS = listOf(
            "gamespace_touch_expert_mode",
            "gamespace_touch_expert_preset",
            "gamespace_touch_threshold",
            "gamespace_touch_tolerance",
            "gamespace_touch_aim_sens",
            "gamespace_touch_tap_stab",
            "gamespace_touch_edge_filter",
        )
    }

    fun superReport(pkg: String) = io.getPerAppBoolean(KEY_TOUCH_SUPER_REPORT, pkg, true)
    fun setSuperReport(pkg: String, v: Boolean) = io.putPerApp(KEY_TOUCH_SUPER_REPORT, pkg, v)

    fun edgeFilter(pkg: String) =
        io.getPerAppInt(KEY_TOUCH_EDGE_FILTER_V2, pkg, 0).coerceIn(0, 3)

    fun setEdgeFilter(pkg: String, level: Int) =
        io.putPerApp(KEY_TOUCH_EDGE_FILTER_V2, pkg, level.coerceIn(0, 3))

    fun gripSuppression(pkg: String) =
        io.getPerAppInt(KEY_TOUCH_GRIP_SUPPRESSION, pkg, 0).coerceIn(0, 3)

    fun setGripSuppression(pkg: String, level: Int) =
        io.putPerApp(KEY_TOUCH_GRIP_SUPPRESSION, pkg, level.coerceIn(0, 3))

    fun removeAll(pkg: String) =
        (listOf(
            KEY_TOUCH_SUPER_REPORT,
            KEY_TOUCH_EDGE_FILTER_V2,
            KEY_TOUCH_GRIP_SUPPRESSION,
        ) + RETIRED_KEYS).forEach { io.putPerApp(it, pkg, null) }
}
