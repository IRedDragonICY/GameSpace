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
package com.ireddragonicy.gamespace.thermal.custom

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import com.ireddragonicy.gamespace.ui.settings.SettingsFreqPickerRow
import com.ireddragonicy.gamespace.ui.settings.SettingsValueSlider
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickerSheet(
    sources: List<ProfileSource>,
    blankLabel: String,
    onBlank: () -> Unit,
    onPick: (ProfileSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val vendors = sources.filterIsInstance<ProfileSource.Vendor>()
    val customs = sources.filterIsInstance<ProfileSource.Custom>()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            "Start from",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        LazyColumn(modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 28.dp)) {
            item {
                SourceRow(
                    icon = Icons.AutoMirrored.Rounded.NoteAdd,
                    title = blankLabel,
                    subtitle = "No throttle levels",
                    trailingText = null,
                    onClick = onBlank,
                )
            }
            item { SheetLabel("Xiaomi vendor profiles") }
            items(vendors, key = { it.id }) { v ->
                SourceRow(
                    icon = Icons.Rounded.DeviceThermostat,
                    title = v.name,
                    subtitle = v.config?.let { "target ${it.targetTempC.toInt()}°C" } ?: "No limits",
                    trailingText = "sconfig ${v.sconfig}",
                    onClick = { onPick(v) },
                )
            }
            if (customs.isNotEmpty()) {
                item { SheetLabel("My profiles") }
                items(customs, key = { it.id }) { c ->
                    SourceRow(
                        icon = Icons.Rounded.ContentCopy,
                        title = c.name,
                        subtitle = c.description,
                        trailingText = null,
                        onClick = { onPick(c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun SourceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingText: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Reusable dense UI components for the custom profile editor.
 * Material You flat design: surfaceContainerHigh cards, zero elevation,
 * M3 Slider, compact rows. Follows the same visual language as
 * PerAppSettingsScreen in this codebase.
 */

// -- Section header (uppercase label, primary color, tight spacing) --

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
    )
}

// -- Grouped card (one card per logical group, rows packed inside) --

@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) { content() }
    }
}

// -- Single throttle level row (trigger + clear + freq + delete) --

@Composable
fun ThrottleLevelRow(
    index: Int,
    level: ThrottleLevel,
    freqTable: List<Long>,
    freqIsMhz: Boolean = false,
    onChange: (ThrottleLevel) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Level ${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${level.trigMc.mcToTempString()} / ${level.clrMc.mcToTempString()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Remove level",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        SettingsValueSlider(
            label = "Trigger",
            value = level.trigMc.mcToC(),
            range = 25f..55f,
            onCommit = { mc ->
                val trig = mc.cToMc()
                val clr = if (level.clrMc >= trig) trig - 1_500L else level.clrMc
                onChange(level.copy(trigMc = trig, clrMc = clr))
            },
            valueText = { "%.1f".format(it) },
        )
        SettingsValueSlider(
            label = "Clear",
            value = level.clrMc.mcToC(),
            range = 25f..55f,
            onCommit = { mc -> onChange(level.copy(clrMc = mc.cToMc())) },
            valueText = { "%.1f".format(it) },
        )
        SettingsFreqPickerRow(
            label = if (freqIsMhz) "GPU" else "CPU",
            selectedValue = level.freq,
            table = freqTable,
            isMhz = freqIsMhz,
            onSelect = { onChange(level.copy(freq = it)) },
        )
    }
}

// -- Add level button --

@Composable
fun AddLevelButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
