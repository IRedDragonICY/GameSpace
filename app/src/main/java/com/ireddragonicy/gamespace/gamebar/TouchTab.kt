/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.PerAppJson
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.touch.GameTouchModeManager
import com.ireddragonicy.gamespace.touch.TouchSampleRateView
import com.ireddragonicy.gamespace.touch.XiaomiTouchFeatureClient

private const val KEY_SUPER_REPORT = "gamespace_touch_super_report"
private const val KEY_THRESHOLD = "gamespace_touch_threshold"
private const val KEY_TOLERANCE = "gamespace_touch_tolerance"
private const val KEY_AIM_SENS = "gamespace_touch_aim_sens"
private const val KEY_TAP_STAB = "gamespace_touch_tap_stab"
private const val KEY_EDGE_FILTER = "gamespace_touch_edge_filter"

@Composable
fun TouchTab(tileRepository: TileRepository, accent: Color) {
    val context = LocalContext.current
    val manager = tileRepository.touchModeManager
    val halAvailable = remember(manager) { manager?.isHalAvailable ?: false }
    val bench = rememberBenchState()

    Column(modifier = Modifier.fillMaxWidth()) {
        // header: title + expand-to-full-window
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TunerSectionLabel(stringResource(R.string.touch_test_title), accent)
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background2(accent).clickable { tileRepository.touchTesterExpanded.value = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Rounded.OpenInFull, "Expand", tint = accent, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("FULL", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        AndroidView(
            factory = { ctx -> TouchSampleRateView(ctx, compact = true) },
            modifier = Modifier.fillMaxWidth().height(150.dp).clip(remember { chamferShape() }),
        )
        TunerHint("Multi-touch enabled · swipe here no longer scrolls the panel. Open FULL for the latency lab.")
        LastTapChip(bench)

        if (!halAvailable) return@Column
        TunerSectionLabel(stringResource(R.string.tuner_touch_section_general), accent)

        var htsr by remember { mutableStateOf(Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_HTSR_ENABLED, 1) == 1) }
        TunerToggleRow(stringResource(R.string.tuner_touch_htsr), htsr, { e -> htsr = e; manager?.toggleHTSR(e) }, accent)

        var superTouch by remember { mutableStateOf(Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_SUPER_TOUCH_ENABLED, 1) == 1) }
        var superTouchLevel by remember { mutableIntStateOf(Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_SUPER_TOUCH_LEVEL, 100)) }
        TunerToggleRow(stringResource(R.string.tuner_touch_super_touch), superTouch, { e -> superTouch = e; manager?.toggleSuperTouch(e) }, accent)
        TunerIntSlider(stringResource(R.string.tuner_touch_super_touch_level), superTouchLevel, 0..100, { superTouchLevel = it }, { manager?.setSuperTouchLevel(it) }, accent, valueText = { "$it%" })

        var firstFrameBoost by remember { mutableStateOf(Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_FIRST_FRAME_BOOST, 1) == 1) }
        TunerToggleRow(stringResource(R.string.tuner_touch_first_frame_boost), firstFrameBoost, { e -> firstFrameBoost = e; manager?.toggleFirstFrameBoost(e) }, accent)

        var gameVibration by remember { mutableStateOf(Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_GAME_VIBRATION, 0) == 1) }
        TunerToggleRow(stringResource(R.string.tuner_touch_game_vibration), gameVibration, { e -> gameVibration = e; manager?.toggleGameVibration(e) }, accent)

        var hotArea by remember { mutableStateOf(Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_HOT_AREA_ENABLED, 1) == 1) }
        TunerToggleRow(stringResource(R.string.tuner_touch_hot_area), hotArea, { e -> hotArea = e; manager?.toggleHotArea(e) }, accent)

        val pkg = tileRepository.currentGamePackage
        TunerSectionLabel(stringResource(R.string.tuner_touch_section_per_app), accent)
        if (pkg == null) NoActiveGameNote() else PerAppTouchTuning(context, pkg, accent)
    }
}

// tiny accent-tinted background helper (avoids extra import noise above)
private fun Modifier.background2(accent: Color): Modifier =
    this.then(Modifier.background(accent.copy(alpha = 0.12f)))

@Composable
private fun PerAppTouchTuning(context: Context, pkg: String, accent: Color) {
    val store = remember { com.ireddragonicy.gamespace.data.PerAppSettingStore(context) }

    var superReport by remember(pkg) { mutableStateOf(store.touchSuperReport(pkg)) }
    TunerToggleRow(stringResource(R.string.touch_test_super_report), superReport, { e ->
        superReport = e
        store.setTouchSuperReport(pkg, e)
        XiaomiTouchFeatureClient.setTouchMode(XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE, if (e) 1 else 0)
    }, accent)
    
    PerAppTuneSlider(stringResource(R.string.tune_touch_up_threshold_title), { store.touchThreshold(it) }, { p, v -> store.setTouchThreshold(p, v) }, 0..4, XiaomiTouchFeatureClient.MODE_TUNE_TOUCH_UP_THRESHOLD, pkg, accent)
    PerAppTuneSlider(stringResource(R.string.tune_jitter_tolerance_title), { store.touchTolerance(it) }, { p, v -> store.setTouchTolerance(p, v) }, 0..4, XiaomiTouchFeatureClient.MODE_TUNE_JITTER_TOLERANCE, pkg, accent)
    PerAppTuneSlider(stringResource(R.string.tune_aim_sensitivity_title), { store.touchAimSens(it) }, { p, v -> store.setTouchAimSens(p, v) }, 0..4, XiaomiTouchFeatureClient.MODE_TUNE_AIM_SENSITIVITY, pkg, accent)
    PerAppTuneSlider(stringResource(R.string.tune_tap_stability_title), { store.touchTapStab(it) }, { p, v -> store.setTouchTapStab(p, v) }, 0..4, XiaomiTouchFeatureClient.MODE_TUNE_TAP_STABILITY, pkg, accent)
    PerAppTuneSlider(stringResource(R.string.tune_edge_filter_title), { store.touchEdgeFilter(it) }, { p, v -> store.setTouchEdgeFilter(p, v) }, 0..3, XiaomiTouchFeatureClient.MODE_TUNE_EDGE_FILTER, pkg, accent)
}

@Composable
private fun PerAppTuneSlider(label: String, getter: (String) -> Int, setter: (String, Int) -> Unit, range: IntRange, modeId: Int, pkg: String, accent: Color) {
    var value by remember(pkg) { mutableIntStateOf(getter(pkg)) }
    TunerIntSlider(label, value, range, { value = it; XiaomiTouchFeatureClient.setTouchMode(modeId, it) }, { setter(pkg, it) }, accent)
}
