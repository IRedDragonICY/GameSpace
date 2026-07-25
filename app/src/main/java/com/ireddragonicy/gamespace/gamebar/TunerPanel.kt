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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PERF TUNER — granular per-game CPU/GPU DVFS control, ROG style.
 *
 * Backed by [PerfTuner]: every commit snaps to the kernel-reported frequency
 * tables, persists per game and applies immediately through the fd-cached
 * sysfs layer. Collapsed by default to keep the panel lean.
 */
@Composable
fun TunerEntryRow(
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val perfTuner = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext, ServiceViewEntryPoint::class.java
        ).perfTuner()
    }
    val accent = LocalPanelAccent.current
    val profile = perfTuner.activeProfile.collectAsState().value
        ?: PerfTuner.PerfProfile()

    // Compact pill — sits beside the thermal + display pills; opens the tabbed
    // tuner page (swaps the whole panel content, so the panel never scrolls).
    // Icon-only button to save space
    Box(
        modifier = modifier
            .size(28.dp)
            .background(Color.White.copy(alpha = 0.05f), remember { RoundedCornerShape(8.dp) })
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled),
            contentDescription = stringResource(R.string.tuner_title),
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        // Status as a colored dot in the top right corner
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(4.dp)
                .background(
                    if (profile.isDefault) Color.Transparent else accent,
                    CircleShape,
                ),
        )
    }
}

/**
 * Full-page TUNER — replaces the panel content entirely with a tabbed host
 * (PERF / TOUCH / FRAME / GFX). The last-opened tab is persisted, so the panel
 * stays scroll-free regardless of how many clusters the SoC exposes.
 */
@Composable
fun TunerScreen(
    tileRepository: TileRepository,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val accent = LocalPanelAccent.current
    val prefs = remember {
        context.getSharedPreferences("panel_prefs", Context.MODE_PRIVATE)
    }
    var selectedTab by remember {
        mutableStateOf(
            TunerTab.values().getOrElse(prefs.getInt("tuner_last_tab", 0)) { TunerTab.PERF }
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = PanelTheme.TextPrimary,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onClose),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.tuner_title),
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        TunerTabRow(
            selected = selectedTab,
            accent = accent,
            onSelect = { tab ->
                selectedTab = tab
                prefs.edit().putInt("tuner_last_tab", tab.ordinal).apply()
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        when (selectedTab) {
            TunerTab.PERF -> PerfTunerTab(accent)
            TunerTab.TOUCH -> TouchTab(tileRepository, accent)
            TunerTab.FRAME -> FrameTab(tileRepository, accent)
            TunerTab.GFX -> GraphicsTab(tileRepository, accent)
        }
    }
}

/**
 * PERF tab — per-game CPU/GPU frequency ceilings, unchanged from before and
 * shared with the Game Hub editor via [PerformanceEditor].
 */
@Composable
private fun PerfTunerTab(accent: Color) {
    val context = LocalContext.current
    val perfTuner = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext, ServiceViewEntryPoint::class.java
        ).perfTuner()
    }
    val profile = perfTuner.activeProfile.collectAsState().value
        ?: PerfTuner.PerfProfile()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Frequency ceilings are per-game, snap to kernel tables and apply live.",
            color = PanelTheme.TextDim,
            fontSize = 9.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        PerformanceEditor(
            perfTuner = perfTuner,
            profile = profile,
            accent = accent,
            onCommit = { perfTuner.updateActiveProfile(it) },
        )
    }
}

/**
 * The tuning editor body — shared between the in-game overlay (edits the
 * ACTIVE profile) and the Game Hub per-game editor (edits ANY game via
 * [PerfTuner.saveProfileFor]). All slider positions snap to kernel tables.
 */
@Composable
fun PerformanceEditor(
    perfTuner: PerfTuner,
    profile: PerfTuner.PerfProfile,
    accent: Color,
    onCommit: (PerfTuner.PerfProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GpuRangeControl(perfTuner, profile, accent, onCommit)

        perfTuner.cpuClusters.forEach { cluster ->
            CpuMaxControl(profile, cluster, accent, onCommit)
        }

        if (!profile.isDefault) {
            Text(
                text = "RESET TO STOCK",
                color = PanelTheme.Danger,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { onCommit(PerfTuner.PerfProfile()) }
                    .padding(4.dp),
            )
        }
    }
}

private fun List<Long>.indexOfNearest(value: Long): Int {
    if (isEmpty()) return 0
    var best = 0
    for (i in indices) if (abs(this[i] - value) < abs(this[best] - value)) best = i
    return best
}

@Composable
private fun GpuRangeControl(
    perfTuner: PerfTuner,
    profile: PerfTuner.PerfProfile,
    accent: Color,
    onCommit: (PerfTuner.PerfProfile) -> Unit,
) {
    val gpu = perfTuner.gpuInfo
    val table = gpu.availableHz
    if (table.size < 2) return

    val minIdx = table.indexOfNearest(profile.gpuMinHz ?: gpu.hwMinHz)
    val maxIdx = table.indexOfNearest(profile.gpuMaxHz ?: gpu.hwMaxHz)
    var range by remember(minIdx, maxIdx) {
        mutableStateOf(minIdx.toFloat()..maxIdx.toFloat())
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "GPU",
                color = accent.copy(alpha = 0.85f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.width(44.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "%d–%d MHz".format(
                    table[range.start.roundToInt().coerceIn(table.indices)] / 1_000_000,
                    table[range.endInclusive.roundToInt().coerceIn(table.indices)] / 1_000_000,
                ),
                color = PanelTheme.TextDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            valueRange = 0f..(table.size - 1).toFloat(),
            steps = (table.size - 2).coerceAtLeast(0),
            onValueChangeFinished = {
                val newMin = table[range.start.roundToInt().coerceIn(table.indices)]
                val newMax = table[range.endInclusive.roundToInt().coerceIn(table.indices)]
                onCommit(
                    profile.copy(
                        gpuMinHz = newMin.takeIf { it != gpu.hwMinHz },
                        gpuMaxHz = newMax.takeIf { it != gpu.hwMaxHz },
                    )
                )
            },
            colors = panelSliderColors(accent),
            modifier = Modifier.height(24.dp),
        )
    }
}

@Composable
private fun CpuMaxControl(
    profile: PerfTuner.PerfProfile,
    cluster: PerfTuner.CpuCluster,
    accent: Color,
    onCommit: (PerfTuner.PerfProfile) -> Unit,
) {
    val table = cluster.availableKhz
    if (table.size < 2) return

    val tuning = profile.clusters[cluster.index] ?: PerfTuner.ClusterTuning()
    val curIdx = table.indexOfNearest(tuning.maxKhz ?: cluster.hwMaxKhz)
    var idx by remember(curIdx) { mutableFloatStateOf(curIdx.toFloat()) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = cluster.name.uppercase(),
                color = accent.copy(alpha = 0.85f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.width(44.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "≤ %.2f GHz".format(
                    table[idx.roundToInt().coerceIn(table.indices)] / 1_000_000f
                ),
                color = PanelTheme.TextDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = idx,
            onValueChange = { idx = it },
            valueRange = 0f..(table.size - 1).toFloat(),
            steps = (table.size - 2).coerceAtLeast(0),
            onValueChangeFinished = {
                val newMax = table[idx.roundToInt().coerceIn(table.indices)]
                val newClusters = profile.clusters.toMutableMap()
                newClusters[cluster.index] =
                    tuning.copy(maxKhz = newMax.takeIf { it != cluster.hwMaxKhz })
                onCommit(profile.copy(clusters = newClusters))
            },
            colors = panelSliderColors(accent),
            modifier = Modifier.height(24.dp),
        )
    }
}

@Composable
private fun panelSliderColors(accent: Color) = SliderDefaults.colors(
    thumbColor = accent,
    activeTrackColor = accent.copy(alpha = 0.8f),
    inactiveTrackColor = Color.White.copy(alpha = 0.08f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.White.copy(alpha = 0.12f),
)
