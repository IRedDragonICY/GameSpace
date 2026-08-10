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
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.colorModes
import com.ireddragonicy.gamespace.utils.rememberDrawablePainter
import com.ireddragonicy.gamespace.ui.settings.*
import com.ireddragonicy.gamespace.ui.viewmodel.PerAppSettingsViewModel
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import com.ireddragonicy.gamespace.thermal.custom.CurveSuggestionEngine
import com.ireddragonicy.gamespace.thermal.custom.SourcePickerSheet

import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import com.ireddragonicy.gamespace.data.DolbyProfileClient

/**
 * Per-app "Configure Game" screen.
 *
 * Mirrors the *structure* of the in-game GamePanel (tabbed, grouped, dense,
 * same profile / display icons & colours) but wears a proper flat Material You
 * skin (dynamic colour, light + dark) instead of the overlay's neon glass.
 *
 *   - one [com.ireddragonicy.gamespace.ui.settings.SettingsCard] per group,
 *     rows packed tight (no per-control cards)
 *   - ≤4 choices  -> [com.ireddragonicy.gamespace.ui.settings.SettingsSegmentedRow]
 *   - many / long -> [com.ireddragonicy.gamespace.ui.settings.SettingsDropdownRow]
 *   - touch tuning -> capability-aware controls backed by verified kernel paths
 */
import androidx.compose.material.icons.rounded.PhonelinkSetup
import androidx.compose.material.icons.rounded.Smartphone

import androidx.compose.material.icons.rounded.Bolt

private enum class PerAppTab(val label: String, val icon: ImageVector) {
    THERMAL("Thermal", Icons.Rounded.Thermostat),
    CHARGING("Charging", Icons.Rounded.Bolt),
    GFX("Graphics", Icons.Rounded.Palette),
    SPOOF("Spoofing", Icons.Rounded.PhonelinkSetup),
    FRAME("Frame", Icons.Rounded.AutoAwesome),
    TOUCH("Touch", Icons.Rounded.TouchApp),
    SOUND("Sound", Icons.Rounded.GraphicEq),
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
                PerAppTab.CHARGING -> ChargingSettingsTab(viewModel)
                PerAppTab.GFX -> GraphicsTab(viewModel)
                PerAppTab.SPOOF -> SpoofingTab(viewModel)
                PerAppTab.FRAME -> FrameTab(viewModel)
                PerAppTab.TOUCH -> TouchTab(viewModel)
                PerAppTab.SOUND -> SoundSettingsTab(viewModel)
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

// ── THERMAL tab ───────────────────────────────────────────────────────────

@Composable
private fun ThermalTab(
    vm: PerAppSettingsViewModel,
    onLaunchCreate: (sourceId: String?) -> Unit,
) {
    SettingsSection("THERMAL")
    SettingsCard {
        ThermalProfileRow(
            options = vm.thermalProfileOptions,
            selected = vm.thermalProfile,
            onSelect = vm::updateThermalProfile,
            onCreateCustom = { onLaunchCreate(null) },
        )
    }

    val disp = colorModes.firstOrNull { it.id == vm.displayStyle } ?: colorModes.first()
    SettingsSection("DISPLAY")
    SettingsCard {
        SettingsDropdownRow(
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
    SettingsSection("GPU TUNING (HARDWARE OVERRIDES)")
    SettingsCard {
        SettingsSegmentedRow(
            label = "Anti-Aliasing (MSAA)",
            options = MSAA_OPTIONS,
            selected = vm.gpuMsaa,
            onSelect = vm::updateGpuMsaa,
        )
        SettingsDivider()
        SettingsSegmentedRow(
            label = "Anisotropic Filtering (AF)",
            options = AF_OPTIONS,
            selected = vm.gpuAf,
            onSelect = vm::updateGpuAf,
        )
        SettingsDivider()
        val texLabel = vm.gpuTexQualityOptions
            .firstOrNull { it.first == vm.gpuTexQuality }?.second ?: "Default"
        SettingsDropdownRow(
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

    SettingsSection("UPSCALING")
    SettingsCard {
        SettingsSegmentedRow(
            label = "Graphics enhancement",
            options = SGSR_OPTIONS,
            selected = vm.sgsrMode,
            onSelect = vm::updateSgsrMode,
        )
    }

    val resLabel = vm.resolutionOptions
        .firstOrNull { it.first == vm.resolution }?.second ?: "Native"
    SettingsSection("RENDERING")
    SettingsCard {
        SettingsSegmentedRow(
            label = "Variable rate shading",
            options = VRS_OPTIONS,
            selected = vm.vrsLevel,
            onSelect = vm::updateVrs,
        )
        SettingsDivider()
        SettingsDropdownRow(
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
            SettingsDivider()
            val angleLabel = vm.angleDriverOptions
                .firstOrNull { it.first == vm.angleDriverChoice }?.second ?: "Default"
            SettingsDropdownRow(
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

    SettingsSection("COLOR & COMPOSITION")
    SettingsCard {
        SettingsSwitchRow(
            title = "Colour enhance",
            subtitle = "Boost saturation for this game",
            checked = vm.colorEnhanceEnabled,
            onCheckedChange = vm::updateColorEnhance,
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "Force GPU composition",
            subtitle = "Disable HW overlays for this game",
            checked = vm.gpuComposition,
            onCheckedChange = vm::updateGpuComposition,
        )
    }
}

// ── FRAME tab ─────────────────────────────────────────────────────────────

private val AFME_MULT_OPTIONS = listOf(
    2 to "2×", 3 to "3×", 4 to "4×",
)
private val AFME_FACTOR_OPTIONS = listOf(
    "auto" to "Auto", "0.25" to "0.25", "0.33" to "0.33", "0.5" to "0.5",
    "0.67" to "0.67", "0.75" to "0.75", "1.0" to "1.0",
)

@Composable
private fun FrameTab(vm: PerAppSettingsViewModel) {
    val multOn = vm.afmeEnabled
    val factorLabel = AFME_FACTOR_OPTIONS
        .firstOrNull { it.first == vm.afmeFactor }?.second ?: "Auto"

    SettingsSection("FRAME GENERATION (AFME)")
    SettingsCard {
        SettingsSwitchRow(
            title = "Frame generation",
            subtitle = "Generate intermediate frames via AFME",
            checked = vm.afmeEnabled,
            onCheckedChange = vm::updateAfmeEnabled,
        )
        SettingsDivider()
        SettingsSegmentedRow(
            label = "Multiplier",
            options = AFME_MULT_OPTIONS,
            selected = vm.afmeMultiplier,
            onSelect = vm::updateAfmeMultiplier,
        )
        SettingsDivider()
        SettingsDropdownRow(
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

    SettingsSection("PACING")
    SettingsCard {
        SettingsSwitchRow(
            title = "Smooth motion",
            subtitle = "Frame-pacing / interpolation",
            checked = vm.smoothMotionEnabled,
            onCheckedChange = vm::updateSmoothMotion,
        )
    }
}

// ── TOUCH tab ─────────────────────────────────────────────────────────────

private val TOUCH_FILTER_LEVELS = listOf(
    0 to "Off",
    1 to "Low",
    2 to "Medium",
    3 to "High",
)

/**
 * Report rate stays on the HAL-backed path. Edge/grip controls appear only
 * when the accompanying OSS kernel ABI is present; that ABI filters the final
 * coordinate stream before it becomes an Android input event.
 */
@Composable
private fun TouchTab(vm: PerAppSettingsViewModel) {
    SettingsSection("REPORTING")
    SettingsCard {
        SettingsSwitchRow(
            title = "Super report rate",
            subtitle = "Raises the panel scan rate to 240 Hz for this game",
            checked = vm.touchSuperReport,
            onCheckedChange = vm::updateTouchSuperReport,
        )
    }

    SettingsSection("ACCIDENTAL TOUCH PROTECTION")
    if (!vm.touchAccidentalFilterAvailable) {
        SettingsCard {
            Text(
                text = "The OSS GameSpace touch-filter kernel is not active. " +
                    "No edge or grip setting is exposed until its real backend is available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
        return
    }

    SettingsCard {
        SettingsSegmentedRow(
            label = "Edge filter",
            options = TOUCH_FILTER_LEVELS,
            selected = vm.touchEdgeFilter,
            onSelect = vm::updateTouchEdgeFilter,
        )
        SettingsDivider()
        SettingsSegmentedRow(
            label = "Grip suppression",
            options = TOUCH_FILTER_LEVELS,
            selected = vm.touchGripSuppression,
            onSelect = vm::updateTouchGripSuppression,
        )
    }
    Text(
        text = "Applied when this game starts. Only contacts that begin in a " +
            "protected edge or corner are rejected; an active touch is never cut off.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ── NEW COMPOSABLE: SoundSettingsTab ──
@Composable
private fun SoundSettingsTab(vm: PerAppSettingsViewModel) {
    if (!vm.dolbyAvailable) {
        SettingsSection("DOLBY ATMOS")
        SettingsCard {
            Text(
                text = "LunarisDolby is not installed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    SettingsSection("DOLBY PER-GAME PROFILE")
    SettingsCard {
        val options = remember {
            listOf(DolbyProfileClient.PROFILE_DEFAULT) + (0 until DolbyProfileClient.PROFILE_COUNT).toList()
        }

        val currentValueText = if (vm.dolbyProfile >= 0)
            DolbyProfileClient.profileName(vm.dolbyProfile)
        else
            "Follow Global"

        SettingsDropdownRow(
            label = "Dolby Profile",
            valueText = currentValueText,
            valueColor = if (vm.dolbyProfile >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            options = options,
            isSelected = { it == vm.dolbyProfile },
            onSelect = { vm.updateDolbyProfile(it) },
            leading = {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            renderItem = { profileVal ->
                val name = if (profileVal >= 0) DolbyProfileClient.profileName(profileVal) else "Follow Global"
                Text(
                    text = name,
                    color = if (profileVal == vm.dolbyProfile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (profileVal == vm.dolbyProfile) FontWeight.Bold else FontWeight.Normal,
                )
            },
        )
    }

    Spacer(Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Profile saved in LunarisDolby (SSOT). Auto-applied on game launch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SpoofingTab(vm: PerAppSettingsViewModel) {
    var showCreateDialog by remember { mutableStateOf(false) }

    SettingsSection("MASTER SPOOFING TOGGLE")
    SettingsCard {
        SettingsSwitchRow(
            title = "Enable Game Device Spoofing",
            subtitle = "Spoof device model properties for games to unlock 90/120 FPS & max graphics settings",
            checked = vm.gameSpoofingGlobalEnabled,
            onCheckedChange = vm::updateGlobalSpoofingEnabled,
        )
    }

    SettingsSection("DEVICE PROFILE FOR ${vm.gameLabel.uppercase()}")
    SettingsCard {
        val current = vm.currentSpoofProfile
        val allProfiles = vm.allSpoofProfiles

        Column(modifier = Modifier.fillMaxWidth()) {
            // Disabled Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.updateGameSpoofProfile(null) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = (current == null),
                    onClick = { vm.updateGameSpoofProfile(null) }
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Disabled (Use Real Device Properties)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (current == null) FontWeight.Bold else FontWeight.Normal,
                        color = if (current == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Default system build properties will be reported to this game",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SettingsDivider()

            allProfiles.forEach { profile ->
                val isSelected = (current?.id == profile.id || current?.props == profile.props)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.updateGameSpoofProfile(profile) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { vm.updateGameSpoofProfile(profile) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            if (profile.isCustom) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        text = "CUSTOM",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                        val modelStr = profile.props["MODEL"] ?: ""
                        val mfrStr = profile.props["MANUFACTURER"] ?: ""
                        val brandStr = profile.props["BRAND"] ?: ""
                        Text(
                            text = "MODEL: $modelStr | MFR: $mfrStr | BRAND: $brandStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (profile.isCustom) {
                        IconButton(onClick = { vm.deleteCustomSpoofProfile(profile.id) }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Delete Profile",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                SettingsDivider()
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    androidx.compose.material3.OutlinedButton(
        onClick = { showCreateDialog = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Create Custom Device Profile")
    }

    if (showCreateDialog) {
        CreateCustomSpoofDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { name, brand, device, manufacturer, model ->
                vm.saveCustomSpoofProfile(name, brand, device, manufacturer, model)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun CreateCustomSpoofDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, brand: String, device: String, manufacturer: String, model: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var manufacturer by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Custom Device Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name (e.g. RedMagic Custom)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text("BRAND (e.g. Nubia)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = device,
                    onValueChange = { device = it },
                    label = { Text("DEVICE (e.g. Red Magic 11 Pro)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = manufacturer,
                    onValueChange = { manufacturer = it },
                    label = { Text("MANUFACTURER (e.g. ZTE)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("MODEL (e.g. NX809J)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    if (name.isNotBlank() && model.isNotBlank()) {
                        onSave(name, brand, device, manufacturer, model)
                    }
                },
                enabled = name.isNotBlank() && model.isNotBlank()
            ) {
                Text("Save Profile")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ChargingSettingsTab(vm: PerAppSettingsViewModel) {
    SettingsSection("CHARGING")
    SettingsCard {
        ChargingProfileRow(
            profiles = vm.chargingProfiles,
            selectedId = vm.chargingProfileId,
            onSelect = vm::updateChargingProfile,
            inheritLabel = "Follow global",
        )
    }
    Text(
        text = "Curves are created in Game Space → Settings → Charging profiles.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
