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
package com.ireddragonicy.gamespace.gamebar

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.PerAppJson
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.touch.GameTouchModeManager
import com.ireddragonicy.gamespace.touch.TouchSampleRateView
import com.ireddragonicy.gamespace.touch.XiaomiTouchFeatureClient

// Per-app JSON keys (Settings.System) — mirrors the literals GameTouchModeManager
// applies at session start, so the tab and the session apply path never drift.
private const val KEY_SUPER_REPORT = "gamespace_touch_super_report"
private const val KEY_THRESHOLD = "gamespace_touch_threshold"
private const val KEY_TOLERANCE = "gamespace_touch_tolerance"
private const val KEY_AIM_SENS = "gamespace_touch_aim_sens"
private const val KEY_TAP_STAB = "gamespace_touch_tap_stab"
private const val KEY_EDGE_FILTER = "gamespace_touch_edge_filter"

/**
 * TOUCH tab — live sample-rate tester always shown; HAL-backed tuning
 * sections only when [GameTouchModeManager.isHalAvailable] (gate mirrors the
 * old TouchTestActivity's silent no-op when the vendor service is missing).
 */
@Composable
fun TouchTab(tileRepository: TileRepository, accent: Color) {
    val context = LocalContext.current
    val manager = tileRepository.touchModeManager
    val halAvailable = remember(manager) { manager?.isHalAvailable ?: false }

    Column(modifier = Modifier.fillMaxWidth()) {
        TunerSectionLabel(text = stringResource(R.string.touch_test_title), accent = accent)
        AndroidView(
            factory = { ctx -> TouchSampleRateView(ctx, compact = true) },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(remember { chamferShape() }),
        )
        TunerHint(text = stringResource(R.string.tuner_touch_tester_hint))

        if (!halAvailable) return@Column

        TunerSectionLabel(text = stringResource(R.string.tuner_touch_section_general), accent = accent)

        var htsr by remember {
            mutableStateOf(
                Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_HTSR_ENABLED, 1) == 1
            )
        }
        TunerToggleRow(
            label = stringResource(R.string.tuner_touch_htsr),
            checked = htsr,
            onCheckedChange = { enabled ->
                htsr = enabled
                manager?.toggleHTSR(enabled)
            },
            accent = accent,
        )

        var superTouch by remember {
            mutableStateOf(
                Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_SUPER_TOUCH_ENABLED, 1) == 1
            )
        }
        var superTouchLevel by remember {
            mutableIntStateOf(
                Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_SUPER_TOUCH_LEVEL, 100)
            )
        }
        TunerToggleRow(
            label = stringResource(R.string.tuner_touch_super_touch),
            checked = superTouch,
            onCheckedChange = { enabled ->
                superTouch = enabled
                manager?.toggleSuperTouch(enabled)
            },
            accent = accent,
        )
        TunerIntSlider(
            label = stringResource(R.string.tuner_touch_super_touch_level),
            value = superTouchLevel,
            range = 0..100,
            onChange = { superTouchLevel = it },
            onCommit = { manager?.setSuperTouchLevel(it) },
            accent = accent,
            valueText = { "$it%" },
        )

        var firstFrameBoost by remember {
            mutableStateOf(
                Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_FIRST_FRAME_BOOST, 1) == 1
            )
        }
        TunerToggleRow(
            label = stringResource(R.string.tuner_touch_first_frame_boost),
            checked = firstFrameBoost,
            onCheckedChange = { enabled ->
                firstFrameBoost = enabled
                manager?.toggleFirstFrameBoost(enabled)
            },
            accent = accent,
        )

        var gameVibration by remember {
            mutableStateOf(
                Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_GAME_VIBRATION, 0) == 1
            )
        }
        TunerToggleRow(
            label = stringResource(R.string.tuner_touch_game_vibration),
            checked = gameVibration,
            onCheckedChange = { enabled ->
                gameVibration = enabled
                manager?.toggleGameVibration(enabled)
            },
            accent = accent,
        )

        var hotArea by remember {
            mutableStateOf(
                Settings.System.getInt(context.contentResolver, GameTouchModeManager.KEY_HOT_AREA_ENABLED, 1) == 1
            )
        }
        TunerToggleRow(
            label = stringResource(R.string.tuner_touch_hot_area),
            checked = hotArea,
            onCheckedChange = { enabled ->
                hotArea = enabled
                manager?.toggleHotArea(enabled)
            },
            accent = accent,
        )

        val pkg = tileRepository.currentGamePackage
        TunerSectionLabel(text = stringResource(R.string.tuner_touch_section_per_app), accent = accent)
        if (pkg == null) {
            NoActiveGameNote()
        } else {
            PerAppTouchTuning(context = context, pkg = pkg, accent = accent)
        }
    }
}

@Composable
private fun PerAppTouchTuning(context: Context, pkg: String, accent: Color) {
    fun readJson(key: String): String? =
        Settings.System.getStringForUser(context.contentResolver, key, UserHandle.USER_CURRENT)

    fun writeJson(key: String, json: String) {
        Settings.System.putStringForUser(context.contentResolver, key, json, UserHandle.USER_CURRENT)
    }

    var superReport by remember(pkg) {
        mutableStateOf(PerAppJson.getBoolean(readJson(KEY_SUPER_REPORT), pkg, true))
    }
    TunerToggleRow(
        label = stringResource(R.string.touch_test_super_report),
        checked = superReport,
        onCheckedChange = { enabled ->
            superReport = enabled
            writeJson(KEY_SUPER_REPORT, PerAppJson.put(readJson(KEY_SUPER_REPORT), pkg, enabled))
            XiaomiTouchFeatureClient.setTouchMode(
                XiaomiTouchFeatureClient.MODE_SUPER_REPORT_RATE, if (enabled) 1 else 0
            )
        },
        accent = accent,
    )

    PerAppTuneSlider(
        label = stringResource(R.string.tune_touch_up_threshold_title),
        key = KEY_THRESHOLD,
        range = 0..4,
        modeId = XiaomiTouchFeatureClient.MODE_TUNE_TOUCH_UP_THRESHOLD,
        pkg = pkg,
        accent = accent,
        readJson = ::readJson,
        writeJson = ::writeJson,
    )
    PerAppTuneSlider(
        label = stringResource(R.string.tune_jitter_tolerance_title),
        key = KEY_TOLERANCE,
        range = 0..4,
        modeId = XiaomiTouchFeatureClient.MODE_TUNE_JITTER_TOLERANCE,
        pkg = pkg,
        accent = accent,
        readJson = ::readJson,
        writeJson = ::writeJson,
    )
    PerAppTuneSlider(
        label = stringResource(R.string.tune_aim_sensitivity_title),
        key = KEY_AIM_SENS,
        range = 0..4,
        modeId = XiaomiTouchFeatureClient.MODE_TUNE_AIM_SENSITIVITY,
        pkg = pkg,
        accent = accent,
        readJson = ::readJson,
        writeJson = ::writeJson,
    )
    PerAppTuneSlider(
        label = stringResource(R.string.tune_tap_stability_title),
        key = KEY_TAP_STAB,
        range = 0..4,
        modeId = XiaomiTouchFeatureClient.MODE_TUNE_TAP_STABILITY,
        pkg = pkg,
        accent = accent,
        readJson = ::readJson,
        writeJson = ::writeJson,
    )
    PerAppTuneSlider(
        label = stringResource(R.string.tune_edge_filter_title),
        key = KEY_EDGE_FILTER,
        range = 0..3,
        modeId = XiaomiTouchFeatureClient.MODE_TUNE_EDGE_FILTER,
        pkg = pkg,
        accent = accent,
        readJson = ::readJson,
        writeJson = ::writeJson,
    )
}

@Composable
private fun PerAppTuneSlider(
    label: String,
    key: String,
    range: IntRange,
    modeId: Int,
    pkg: String,
    accent: Color,
    readJson: (String) -> String?,
    writeJson: (String, String) -> Unit,
) {
    var value by remember(pkg) {
        mutableIntStateOf(PerAppJson.getInt(readJson(key), pkg, 2))
    }
    TunerIntSlider(
        label = label,
        value = value,
        range = range,
        onChange = {
            value = it
            XiaomiTouchFeatureClient.setTouchMode(modeId, it)
        },
        onCommit = {
            writeJson(key, PerAppJson.put(readJson(key), pkg, it))
        },
        accent = accent,
    )
}
