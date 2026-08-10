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

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import kotlin.math.roundToInt

/**
 * Tabs of the in-panel tuner page. Order == persisted ordinal (see
 * [TunerScreen]); the drawables reuse icons already shipped for the tiles.
 */
enum class TunerTab(@StringRes val labelRes: Int, val iconRes: Int) {
    PERF(R.string.tuner_tab_perf, R.drawable.materialsymbols_ic_tune_rounded_filled),
    TOUCH(R.string.tuner_tab_touch, R.drawable.materialsymbols_ic_touch_app_rounded_filled),
    FRAME(R.string.tuner_tab_frame, R.drawable.materialsymbols_ic_auto_awesome_rounded_filled),
    GFX(R.string.tuner_tab_gfx, R.drawable.materialsymbols_ic_palette_rounded_filled),
    FILTER(R.string.tuner_tab_filter, R.drawable.ic_color_vibrant),
    SOUND(R.string.tuner_tab_sound, R.drawable.ic_sound_eq),
}

/** Horizontally scrollable pill row — selected pill is accent-filled. */
@Composable
fun TunerTabRow(
    selected: TunerTab,
    accent: Color,
    onSelect: (TunerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val shape = remember { chamferShape(bigCut = 8.dp, smallCut = 3.dp) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TunerTab.values().forEach { tab ->
            val isSelected = tab == selected
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(shape)
                    .background(if (isSelected) accent else Color.White.copy(alpha = 0.05f))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(tab.iconRes),
                    contentDescription = null,
                    tint = if (isSelected) Color.Black else accent,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(tab.labelRes),
                    color = if (isSelected) Color.Black else PanelTheme.TextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

// ── Shared tab building blocks ─────────────────────────────────────────────

/** Uppercase accent section header. */
@Composable
fun TunerSectionLabel(text: String, accent: Color) {
    Text(
        text = text,
        color = accent.copy(alpha = 0.85f),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

/** Dim inline hint line. */
@Composable
fun TunerHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = PanelTheme.TextDim,
        fontSize = 9.sp,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/** Placeholder shown by per-app tabs when no game session is active. */
@Composable
fun NoActiveGameNote() {
    Text(
        text = stringResource(R.string.tuner_no_active_game),
        color = PanelTheme.TextDim,
        fontSize = 10.sp,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** Label + Material 3 switch, styled to the panel accent. */
@Composable
fun TunerToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (enabled) PanelTheme.TextPrimary else PanelTheme.TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = tunerSwitchColors(accent),
        )
    }
}

/** Segmented choice: label above, a row of equal-weight pills. */
@Composable
fun <T> TunerSegmented(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    accent: Color,
    enabled: Boolean = true,
) {
    val shape = remember { RoundedCornerShape(6.dp) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = if (enabled) PanelTheme.TextPrimary else PanelTheme.TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (optLabel, value) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(shape)
                        .background(
                            when {
                                isSelected && enabled -> accent
                                isSelected -> accent.copy(alpha = 0.4f)
                                else -> Color.White.copy(alpha = 0.05f)
                            }
                        )
                        .then(
                            if (enabled) Modifier.clickable { onSelect(value) } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = optLabel,
                        color = when {
                            isSelected -> Color.Black
                            enabled -> PanelTheme.TextPrimary
                            else -> PanelTheme.TextDim
                        },
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Integer slider that snaps to discrete steps for small ranges (0..4, 0..3)
 * and stays continuous for wide ones (0..100). [onChange] fires live per step
 * (cheap side-effects), [onCommit] on release (persist).
 */
@Composable
fun TunerIntSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    accent: Color,
    valueText: (Int) -> String = { it.toString() },
    modifier: Modifier = Modifier,
) {
    var pos by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val current = pos.roundToInt().coerceIn(range.first, range.last)
    val span = range.last - range.first
    val steps = if (span in 2..10) span - 1 else 0

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = PanelTheme.TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText(current),
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = pos,
            onValueChange = {
                val prev = pos.roundToInt().coerceIn(range.first, range.last)
                pos = it
                val now = it.roundToInt().coerceIn(range.first, range.last)
                if (now != prev) onChange(now)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            onValueChangeFinished = {
                onCommit(pos.roundToInt().coerceIn(range.first, range.last))
            },
            colors = tunerSliderColors(accent),
            modifier = Modifier.height(24.dp),
        )
    }
}

/**
 * Continuous factor slider (e.g. render-resolution scale). Snaps to [step],
 * reports the live value while dragging via [onChange] and the settled value
 * via [onCommit] — the caller applies on commit so a live rescale fires once
 * per gesture, not per pixel.
 */
@Composable
fun TunerScaleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    accent: Color,
    valueText: (Float) -> String,
    modifier: Modifier = Modifier,
) {
    fun snap(v: Float): Float {
        val snapped = (Math.round(v / step) * step)
        return snapped.coerceIn(range.start, range.endInclusive)
    }

    var pos by remember(value) { mutableFloatStateOf(value) }
    val steps = (((range.endInclusive - range.start) / step).roundToInt() - 1)
        .coerceAtLeast(0)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = PanelTheme.TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText(snap(pos)),
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = pos,
            onValueChange = {
                val prev = snap(pos)
                pos = it
                val now = snap(it)
                if (now != prev) onChange(now)
            },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onCommit(snap(pos)) },
            colors = tunerSliderColors(accent),
            modifier = Modifier.height(24.dp),
        )
    }
}

@Composable
private fun tunerSwitchColors(accent: Color) = SwitchDefaults.colors(
    checkedThumbColor = Color.Black,
    checkedTrackColor = accent,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
    uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
    uncheckedBorderColor = Color.Transparent,
)

@Composable
private fun tunerSliderColors(accent: Color) = SliderDefaults.colors(
    thumbColor = accent,
    activeTrackColor = accent.copy(alpha = 0.8f),
    inactiveTrackColor = Color.White.copy(alpha = 0.08f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.White.copy(alpha = 0.12f),
)
