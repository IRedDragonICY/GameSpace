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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.data.FilterKind
import com.ireddragonicy.gamespace.data.FilterNode
import com.ireddragonicy.gamespace.data.FilterPreset
import com.ireddragonicy.gamespace.data.FilterStack
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.utils.rememberPerAppStore
import kotlin.math.roundToInt

// Deliberately tighter than the shared Tuner widgets: this tab shows a stack of
// cards, each with up to five controls, and the standard 38dp slider row runs
// off the bottom of the panel after two filters.
private val CARD_SHAPE = RoundedCornerShape(4.dp)
private const val ROW_LABEL_SP = 10
private val ROW_HEIGHT = 30.dp

private fun fmt(kind: FilterKind, index: Int, v: Float): String = when {
    kind == FilterKind.COLORBLIND && index == 0 ->
        listOf("Off", "Protan", "Deutan", "Tritan")[v.roundToInt().coerceIn(0, 3)]
    kind == FilterKind.EXPOSURE && index == 0 -> "%+.2f".format(v)
    kind == FilterKind.COLOR && index == 4 -> "${Math.toDegrees(v.toDouble()).roundToInt()}°"
    kind == FilterKind.LETTERBOX -> if (v <= 0f) "Off" else "${(v * 200).roundToInt()}%"
    else -> "${(v * 100).roundToInt()}%"
}

/** One parameter: label and value share a line, slider sits directly under it. */
@Composable
private fun ParamRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    discrete: Boolean,
    valueText: String,
    accent: Color,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var pos by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = PanelTheme.TextPrimary,
                fontSize = ROW_LABEL_SP.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                color = accent,
                fontSize = ROW_LABEL_SP.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = pos,
            onValueChange = {
                val v = if (discrete) it.roundToInt().toFloat() else it
                if (v != pos) { pos = v; onChange(v) }
            },
            valueRange = range,
            steps = if (discrete) (range.endInclusive - range.start).roundToInt() - 1 else 0,
            onValueChangeFinished = { onCommit(pos) },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.08f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().height(14.dp),
        )
    }
}

/** Square icon button used for the move/remove controls in a card header. */
@Composable
private fun CardButton(
    glyph: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (enabled) Color.White.copy(alpha = 0.08f)
                else Color.White.copy(alpha = 0.03f)
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (enabled) accent else Color.White.copy(alpha = 0.20f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FilterCard(
    node: FilterNode,
    index: Int,
    count: Int,
    accent: Color,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onParam: (Int, Float, Boolean) -> Unit,
) {
    var open by remember(node.kind, index) { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = node.kind.label,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).clickable { open = !open },
            )
            CardButton("↑", accent, index > 0) { onMove(-1) }
            Box(Modifier.width(3.dp))
            CardButton("↓", accent, index < count - 1) { onMove(1) }
            Box(Modifier.width(3.dp))
            CardButton("✕", accent, true) { onRemove() }
        }

        if (open) {
            node.kind.paramLabels.forEachIndexed { i, label ->
                val range = node.kind.ranges.getOrNull(i) ?: 0f..1f
                ParamRow(
                    label = label,
                    value = node.param(i),
                    range = range,
                    discrete = node.kind.isDiscrete(i),
                    valueText = fmt(node.kind, i, node.param(i)),
                    accent = accent,
                    onChange = { onParam(i, it, false) },
                    onCommit = { onParam(i, it, true) },
                )
            }
        }
    }
}

/**
 * Filter tuning tab — manages frame-generation colour filters via [PerAppSettingStore].
 *
 * Drives the live filter stack, node parameters, and arms
 * `persist.sys.afme.filter.live` while composed so the layers follow a slider
 * drag frame by frame instead of at the usual 64-present poll.
 */
@Composable
fun FilterTab(tileRepository: TileRepository, accent: Color) {
    val store = rememberPerAppStore()
    val pkg = tileRepository.currentGamePackage

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (pkg == null) {
            NoActiveGameNote()
            return@Column
        }

        // Part of the shared AFME arming state, so re-read on the revision rather
        // than cached: the sidebar chip and the FRAME tab can flip it too.
        val enabled = remember(pkg, PerAppSettingStore.afmeRevision) {
            store.filterEnabled(pkg)
        }
        var stack by remember(pkg) { mutableStateOf(store.filterStack(pkg)) }
        var picking by remember(pkg) { mutableStateOf(false) }

        DisposableEffect(Unit) {
            store.setFilterLive(true)
            onDispose { store.setFilterLive(false) }
        }

        fun commit(next: FilterStack) {
            stack = next
            store.setFilterStack(pkg, next)
        }

        TunerToggleRow(
            label = "Color filter",
            checked = enabled,
            onCheckedChange = { store.setFilterEnabled(pkg, it) },
            accent = accent,
        )

        if (!enabled) return@Column

        // ── Presets: each just loads a stack the user could have built ──
        Text(
            text = "PRESETS",
            color = accent.copy(alpha = 0.85f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            FilterPreset.values().forEach { p ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { p.stack?.let { commit(it) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = p.label,
                        color = PanelTheme.TextPrimary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // ── The stack ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "FILTERS",
                color = accent.copy(alpha = 0.85f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${stack.nodes.size}/${FilterStack.MAX}",
                color = PanelTheme.TextDim,
                fontSize = 9.sp,
            )
        }

        stack.nodes.forEachIndexed { index, node ->
                FilterCard(
                    node = node,
                    index = index,
                    count = stack.nodes.size,
                    accent = accent,
                    onMove = { commit(stack.move(index, it)) },
                    onRemove = { commit(stack.removeAt(index)) },
                    onParam = { pi, v, persist ->
                        val next = stack.replaceAt(index, node.withParam(pi, v))
                        if (persist) {
                            stack = next
                            store.setFilterStack(pkg, next)
                        } else {
                            stack = next
                            store.pushStack(next)
                        }
                    },
                )
        }

        if (stack.nodes.isEmpty()) {
            TunerHint(text = "No filters yet — add one below.")
        }

        // ── Add ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(CARD_SHAPE)
                .background(accent.copy(alpha = if (picking) 0.22f else 0.12f))
                .clickable(enabled = stack.nodes.size < FilterStack.MAX) {
                    picking = !picking
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (picking) "Close" else "Add Filter  +",
                color = if (stack.nodes.size < FilterStack.MAX) accent
                        else Color.White.copy(alpha = 0.25f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        if (picking) {
            FilterKind.values().toList().chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.forEach { kind ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    commit(stack.add(kind))
                                    picking = false
                                },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = kind.label +
                                    if (kind.isScreenSpace) "  ·post" else "",
                                color = PanelTheme.TextPrimary,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            TunerHint(
                text = "·post filters run after frame generation — grain fed to " +
                    "the motion estimator would read as motion, and a vignette " +
                    "applied earlier smears with the camera."
            )
        }
    }
}
