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
import com.ireddragonicy.gamespace.utils.rememberPerAppStore

private val AFME_FACTORS = listOf("auto", "0.25", "0.33", "0.5", "0.67", "0.75", "1.0")

/**
 * FRAME tab — AFME frame generation + smooth motion, per game. Everything
 * routes through [PerAppSettingStore] so this tab and the per-app settings
 * screen can never drift apart.
 */
@Composable
fun FrameTab(tileRepository: TileRepository, accent: Color) {
    val context = LocalContext.current
    val store = rememberPerAppStore()
    val pkg = tileRepository.currentGamePackage
    Column(modifier = Modifier.fillMaxWidth()) {
        if (pkg == null) { NoActiveGameNote(); return@Column }

        val revision = PerAppSettingStore.afmeRevision
        val enabled = remember(pkg, revision) { store.afmeEnabled(pkg) }     // ON/OFF
        val multiplier = remember(pkg, revision) { store.afmeMultiplier(pkg) } // 2..4
        var factor by remember(pkg) { mutableStateOf(store.afmeFactor(pkg)) }
        var method by remember(pkg) { mutableIntStateOf(store.afmeMethod(pkg)) }
        var smoothMotion by remember(pkg) { mutableStateOf(store.smoothMotion(pkg)) }
        var vrsFg by remember(pkg) { mutableStateOf(store.afmeVrsFg(pkg)) }
        var hudMask by remember(pkg) { mutableStateOf(store.afmeHudMask(pkg)) }

        // Tier saja — tanpa "Off". Off itu tugas switch di atas.
        val multiplierOptions = remember { listOf("2×" to 2, "3×" to 3, "4×" to 4) }
        val factorOptions = remember {
            AFME_FACTORS.map { v ->
                (if (v == "auto") context.getString(R.string.tuner_choice_auto) else v) to v
            }
        }
        val methodOptions = remember {
            listOf(
                context.getString(R.string.tuner_frame_afme_method_extrapolate) to 0,
                context.getString(R.string.tuner_frame_afme_method_motion) to 1,
            )
        }

        TunerSectionLabel(text = stringResource(R.string.tuner_frame_section_afme), accent = accent)

        // ── ON / OFF — inilah yang di-flip oleh tile quick‑QS ──
        TunerToggleRow(
            label = stringResource(R.string.tuner_frame_afme_multiplier), // teks = "Frame generation"
            checked = enabled,
            onCheckedChange = { store.setAfmeEnabled(pkg, it) },
            accent = accent,
        )
        // ── Tier: setting independen, selalu bisa dipilih (pre‑configure saat off) ──
        Column(modifier = Modifier.padding(top = 8.dp)) {
            TunerSegmented(
                label = stringResource(R.string.tuner_frame_afme_tier),
                options = multiplierOptions,
                selected = multiplier,
                onSelect = { store.setAfmeMultiplier(pkg, it) },
                accent = accent,
            )
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            TunerSegmented(
                label = stringResource(R.string.tuner_frame_afme_method),
                options = methodOptions, selected = method,
                onSelect = { method = it; store.setAfmeMethod(pkg, it) },
                accent = accent, enabled = enabled,
            )
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            TunerSegmented(
                label = stringResource(R.string.tuner_frame_afme_factor),
                options = factorOptions, selected = factor,
                onSelect = { factor = it; store.setAfmeFactor(pkg, it) },
                accent = accent, enabled = enabled,
            )
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            TunerToggleRow(
                label = stringResource(R.string.tuner_frame_afme_vrs_fg),
                checked = vrsFg,
                onCheckedChange = { vrsFg = it; store.setAfmeVrsFg(pkg, it) },
                accent = accent, enabled = enabled,
            )
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            TunerToggleRow(
                label = stringResource(R.string.tuner_frame_afme_hud_mask),
                checked = hudMask,
                onCheckedChange = { hudMask = it; store.setAfmeHudMask(pkg, it) },
                accent = accent, enabled = enabled && method == 1,
            )
        }

        TunerSectionLabel(text = stringResource(R.string.tuner_frame_section_pacing), accent = accent)
        TunerToggleRow(
            label = stringResource(R.string.tuner_frame_smooth_motion),
            checked = smoothMotion,
            onCheckedChange = { smoothMotion = it; store.setSmoothMotion(pkg, it) },
            accent = accent,
        )
    }
}
