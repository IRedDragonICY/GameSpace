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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
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
            // A tile can ask for a specific tab (the AFME chip opens FRAME);
            // otherwise resume wherever the user last was.
            tileRepository.consumeRequestedTunerTab()
                ?: TunerTab.values().getOrElse(prefs.getInt("tuner_last_tab", 0)) { TunerTab.PERF }
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
            TunerTab.FILTER -> FilterTab(tileRepository, accent)
            TunerTab.SOUND -> SoundTab(tileRepository, accent)
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
            style = PerfEditorStyle.panel(accent),
            onCommit = { perfTuner.updateActiveProfile(it) },
        )
    }
}

/**
 * How the [PerformanceEditor] renders on the surface hosting it.
 *
 * The editor is used on two very different surfaces — the dark glass overlay
 * and a Material You settings screen — which want different palettes AND
 * different type scales: 9sp reads fine on a 300dp panel and is unusably small
 * on a full-screen settings list. Rather than fork the editor (which is exactly
 * the duplication that let the two screens drift apart in the first place), the
 * host hands in its own metrics.
 */
data class PerfEditorStyle(
    val accent: Color,
    val value: Color,
    val track: Color,
    val chipBackground: Color,
    val danger: Color,
    val labelSize: TextUnit,
    val governorSize: TextUnit,
    val sliderHeight: Dp,
) {
    companion object {
        /** In-game overlay: dark glass, white-alpha scale, panel-dense type. */
        @Composable
        fun panel(accent: Color) = PerfEditorStyle(
            accent = accent,
            value = PanelTheme.TextDim,
            track = Color.White.copy(alpha = 0.08f),
            chipBackground = Color.White.copy(alpha = 0.05f),
            danger = PanelTheme.Danger,
            labelSize = 9.sp,
            governorSize = 8.sp,
            sliderHeight = 24.dp,
        )

        /** Settings screens: Material colour scheme (light + dark), legible type. */
        @Composable
        fun material() = PerfEditorStyle(
            accent = MaterialTheme.colorScheme.primary,
            value = MaterialTheme.colorScheme.onSurfaceVariant,
            track = MaterialTheme.colorScheme.surfaceVariant,
            chipBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            danger = MaterialTheme.colorScheme.error,
            labelSize = 12.sp,
            governorSize = 11.sp,
            sliderHeight = 32.dp,
        )
    }
}

/**
 * The tuning editor body — the single frequency editor in the app, shared by
 * the in-game overlay (edits the ACTIVE profile), the Game Hub per-game editor
 * ([PerfTuner.saveProfileFor]) and the Global Profile screen
 * ([PerfTuner.saveGlobalProfile]). All slider positions snap to kernel tables.
 */
@Composable
fun PerformanceEditor(
    perfTuner: PerfTuner,
    profile: PerfTuner.PerfProfile,
    style: PerfEditorStyle,
    onCommit: (PerfTuner.PerfProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GpuRangeControl(perfTuner, profile, style, onCommit)

        perfTuner.cpuClusters.forEach { cluster ->
            CpuRangeControl(perfTuner, profile, cluster, style, onCommit)
        }

        if (!profile.isDefault) {
            Text(
                text = "RESET TO STOCK",
                color = style.danger,
                fontSize = style.labelSize,
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
    style: PerfEditorStyle,
    onCommit: (PerfTuner.PerfProfile) -> Unit,
) {
    val gpu = perfTuner.gpuInfo
    val table = gpu.availableHz
    if (table.size < 2) return

    RangeControl(
        name = "GPU",
        table = table,
        selectedMin = profile.gpuMinHz ?: gpu.hwMinHz,
        selectedMax = profile.gpuMaxHz ?: gpu.hwMaxHz,
        format = { lo, hi -> "%d–%d MHz".format(lo / 1_000_000, hi / 1_000_000) },
        style = style,
        governor = profile.gpuGovernor ?: gpu.defaultGovernor,
        governors = gpu.governors,
        onGovernor = { gov ->
            onCommit(profile.copy(gpuGovernor = gov.takeIf { it != gpu.defaultGovernor }))
        },
        onCommitRange = { newMin, newMax ->
            onCommit(
                profile.copy(
                    gpuMinHz = newMin.takeIf { it != gpu.hwMinHz },
                    gpuMaxHz = newMax.takeIf { it != gpu.hwMaxHz },
                )
            )
        },
    )
}

/**
 * Amber tick over the track marking where the thermal daemon has actually
 * pinned `scaling_max_freq`.
 *
 * The requested ceiling and the live one are different numbers whenever SS is
 * throttling — the kernel takes the most restrictive of the two — and without
 * this the slider looks like it is being ignored. [fraction] is 0..1 along the
 * same index space the slider uses.
 */
@Composable
private fun ThrottleGhost(fraction: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val x = (size.width * fraction).coerceIn(0f, size.width)
        drawLine(
            color = ThrottleAmber,
            start = Offset(x, size.height * 0.15f),
            end = Offset(x, size.height * 0.85f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private val ThrottleAmber = Color(0xFFFFB300)

@Composable
private fun CpuRangeControl(
    perfTuner: PerfTuner,
    profile: PerfTuner.PerfProfile,
    cluster: PerfTuner.CpuCluster,
    style: PerfEditorStyle,
    onCommit: (PerfTuner.PerfProfile) -> Unit,
) {
    val table = cluster.availableKhz
    if (table.size < 2) return

    val tuning = profile.clusters[cluster.policyIndex] ?: PerfTuner.ClusterTuning()

    // Live ceiling, polled off the same node the daemon writes.
    var liveMaxKhz by remember { mutableLongStateOf(0L) }
    LaunchedEffect(cluster.policyDir) {
        while (true) {
            liveMaxKhz = perfTuner.cpuLiveMaxKhz(cluster)
            delay(1000L)
        }
    }

    fun withCluster(update: PerfTuner.ClusterTuning) = onCommit(
        profile.copy(
            clusters = profile.clusters.toMutableMap()
                .apply { put(cluster.policyIndex, update) }
        )
    )

    RangeControl(
        name = cluster.name,
        table = table,
        selectedMin = tuning.minKhz ?: cluster.hwMinKhz,
        selectedMax = tuning.maxKhz ?: cluster.hwMaxKhz,
        format = { lo, hi -> "%.1f–%.1f GHz".format(lo / 1_000_000f, hi / 1_000_000f) },
        style = style,
        governor = tuning.governor ?: cluster.defaultGovernor,
        governors = cluster.governors,
        onGovernor = { gov ->
            withCluster(tuning.copy(governor = gov.takeIf { it != cluster.defaultGovernor }))
        },
        onCommitRange = { newMin, newMax ->
            withCluster(
                tuning.copy(
                    minKhz = newMin.takeIf { it != cluster.hwMinKhz },
                    maxKhz = newMax.takeIf { it != cluster.hwMaxKhz },
                )
            )
        },
        liveCeiling = liveMaxKhz.takeIf { it > 0 },
        formatCeiling = { "⚡%.1f".format(it / 1_000_000f) },
    )
}

/**
 * One frequency domain: label + live range readout, a snap-to-OPP range slider
 * and the governor picker. GPU and every CPU cluster are the same control with
 * different formatters — the only asymmetry is the throttle ghost, which the
 * GPU simply leaves unset.
 *
 * [liveCeiling] is the ceiling actually in force, which is not necessarily the
 * one we asked for: mi_thermal_engine writes `scaling_max_freq` too and the
 * lower value wins. Surfacing it is the only way the slider doesn't look like
 * it is being ignored while SS is throttling.
 */
@Composable
private fun RangeControl(
    name: String,
    table: List<Long>,
    selectedMin: Long,
    selectedMax: Long,
    format: (Long, Long) -> String,
    style: PerfEditorStyle,
    governor: String,
    governors: List<String>,
    onGovernor: (String) -> Unit,
    onCommitRange: (Long, Long) -> Unit,
    liveCeiling: Long? = null,
    formatCeiling: ((Long) -> String)? = null,
) {
    val minIdx = table.indexOfNearest(selectedMin)
    val maxIdx = table.indexOfNearest(selectedMax)
    var range by remember(minIdx, maxIdx) {
        mutableStateOf(minIdx.toFloat()..maxIdx.toFloat())
    }
    fun valueAt(pos: Float) = table[pos.roundToInt().coerceIn(table.indices)]
    val requestedMax = valueAt(range.endInclusive)
    val throttled = liveCeiling != null && liveCeiling < requestedMax

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name.uppercase(),
                color = style.accent.copy(alpha = 0.85f),
                fontSize = style.labelSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.widthIn(min = 44.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (throttled && formatCeiling != null) {
                Text(
                    text = formatCeiling(liveCeiling),
                    color = ThrottleAmber,
                    fontSize = style.labelSize,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = format(valueAt(range.start), requestedMax),
                color = style.value,
                fontSize = style.labelSize,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box {
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                valueRange = 0f..(table.size - 1).toFloat(),
                steps = (table.size - 2).coerceAtLeast(0),
                onValueChangeFinished = {
                    onCommitRange(valueAt(range.start), valueAt(range.endInclusive))
                },
                colors = SliderDefaults.colors(
                    thumbColor = style.accent,
                    activeTrackColor = style.accent.copy(alpha = 0.8f),
                    inactiveTrackColor = style.track,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = style.track,
                ),
                modifier = Modifier.height(style.sliderHeight),
            )
            if (throttled) {
                ThrottleGhost(
                    fraction = table.indexOfNearest(liveCeiling).toFloat() /
                        (table.size - 1).toFloat(),
                    modifier = Modifier
                        .matchParentSize()
                        .height(style.sliderHeight),
                )
            }
        }
        GovernorRow(governor, governors, style, onGovernor)
    }
}

/**
 * Horizontal governor picker. Every name the kernel reports is offered as-is —
 * this device's governor list is the authority, not a curated subset.
 */
@Composable
private fun GovernorRow(
    current: String,
    options: List<String>,
    style: PerfEditorStyle,
    onPick: (String) -> Unit,
) {
    if (options.size < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { gov ->
            val selected = gov == current
            Text(
                text = gov,
                color = if (selected) style.accent else style.value,
                fontSize = style.governorSize,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (selected) style.accent.copy(alpha = 0.15f)
                        else style.chipBackground
                    )
                    .clickable { onPick(gov) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
