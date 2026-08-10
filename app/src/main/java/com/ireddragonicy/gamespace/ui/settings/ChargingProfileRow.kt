/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.ireddragonicy.gamespace.charging.CustomChargingProfile

/**
 * Charging-curve selector, shared by the global and per-app editors.
 *
 * Same [SettingsDropdownRow] shape as every other "pick one of a list" control
 * in Settings, so the charging curve reads as one more profile knob instead of
 * a bespoke button — it used to be an OutlinedButton + DropdownMenu in the
 * per-app tab and a read-only text row in the global screen.
 *
 * A null id is the inherit case, spelled by [inheritLabel]: globally that means
 * the vendor's own table, per-app it means "whatever the global profile says".
 */
@Composable
fun ChargingProfileRow(
    profiles: List<CustomChargingProfile>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Charging curve",
    inheritLabel: String = "Vendor default",
) {
    // null is a real choice here, so it has to be an option and not just the
    // empty state — hence the nullable-id list rather than profiles alone.
    val ids: List<String?> = listOf(null) + profiles.map { it.id }
    fun nameOf(id: String?) = profiles.firstOrNull { it.id == id }?.name ?: inheritLabel

    SettingsDropdownRow(
        label = label,
        valueText = nameOf(selectedId),
        options = ids,
        isSelected = { it == selectedId },
        onSelect = onSelect,
        modifier = modifier,
        renderItem = { id ->
            Text(
                text = nameOf(id),
                fontWeight = if (id == selectedId) FontWeight.Bold else FontWeight.Normal,
                color = if (id == selectedId) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}
