/*
 * Copyright (C) 2026 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ireddragonicy.gamespace.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.colorModes
import com.ireddragonicy.gamespace.gamebar.getProfileIcon
import com.ireddragonicy.gamespace.gamebar.getThermalProfileColor
import com.ireddragonicy.gamespace.gamebar.rememberDrawablePainter
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.ui.viewmodel.PerAppSettingsViewModel
import androidx.compose.material.icons.rounded.Add
import com.ireddragonicy.gamespace.thermal.custom.CurveSuggestionEngine
import com.ireddragonicy.gamespace.thermal.custom.SourcePickerSheet

/**
 * Per-app "Configure Game" screen.
 *
 * Mirrors the *structure* of the in-game GamePanel (tabbed, grouped, dense,
 * same profile / display icons & colours) but wears a proper flat Material You
 * skin (dynamic colour, light + dark) instead of the overlay's neon glass.
 *
 *   - one [Card] per group, rows packed tight (no per-control cards)
 *   - ≤4 choices  -> M3 [SingleChoiceSegmentedButtonRow]
 *   - many / long -> compact [DropdownRow]
 *   - touch tune  -> 2-column [SliderCompact] grid
 */
private enum class PerAppTab(val label: String, val icon: ImageVector) {
    THERMAL("Thermal", Icons.Rounded.Thermostat),
    GFX("Graphics", Icons.Rounded.Palette),
    FRAME("Frame", Icons.Rounded.AutoAwesome),
    TOUCH("Touch", Icons.Rounded.TouchApp),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppSettingsScreen(
    viewModel: PerAppSettingsViewModel,
    onBack: () -> Unit,
    onRemoved: () -> Unit,
    onLaunchCreate: (sourceId: String?) -> Unit,
) {
    var tab by remember { mutableStateOf(PerAppTab.THERMAL) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var seedPickerOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.per_app_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Remove from Game Space",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    confirmRemove = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            GameHeader(viewModel)
            Spacer(Modifier.height(12.dp))
            TabChips(tab) { tab = it }
            Spacer(Modifier.height(4.dp))

            when (tab) {
                PerAppTab.THERMAL -> ThermalTab(viewModel, onLaunchCreate)
                PerAppTab.GFX -> GraphicsTab(viewModel)
                PerAppTab.FRAME -> FrameTab(viewModel)
                PerAppTab.TOUCH -> TouchTab(viewModel)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text("Remove ${viewModel.gameLabel}?") },
            text = {
                Text(
                    "This game will no longer receive Game Space optimisations and its " +
                        "per-game tuning will be cleared.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        viewModel.unregisterGame()
                        onRemoved()
                    },
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }

    if (seedPickerOpen) {
        SourcePickerSheet(
            // Vendor scenes need no repository (static catalog) — keeps this screen
            // free of extra Hilt wiring. "Seed from my own profile" is still one tap
            // away via the Import button inside the editor.
            sources = CurveSuggestionEngine.VENDOR_TEMPLATES,
            blankLabel = "Start blank",
            onBlank = {
                seedPickerOpen = false
                onLaunchCreate(null)
            },
            onPick = { source ->
                seedPickerOpen = false
                onLaunchCreate(source.id)
            },
            onDismiss = { seedPickerOpen = false },
        )
    }
}

// ── header ────────────────────────────────────────────────────────────────

@Composable
private fun GameHeader(vm: PerAppSettingsViewModel) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = rememberDrawablePainter(vm.gameIcon),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vm.gameLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = vm.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── tab chips (flat M3 filter-chip style, not the overlay's chamfer) ───────

@Composable
private fun TabChips(selected: PerAppTab, onSelect: (PerAppTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PerAppTab.values().forEach { tab ->
            val sel = tab == selected
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (sel) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── grouped card + caption (one card per group, rows packed inside) ────────

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
    )
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

// ── dense row primitives (M3 skin, panel-like density) ────────────────────

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun <T> SegmentedRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, pair ->
                SegmentedButton(
                    selected = pair.first == selected,
                    onClick = { onSelect(pair.first) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) {
                    Text(text = pair.second, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun <T> DropdownRow(
    label: String,
    valueText: String,
    valueColor: Color,
    options: List<T>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    leading: @Composable () -> Unit,
    renderItem: @Composable (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    // Measure the anchor so the popup can match its width. A wrap-content
    // popup anchored to a full-width row lands narrow on the LEFT (the bug
    // we're killing); matching the width makes it sit flush under the row.
    val density = LocalDensity.current
    var anchorWidthPx by remember { mutableStateOf(0) }
    val menuModifier = if (anchorWidthPx > 0) {
        Modifier.width(with(density) { anchorWidthPx.toDp() })
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchorWidthPx = it.size.width },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = { expanded = true })
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) valueColor
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = menuModifier,
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { renderItem(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                    trailingIcon = if (isSelected(opt)) {
                        {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

/** Compact slider: label + value on one line, thin track below. */
@Composable
private fun SliderCompact(
    label: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var pos by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val cur = pos.roundToInt().coerceIn(range.first, range.last)
    val steps = (range.last - range.first - 1).coerceAtLeast(0)
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = cur.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = {
                onCommit(pos.roundToInt().coerceIn(range.first, range.last))
            },
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
    }
}

// ── THERMAL tab ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThermalTab(
    vm: PerAppSettingsViewModel,
    onLaunchCreate: (sourceId: String?) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val idx = vm.thermalProfile
    val name = vm.thermalProfileOptions.firstOrNull { it.first == idx }?.second ?: "Auto"
    val color = getThermalProfileColor(idx)

    Section("THERMAL") {
        // A plain tappable row (NOT a floating menu) — long lists open a
        // bottom sheet instead, which is the correct Material pattern for
        // "pick 1 of many" and never mis-anchors to a screen corner.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSheet = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = getProfileIcon(idx, name),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Thermal profile",
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
    }

    if (showSheet) {
        ThermalProfileSheet(
            vm = vm,
            onLaunchCreate = onLaunchCreate,
            onDismiss = { showSheet = false },
        )
    }

    val disp = colorModes.firstOrNull { it.id == vm.displayStyle } ?: colorModes.first()
    Section("DISPLAY") {
        DropdownRow(
            label = "Display style",
            valueText = disp.label,
            valueColor = MaterialTheme.colorScheme.primary,
            options = colorModes,
            isSelected = { it.id == vm.displayStyle },
            onSelect = { vm.updateDisplayStyle(it.id) },
            leading = {
                Icon(
                    painter = painterResource(disp.iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            },
            renderItem = { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(m.iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = m.label, color = MaterialTheme.colorScheme.onSurface)
                }
            },
        )
    }
}

/**
 * Bottom-sheet thermal picker. Replaces the floating dropdown for the thermal
 * list because that list is long (17 built-in + unlimited custom) and a popup
 * that tall clamps to a screen corner and fights the thumb.
 *
 * UX decisions (the "overthink"):
 *  - Always anchored to the BOTTOM → predictable, thumb-friendly, never clips
 *    the status bar like the old popup did.
 *  - Search field: built-in is fixed but custom profiles grow without bound,
 *    so filtering by name is the expert move once the list gets long.
 *  - Grouping: BUILT-IN vs MY PROFILES, so a custom profile never hides among
 *    the vendor scenes.
 *  - Selected row gets a check + keeps its semantic colour.
 *  - Sticky footer "Create custom profile…" = the in-place escape hatch, so a
 *    missing profile is one tap away instead of a 6-step detour to Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThermalProfileSheet(
    vm: PerAppSettingsViewModel,
    onLaunchCreate: (sourceId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    // Re-read every time the sheet opens (it's only composed while visible),
    // so a profile created elsewhere shows up without any extra wiring.
    val all = vm.thermalProfileOptions
    val selected = vm.thermalProfile

    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val filtered = remember(all, q) {
        if (q.isEmpty()) all
        else all.filter { it.second.lowercase().contains(q) }
    }
    val builtin = filtered.filter { it.first < ThermalProfiles.CUSTOM_PROFILE_BASE }
    val custom = filtered.filter { it.first >= ThermalProfiles.CUSTOM_PROFILE_BASE }

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
            if (builtin.isEmpty() && custom.isEmpty()) {
                Text(
                    text = "No profiles match “$query”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                )
            }
            if (builtin.isNotEmpty()) {
                SheetSectionLabel("Built-in")
                builtin.forEach { (i, n) ->
                    SheetProfileRow(i, n, selected = i == selected) {
                        vm.updateThermalProfile(i)
                        hideAndThen(onDismiss)
                    }
                }
            }
            if (custom.isNotEmpty()) {
                SheetSectionLabel("My profiles")
                custom.forEach { (i, n) ->
                    SheetProfileRow(i, n, selected = i == selected) {
                        vm.updateThermalProfile(i)
                        hideAndThen(onDismiss)
                    }
                }
            }
        }

        // Sticky escape hatch — forge a profile without leaving this screen.
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { hideAndThen { onLaunchCreate(null) } }
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
    index: Int,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = getThermalProfileColor(index)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = getProfileIcon(index, name),
            contentDescription = null,
            tint = c,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
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

// ── GRAPHICS tab ──────────────────────────────────────────────────────────

private val SGSR_OPTIONS = listOf(
    0 to "Off", 1 to "SGSR1", 2 to "SGSR2", 3 to "MobFGSR",
)
private val VRS_OPTIONS = listOf(
    0 to "Off", 1 to "2×1", 2 to "2×2", 3 to "4×4",
)
private val MSAA_OPTIONS = listOf(
    0 to "Off", 2 to "2×", 4 to "4×",
)
private val AF_OPTIONS = listOf(
    0 to "Off", 2 to "2×", 4 to "4×", 8 to "8×", 16 to "16×",
)

@Composable
private fun GraphicsTab(vm: PerAppSettingsViewModel) {
    Section("GPU TUNING (HARDWARE OVERRIDES)") {
        SegmentedRow(
            label = "Anti-Aliasing (MSAA)",
            options = MSAA_OPTIONS,
            selected = vm.gpuMsaa,
            onSelect = vm::updateGpuMsaa,
        )
        RowDivider()
        SegmentedRow(
            label = "Anisotropic Filtering (AF)",
            options = AF_OPTIONS,
            selected = vm.gpuAf,
            onSelect = vm::updateGpuAf,
        )
        RowDivider()
        val texLabel = vm.gpuTexQualityOptions
            .firstOrNull { it.first == vm.gpuTexQuality }?.second ?: "Default"
        DropdownRow(
            label = "Texture Filtering Quality",
            valueText = texLabel,
            valueColor = MaterialTheme.colorScheme.primary,
            options = vm.gpuTexQualityOptions,
            isSelected = { it.first == vm.gpuTexQuality },
            onSelect = { vm.updateGpuTexQuality(it.first) },
            leading = {
                Icon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            },
            renderItem = { opt ->
                Text(text = opt.second, color = MaterialTheme.colorScheme.onSurface)
            },
        )
    }

    Section("UPSCALING") {
        SegmentedRow(
            label = "Graphics enhancement",
            options = SGSR_OPTIONS,
            selected = vm.sgsrMode,
            onSelect = vm::updateSgsrMode,
        )
    }

    val resLabel = vm.resolutionOptions
        .firstOrNull { it.first == vm.resolution }?.second ?: "Native"
    Section("RENDERING") {
        SegmentedRow(
            label = "Variable rate shading",
            options = VRS_OPTIONS,
            selected = vm.vrsLevel,
            onSelect = vm::updateVrs,
        )
        RowDivider()
        DropdownRow(
            label = "Render resolution",
            valueText = resLabel,
            valueColor = MaterialTheme.colorScheme.primary,
            options = vm.resolutionOptions,
            isSelected = { it.first == vm.resolution },
            onSelect = { vm.updateResolution(it.first) },
            leading = {
                Icon(
                    imageVector = Icons.Rounded.AspectRatio,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            },
            renderItem = { opt ->
                Text(text = opt.second, color = MaterialTheme.colorScheme.onSurface)
            },
        )
        if (vm.angleFeatureAvailable) {
            RowDivider()
            val angleLabel = vm.angleDriverOptions
                .firstOrNull { it.first == vm.angleDriverChoice }?.second ?: "Default"
            DropdownRow(
                label = "Graphics driver (ANGLE)",
                valueText = angleLabel,
                valueColor = MaterialTheme.colorScheme.primary,
                options = vm.angleDriverOptions,
                isSelected = { it.first == vm.angleDriverChoice },
                onSelect = { vm.updateAngleDriverChoice(it.first) },
                leading = {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                renderItem = { opt ->
                    Text(text = opt.second, color = MaterialTheme.colorScheme.onSurface)
                },
            )
        }
    }

    Section("COLOR & COMPOSITION") {
        SwitchRow(
            title = "Colour enhance",
            subtitle = "Boost saturation for this game",
            checked = vm.colorEnhanceEnabled,
            onChecked = vm::updateColorEnhance,
        )
        RowDivider()
        SwitchRow(
            title = "Force GPU composition",
            subtitle = "Disable HW overlays for this game",
            checked = vm.gpuComposition,
            onChecked = vm::updateGpuComposition,
        )
    }
}

// ── FRAME tab ─────────────────────────────────────────────────────────────

private val AFME_MULT_OPTIONS = listOf(
    0 to "Off", 2 to "2×", 3 to "3×", 4 to "4×",
)
private val AFME_FACTOR_OPTIONS = listOf(
    "auto" to "Auto", "0.25" to "0.25", "0.33" to "0.33", "0.5" to "0.5",
    "0.67" to "0.67", "0.75" to "0.75", "1.0" to "1.0",
)

@Composable
private fun FrameTab(vm: PerAppSettingsViewModel) {
    val multOn = vm.afmeMultiplier > 0
    val factorLabel = AFME_FACTOR_OPTIONS
        .firstOrNull { it.first == vm.afmeFactor }?.second ?: "Auto"

    Section("FRAME GENERATION (AFME)") {
        SegmentedRow(
            label = "Multiplier",
            options = AFME_MULT_OPTIONS,
            selected = vm.afmeMultiplier,
            onSelect = vm::updateAfmeMultiplier,
        )
        RowDivider()
        DropdownRow(
            label = "Extrapolation factor",
            valueText = factorLabel,
            valueColor = MaterialTheme.colorScheme.primary,
            options = AFME_FACTOR_OPTIONS,
            isSelected = { it.first == vm.afmeFactor },
            onSelect = { vm.updateAfmeFactor(it.first) },
            enabled = multOn,
            leading = {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = if (multOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
            },
            renderItem = { opt ->
                Text(text = opt.second, color = MaterialTheme.colorScheme.onSurface)
            },
        )
    }

    Section("PACING") {
        SwitchRow(
            title = "Smooth motion",
            subtitle = "Frame-pacing / interpolation",
            checked = vm.smoothMotionEnabled,
            onChecked = vm::updateSmoothMotion,
        )
    }
}

// ── TOUCH tab (sliders in a 2-column grid) ────────────────────────────────

@Composable
private fun TouchTab(vm: PerAppSettingsViewModel) {
    Section("REPORTING") {
        SwitchRow(
            title = "Super report rate",
            subtitle = "480 Hz super-resolution reporting (game mode is 240 Hz)",
            checked = vm.touchSuperReport,
            onChecked = vm::updateTouchSuperReport,
        )
        RowDivider()
        SwitchRow(
            title = "Expert mode",
            subtitle = "Use a built-in tuning preset instead of the manual values",
            checked = vm.touchExpertMode,
            onChecked = vm::updateTouchExpertMode,
        )
    }

    Section("TUNING") {
        if (vm.touchExpertMode) {
            SliderCompact(
                label = "Expert preset",
                value = vm.touchExpertPreset,
                range = 1..3,
                onCommit = vm::updateTouchExpertPreset,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    SliderCompact(
                        "Touch-up threshold", vm.touchThreshold, 0..4,
                        vm::updateTouchThreshold, Modifier.weight(1f),
                    )
                    SliderCompact(
                        "Jitter tolerance", vm.touchTolerance, 0..4,
                        vm::updateTouchTolerance, Modifier.weight(1f),
                    )
                }
                Row {
                    SliderCompact(
                        "Aim sensitivity", vm.touchAimSens, 0..4,
                        vm::updateTouchAimSens, Modifier.weight(1f),
                    )
                    SliderCompact(
                        "Tap stability", vm.touchTapStab, 0..4,
                        vm::updateTouchTapStab, Modifier.weight(1f),
                    )
                }
                Row {
                    SliderCompact(
                        "Edge filter", vm.touchEdgeFilter, 0..3,
                        vm::updateTouchEdgeFilter, Modifier.weight(1f),
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
