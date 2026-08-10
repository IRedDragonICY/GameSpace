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

import android.graphics.Point
import android.view.Display
import android.view.WindowManagerGlobal
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.utils.rememberPerAppStore
import kotlin.math.min

/** Named tier for a scale factor, closest wins; purely a readout hint. */
private val RESOLUTION_TIERS = listOf(
    2.0f to "Ultra Sharp", 1.5f to "Super Sharp", 1.25f to "Sharp",
    1.0f to "Native", 0.85f to "High", 0.75f to "Medium-High",
    0.65f to "Medium", 0.5f to "Low", 0.35f to "Very Low", 0.25f to "Ultra Low",
)

private const val RES_MIN = 0.25f
private const val RES_MAX = 2.0f
private const val RES_STEP = 0.05f

/** Format a scale factor as the framework expects: "1.0", "0.9", "1.25". */
private fun formatFactor(f: Float): String {
    if (f >= 0.999f && f <= 1.001f) return "1.0"
    val s = "%.2f".format(f).trimEnd('0').trimEnd('.')
    return if (s.contains('.')) s else "$s.0"
}

private fun tierName(f: Float): String =
    RESOLUTION_TIERS.minByOrNull { kotlin.math.abs(it.first - f) }?.second ?: ""

/**
 * GFX tab — upscaler, variable rate shading, render resolution and color
 * enhance, all per game via [PerAppSettingStore].
 */
@Composable
fun GraphicsTab(tileRepository: TileRepository, accent: Color) {
    val store = rememberPerAppStore()
    val pkg = tileRepository.currentGamePackage
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        if (pkg == null) {
            NoActiveGameNote()
            return@Column
        }

        var sgsrMode by remember(pkg) { mutableIntStateOf(store.sgsrMode(pkg)) }
        var vrsLevel by remember(pkg) { mutableIntStateOf(store.vrsLevel(pkg)) }
        var resolution by remember(pkg) {
            mutableFloatStateOf(store.resolution(pkg).toFloatOrNull() ?: 1.0f)
        }
        var colorEnhance by remember(pkg) { mutableStateOf(store.colorEnhance(pkg)) }
        var msaa by remember(pkg) { mutableIntStateOf(store.gpuMsaa(pkg)) }
        var gpuComposition by remember(pkg) { mutableStateOf(store.gpuComposition(pkg)) }
        // AF shares the AFME arming state, so read it through the revision
        // instead of caching — see PerAppSettingStore.syncAfmeState.
        val af = remember(pkg, PerAppSettingStore.afmeRevision) { store.gpuAf(pkg) }

        // Native panel size (unscaled), for the live W×H readout — same source
        // the old per-app dialog used.
        val nativeSize = remember {
            try {
                val wms = WindowManagerGlobal.getWindowManagerService()
                val p = Point()
                wms?.getInitialDisplaySize(Display.DEFAULT_DISPLAY, p)
                if (p.x > 0 && p.y > 0) p else Point(1080, 2400)
            } catch (e: Exception) {
                Point(1080, 2400)
            }
        }
        // Landscape game: report the long edge as width in the readout.
        val nativeLong = kotlin.math.max(nativeSize.x, nativeSize.y)
        val nativeShort = min(nativeSize.x, nativeSize.y)

        val sgsrOptions = remember {
            listOf(
                context.getString(R.string.tuner_choice_off) to 0,
                context.getString(R.string.tuner_gfx_sgsr1) to 1,
                context.getString(R.string.tuner_gfx_sgsr2) to 2,
                context.getString(R.string.tuner_gfx_mobfgsr) to 3,
            )
        }
        val afOptions = remember {
            listOf(
                context.getString(R.string.tuner_choice_off) to 0,
                "2×" to 2,
                "4×" to 4,
                "8×" to 8,
                "16×" to 16,
            )
        }
        val vrsOptions = remember {
            listOf(
                context.getString(R.string.tuner_choice_off) to 0,
                "2×1" to 1,
                "2×2" to 2,
                "4×4" to 3,
            )
        }
        TunerSectionLabel(text = stringResource(R.string.tuner_gfx_section_upscale), accent = accent)
        TunerSegmented(
            label = stringResource(R.string.tuner_gfx_enhancement),
            options = sgsrOptions,
            selected = sgsrMode,
            onSelect = {
                sgsrMode = it
                store.setSgsrMode(pkg, it)
            },
            accent = accent,
        )

        TunerSectionLabel(text = stringResource(R.string.tuner_gfx_section_rendering), accent = accent)
        TunerSegmented(
            label = stringResource(R.string.tuner_gfx_vrs),
            options = vrsOptions,
            selected = vrsLevel,
            onSelect = {
                vrsLevel = it
                store.setVrsLevel(pkg, it)
            },
            accent = accent,
        )
        TunerScaleSlider(
            label = stringResource(R.string.tuner_gfx_resolution),
            value = resolution,
            range = RES_MIN..RES_MAX,
            step = RES_STEP,
            onChange = { resolution = it },
            onCommit = { f ->
                resolution = f
                // Live rescale: the framework applies this to the running game
                // immediately (WindowManagerInternal.updateCompatScaleForPackage).
                store.setResolution(pkg, formatFactor(f))
            },
            accent = accent,
            valueText = { f ->
                val w = (nativeLong * f).toInt()
                val h = (nativeShort * f).toInt()
                "${tierName(f)} · ${w}×${h} · ${formatFactor(f)}×"
            },
        )

        TunerSectionLabel(text = stringResource(R.string.tuner_gfx_section_gpu), accent = accent)
        TunerSegmented(
            label = stringResource(R.string.tuner_gfx_af),
            options = afOptions,
            selected = af,
            onSelect = { store.setGpuAf(pkg, it) },
            accent = accent,
        )
        TunerHint(text = stringResource(R.string.tuner_gfx_af_hint))
        TunerToggleRow(
            label = stringResource(R.string.tuner_gfx_msaa),
            checked = msaa > 0,
            onCheckedChange = {
                msaa = if (it) 4 else 0
                store.setGpuMsaa(pkg, msaa)
            },
            accent = accent,
        )
        TunerHint(text = stringResource(R.string.tuner_gfx_msaa_hint))
        TunerToggleRow(
            label = stringResource(R.string.tuner_gfx_gpu_composition),
            checked = gpuComposition,
            onCheckedChange = {
                gpuComposition = it
                store.setGpuComposition(pkg, it)
            },
            accent = accent,
        )
        TunerHint(text = stringResource(R.string.tuner_gfx_gpu_composition_hint))

        TunerSectionLabel(text = stringResource(R.string.tuner_gfx_section_color), accent = accent)
        TunerToggleRow(
            label = stringResource(R.string.tuner_gfx_color_enhance),
            checked = colorEnhance,
            onCheckedChange = {
                colorEnhance = it
                store.setColorEnhance(pkg, it)
            },
            accent = accent,
        )
    }
}
