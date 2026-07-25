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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Custom thermal profile editor. Material You flat design, dense layout.
 *
 * Four tabs:
 *  - Smart  : wizard + one-click presets + live preview (for casual users)
 *  - CPU    : manual per-cluster curves + auto-fill (for power users)
 *  - GPU    : GPU throttle curve (max 4 levels)
 *  - Power  : monitor thresholds + SIC charging overrides
 *
 * All frequencies come from the kernel via PerfTuner. Nothing is hardcoded.
 */
@AndroidEntryPoint(ComponentActivity::class)
class CustomProfileEditorActivity : Hilt_CustomProfileEditorActivity() {

    private val viewModel: CustomProfileEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this)
            else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) {
                EditorRoot(
                    viewModel,
                    onBack = { finish() },
                    onPersisted = { id ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_SAVED_ID, id))
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_ID = "profile_id"
        const val EXTRA_SOURCE_ID = "source_id"
        /** Returned with RESULT_OK so the caller can auto-select the saved profile. */
        const val EXTRA_SAVED_ID = "saved_profile_id"
        fun start(context: Context, profileId: String? = null, sourceId: String? = null) {
            context.startActivity(Intent(context, CustomProfileEditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (profileId != null) putExtra(EXTRA_ID, profileId)
                if (sourceId != null) putExtra(EXTRA_SOURCE_ID, sourceId)
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorRoot(
    vm: CustomProfileEditorViewModel,
    onBack: () -> Unit,
    onPersisted: (savedId: String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tabs = remember { listOf("Smart", "CPU", "GPU", "Power") }

    var confirmImport by remember { mutableStateOf<ProfileSource?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // Persist helpers: only a *saved* profile has a stable array index the
    // caller can select, so "Test now" (apply-only) deliberately does NOT
    // notify — auto-selecting an unsaved profile would point at nothing.
    fun saveOnly() { if (vm.save()) onPersisted(vm.profileId) }
    fun saveApply() { if (vm.saveAndApply()) onPersisted(vm.profileId) }

    // Show status messages from the ViewModel via snackbar.
    LaunchedEffect(vm.statusMessage) {
        vm.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            vm.clearStatus()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (vm.isExisting) "Edit Profile" else "New Profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.applyNow() }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Test apply")
                    }
                    IconButton(onClick = { saveOnly() }) {
                        Icon(Icons.Rounded.Check, contentDescription = "Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Tab row (M3 segmented buttons)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                tabs.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = vm.activeTab == i,
                        onClick = { vm.activeTab = i },
                        shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                    ) { Text(label, fontSize = 12.sp) }
                }
            }

            // Scrollable body
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                NameField(vm)
                when (vm.activeTab) {
                    0 -> SmartTab(vm, onImportClick = {
                        vm.refreshSources()
                        vm.showImporter = true
                    })
                    1 -> CpuTab(vm)
                    2 -> GpuTab(vm)
                    3 -> PowerTab(vm)
                }
                ErrorList(vm)
                Spacer(Modifier.height(16.dp))
            }

            // Bottom action bar
            BottomBar(vm, onSaveApply = { saveApply() })
        }

        if (vm.showImporter) {
            SourcePickerSheet(
                sources = vm.importSources,
                blankLabel = "Clear current curves",
                onBlank = {
                    vm.showImporter = false
                    if (vm.hasCurves) confirmClear = true else vm.clearCurves()
                },
                onPick = { s ->
                    vm.showImporter = false
                    if (vm.hasCurves) confirmImport = s else vm.importFrom(s)
                },
                onDismiss = { vm.showImporter = false },
            )
        }

        confirmImport?.let { src ->
            AlertDialog(
                onDismissRequest = { confirmImport = null },
                title = { Text("Replace current curves?") },
                text = { Text("Levels on all clusters + GPU will be replaced by \"${src.name}\".") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.importFrom(src)
                        confirmImport = null
                    }) { Text("Replace") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmImport = null }) { Text("Cancel") }
                },
            )
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear all curves?") },
                text = { Text("All throttle levels on CPU clusters and GPU will be removed.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.clearCurves()
                        confirmClear = false
                    }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
                },
            )
        }
    }
}

// -- Name field --

@Composable
private fun NameField(vm: CustomProfileEditorViewModel) {
    OutlinedTextField(
        value = vm.name,
        onValueChange = { vm.name = it; vm.dirty = true },
        label = { Text("Profile name") },
        placeholder = { Text("e.g. Genshin Cool") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

// -- Smart tab --

@Composable
private fun SmartTab(vm: CustomProfileEditorViewModel, onImportClick: () -> Unit) {
    SectionLabel("Base curves")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Import from vendor or another profile",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = vm.seededFrom?.let { "Seeded from: $it" }
                        ?: "Blank — pick a preset or import",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onImportClick) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Import")
            }
        }
    }
    SectionLabel("One-click presets")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CurveSuggestionEngine.PRESETS.take(2).forEach { preset ->
            PresetCard(preset, Modifier.weight(1f)) { vm.applyPreset(preset) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CurveSuggestionEngine.PRESETS.drop(2).forEach { preset ->
            PresetCard(preset, Modifier.weight(1f)) { vm.applyPreset(preset) }
        }
    }

    SectionLabel("Custom wizard")
    GroupCard {
        val cfg = vm.wizardConfig
        WizardSlider("Target temp", "${cfg.targetTempC.roundToInt()} C", 35f..50f, cfg.targetTempC) {
            vm.wizardConfig = cfg.copy(targetTempC = it)
        }
        Text(
            "Device will be kept around this temperature",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        WizardSlider(
            "Priority",
            if (cfg.aggressiveness > 50) "Cooler" else "Faster",
            0f..100f,
            cfg.aggressiveness.toFloat(),
        ) { vm.wizardConfig = cfg.copy(aggressiveness = it.roundToInt()) }
        Text(
            "Left = relaxed throttle (higher FPS). Right = aggressive (cooler, efficient).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        WizardSlider("Freq floor", "${cfg.floorPercent}%", 20f..90f, cfg.floorPercent.toFloat()) {
            vm.wizardConfig = cfg.copy(floorPercent = it.roundToInt())
        }
        Text(
            "Minimum frequency during heaviest throttle, as % of hardware max",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.generateFromWizard() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Generate curve") }
    }

    SectionLabel("Estimated result")
    ThrottleMapCard(vm)
}

@Composable
private fun PresetCard(
    preset: CurvePreset,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                imageVector = preset.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = preset.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${preset.config.targetTempC.roundToInt()} C target",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun WizardSlider(
    label: String,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    value: Float,
    onChange: (Float) -> Unit,
) {
    var pos by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(80.dp),
        )
        Slider(
            value = pos,
            onValueChange = { pos = it; onChange(it) },
            valueRange = range,
            modifier = Modifier.weight(1f).height(28.dp),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.End,
        )
    }
}

// -- CPU tab --

@Composable
private fun CpuTab(vm: CustomProfileEditorViewModel) {
    val clusters = vm.perfTuner.cpuClusters
    if (clusters.isEmpty()) {
        Text(
            "Reading CPU clusters from kernel...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        return
    }
    SectionLabel("Per-cluster curves (manual)")
    Text(
        "Frequencies are read from this device's kernel OPP tables.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    clusters.forEach { cluster ->
        GroupCard(Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = cluster.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = vm.clusterDescription(cluster.index),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = { vm.autoFillCluster(cluster.index) }) {
                    Text("Auto", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val table = cluster.availableKhz
            vm.clusterLevels[cluster.index].forEachIndexed { i, level ->
                ThrottleLevelRow(
                    index = i,
                    level = level,
                    freqTable = table,
                    onChange = { vm.updateCpuLevel(cluster.index, i, it) },
                    onRemove = { vm.removeCpuLevel(cluster.index, i) },
                )
                Spacer(Modifier.height(6.dp))
            }
            AddLevelButton("Add level") { vm.addCpuLevel(cluster.index) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// -- GPU tab --

@Composable
private fun GpuTab(vm: CustomProfileEditorViewModel) {
    val gpu = vm.perfTuner.gpuInfo
    SectionLabel("GPU throttle (max ${CustomProfileContract.GPU_MAX_LEVELS} levels)")
    if (gpu.availableHz.isEmpty()) {
        Text(
            "GPU frequency table not available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        "Adreno, max ${formatMhz(gpu.hwMaxHz / 1_000_000L)}. Frequencies from kernel.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    GroupCard {
        val tableMhz = remember(gpu) { gpu.availableHz.map { it / 1_000_000L } }
        vm.gpuLevels.forEachIndexed { i, level ->
            ThrottleLevelRow(
                index = i,
                level = level,
                freqTable = tableMhz,
                freqIsMhz = true,
                onChange = { vm.updateGpuLevel(i, it) },
                onRemove = { vm.removeGpuLevel(i) },
            )
            Spacer(Modifier.height(6.dp))
        }
        if (vm.gpuLevels.size < CustomProfileContract.GPU_MAX_LEVELS) {
            AddLevelButton("Add GPU level") { vm.addGpuLevel() }
        }
    }
}

// -- Power tab --

@Composable
private fun PowerTab(vm: CustomProfileEditorViewModel) {
    SectionLabel("Monitor and limits")
    GroupCard {
        ValueSlider("Boost", "0 = off", (vm.monitor.boostMc / 1000).toInt(), 0..58, " C") {
            vm.monitor = vm.monitor.copy(boostMc = it * 1000L); vm.dirty = true
        }
        ValueSlider("Hotplug", "Shut cores at temp", (vm.monitor.hotplugMc / 1000).toInt(), 0..58, " C") {
            vm.monitor = vm.monitor.copy(hotplugMc = it * 1000L); vm.dirty = true
        }
        ValueSlider("Backlight", "Dim screen at temp", (vm.monitor.backlightMc / 1000).toInt(), 0..58, " C") {
            vm.monitor = vm.monitor.copy(backlightMc = it * 1000L); vm.dirty = true
        }
        ValueSlider("BL cap", "Brightness limit (0-255)", vm.monitor.backlightCap, 0..255, "") {
            vm.monitor = vm.monitor.copy(backlightCap = it); vm.dirty = true
        }
    }

    SectionLabel("Charging (SIC)")
    GroupCard {
        ValueSlider("Batt target", "0 = follow global", (vm.sic.targetMc / 1000).toInt(), 0..58, " C") {
            vm.sic = vm.sic.copy(targetMc = it * 1000L); vm.dirty = true
        }
        ValueSlider("Max charge", "0 = follow global", (vm.sic.maxFccUa / CustomProfileContract.UA_PER_WATT).toInt(), 0..90, " W") {
            vm.sic = vm.sic.copy(maxFccUa = it * CustomProfileContract.UA_PER_WATT); vm.dirty = true
        }
    }
}

// -- Error list --

@Composable
private fun ErrorList(vm: CustomProfileEditorViewModel) {
    if (vm.validationErrors.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            vm.validationErrors.forEach { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

// -- Bottom action bar --

@Composable
private fun BottomBar(vm: CustomProfileEditorViewModel, onSaveApply: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilledTonalButton(
            onClick = { vm.applyNow() },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Test now")
        }
        Button(
            onClick = onSaveApply,
            modifier = Modifier.weight(1f),
        ) {
            Text("Save and apply")
        }
    }
}
