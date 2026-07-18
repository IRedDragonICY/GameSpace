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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository

private val AFME_FACTORS = listOf("auto", "0.25", "0.33", "0.5", "0.67", "0.75", "1.0")

/**
 * FRAME tab — AFME frame generation + smooth motion, per game. Everything
 * routes through [PerAppSettingStore] so this tab and the per-app settings
 * screen can never drift apart.
 */
@Composable
fun FrameTab(tileRepository: TileRepository, accent: Color) {
    val context = LocalContext.current
    val store = remember { PerAppSettingStore(context) }
    val pkg = tileRepository.currentGamePackage

    Column(modifier = Modifier.fillMaxWidth()) {
        if (pkg == null) {
            NoActiveGameNote()
            return@Column
        }

        var multiplier by remember(pkg) { mutableIntStateOf(store.afmeMultiplier(pkg)) }
        var factor by remember(pkg) { mutableStateOf(store.afmeFactor(pkg)) }
        var smoothMotion by remember(pkg) { mutableStateOf(store.smoothMotion(pkg)) }

        val multiplierOptions = remember {
            listOf(
                context.getString(R.string.tuner_choice_off) to 0,
                "2×" to 2,
                "3×" to 3,
                "4×" to 4,
            )
        }
        val factorOptions = remember {
            AFME_FACTORS.map { value ->
                (if (value == "auto") context.getString(R.string.tuner_choice_auto) else value) to value
            }
        }

        TunerSectionLabel(text = stringResource(R.string.tuner_frame_section_afme), accent = accent)
        TunerSegmented(
            label = stringResource(R.string.tuner_frame_afme_multiplier),
            options = multiplierOptions,
            selected = multiplier,
            onSelect = {
                multiplier = it
                store.setAfmeMultiplier(pkg, it)
            },
            accent = accent,
        )

        Column(modifier = Modifier.padding(top = 8.dp)) {
            TunerSegmented(
                label = stringResource(R.string.tuner_frame_afme_factor),
                options = factorOptions,
                selected = factor,
                onSelect = {
                    factor = it
                    store.setAfmeFactor(pkg, it)
                },
                accent = accent,
                enabled = multiplier > 0,
            )
        }

        TunerSectionLabel(text = stringResource(R.string.tuner_frame_section_pacing), accent = accent)
        TunerToggleRow(
            label = stringResource(R.string.tuner_frame_smooth_motion),
            checked = smoothMotion,
            onCheckedChange = {
                smoothMotion = it
                store.setSmoothMotion(pkg, it)
            },
            accent = accent,
        )
    }
}
