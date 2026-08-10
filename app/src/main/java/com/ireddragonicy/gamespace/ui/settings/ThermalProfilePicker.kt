/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.getProfileIcon
import com.ireddragonicy.gamespace.gamebar.getThermalProfileColor
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import kotlinx.coroutines.launch

/**
 * The thermal-profile control, shared by every screen that offers one.
 *
 * Renders as a [SettingsDropdownRow]-shaped row that opens a bottom sheet
 * rather than a floating menu: the list is long (17 built-in + unbounded
 * custom) and a popup that tall clamps to a screen corner and fights the thumb.
 *
 * UX decisions:
 *  - Always bottom-anchored → predictable, thumb-friendly, never clips the
 *    status bar.
 *  - Search: built-in is fixed but custom profiles grow without bound.
 *  - BUILT-IN vs MY PROFILES grouping, so a custom profile never hides among
 *    the vendor scenes.
 *  - Sticky "Create custom profile…" footer — the in-place escape hatch, when
 *    [onCreateCustom] is wired.
 *
 * Stateless: the caller owns [selected] and persists in [onSelect].
 */
@Composable
fun ThermalProfileRow(
    options: List<ThermalProfiles.ProfileOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Thermal profile",
    onCreateCustom: (() -> Unit)? = null,
) {
    var showSheet by remember { mutableStateOf(false) }
    val name = options.firstOrNull { it.index == selected }?.name
        ?: ThermalProfiles.PROFILE_NAMES.getOrElse(selected) { "Auto" }
    val color = getThermalProfileColor(selected)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showSheet = true }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = getProfileIcon(selected, name),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Rounded.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showSheet) {
        ThermalProfileSheet(
            options = options,
            selected = selected,
            onSelect = onSelect,
            onCreateCustom = onCreateCustom,
            onDismiss = { showSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThermalProfileSheet(
    options: List<ThermalProfiles.ProfileOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onCreateCustom: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val filtered = remember(options, q) {
        if (q.isEmpty()) options else options.filter { it.name.lowercase().contains(q) }
    }
    val (custom, builtin) = filtered.partition { it.isCustom }

    // Animated programmatic dismiss (swipe/backdrop just unmount via onDismiss).
    val hideAndThen: (() -> Unit) -> Unit = { then ->
        scope.launch {
            sheetState.hide()
            then()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = "Thermal profile",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        // Search — only earns its keep when the list is long, which it is.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search profiles") },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_search_rounded_filled),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Bounded + scrollable list (footer below stays pinned).
        Column(
            modifier = Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            if (filtered.isEmpty()) {
                Text(
                    text = "No profiles match “$query”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                )
            }
            listOf("Built-in" to builtin, "My profiles" to custom).forEach { (title, group) ->
                if (group.isEmpty()) return@forEach
                SheetSectionLabel(title)
                group.forEach { option ->
                    SheetProfileRow(option, selected = option.index == selected) {
                        onSelect(option.index)
                        hideAndThen(onDismiss)
                    }
                }
            }
        }

        // Sticky escape hatch — forge a profile without leaving this screen.
        if (onCreateCustom != null) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { hideAndThen(onCreateCustom) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Create custom profile…",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SheetProfileRow(
    option: ThermalProfiles.ProfileOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = getThermalProfileColor(option.index)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = getProfileIcon(option.index, option.name),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = option.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
