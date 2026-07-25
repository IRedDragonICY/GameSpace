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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ireddragonicy.gamespace.thermal.PerfTuner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder and logic for the custom profile editor.
 *
 * All state is Compose mutable state so the UI is fully reactive. Survives
 * configuration changes because it is a ViewModel. Hardware frequency tables
 * come from [PerfTuner] (real kernel data, never hardcoded).
 */
@HiltViewModel
class CustomProfileEditorViewModel @Inject constructor(
    private val repository: CustomProfileRepository,
    val perfTuner: PerfTuner,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val profileId: String =
        savedStateHandle.get<String>("profile_id") ?: ("custom_" + System.currentTimeMillis())
    val isExisting: Boolean = savedStateHandle.get<String>("profile_id") != null
    private val sourceId: String? = savedStateHandle.get<String>("source_id")

    var name by mutableStateOf("")
    val clusterLevels = List(4) { mutableStateListOf<ThrottleLevel>() }
    val gpuLevels = mutableStateListOf<ThrottleLevel>()
    var monitor by mutableStateOf(MonitorLimits())
    var sic by mutableStateOf(SicOverrides())

    var activeTab by mutableIntStateOf(0)
    var dirty by mutableStateOf(false)

    /** Current wizard configuration for the Smart tab. */
    var wizardConfig by mutableStateOf(CurveConfig())

    val validationErrors = mutableStateListOf<String>()

    /** Set by save/apply actions; observed by the UI to show a snackbar. */
    var statusMessage by mutableStateOf<String?>(null)

    var importSources by mutableStateOf<List<ProfileSource>>(emptyList())
    var seededFrom by mutableStateOf<String?>(null)
    var showImporter by mutableStateOf(false)

    val hasCurves: Boolean
        get() = clusterLevels.any { it.isNotEmpty() } || gpuLevels.isNotEmpty()

    var hardwareReady by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                perfTuner.cpuClusters
                perfTuner.gpuInfo
            }
            hardwareReady = true
        }
        refreshSources()
        load()
    }

    fun refreshSources() {
        importSources = repository.listImportSources()
    }

    private fun loadFrom(profile: CustomThermalProfile) {
        name = profile.name
        profile.clusters.forEachIndexed { c, levels ->
            clusterLevels[c].clear()
            clusterLevels[c].addAll(levels.map { it.copy() })
        }
        gpuLevels.clear()
        gpuLevels.addAll(profile.gpuLevels.map { it.copy() })
        monitor = profile.monitor.copy()
        sic = profile.sic.copy()
    }

    private fun load() {
        val existing = repository.getById(profileId)
        if (existing != null) {
            loadFrom(existing)
            return
        }
        val source = sourceId?.let { id -> importSources.firstOrNull { it.id == id } }
        if (source != null) {
            val seeded = repository.materialize(source, baseId = profileId)
            loadFrom(seeded)
            name = seeded.name
            (source as? ProfileSource.Vendor)?.config?.let { wizardConfig = it }
            seededFrom = source.name + if (source is ProfileSource.Vendor) " (vendor)" else ""
        }
    }

    fun importFrom(source: ProfileSource) {
        val seeded = repository.materialize(source, baseId = profileId)
        seeded.clusters.forEachIndexed { c, lv ->
            clusterLevels[c].clear()
            clusterLevels[c].addAll(lv.map { it.copy() })
        }
        gpuLevels.clear()
        gpuLevels.addAll(seeded.gpuLevels.map { it.copy() })
        if (name.isBlank()) name = seeded.name
        (source as? ProfileSource.Vendor)?.config?.let { wizardConfig = it }
        seededFrom = source.name + if (source is ProfileSource.Vendor) " (vendor)" else ""
        dirty = true
        statusMessage = "Curves imported from \"${source.name}\""
    }

    fun clearCurves() {
        clusterLevels.forEach { it.clear() }
        gpuLevels.clear()
        seededFrom = null
        dirty = true
        statusMessage = "Curves cleared"
    }

    fun toProfile(): CustomThermalProfile {
        val p = CustomThermalProfile(id = profileId, name = name.trim())
        clusterLevels.forEachIndexed { c, levels ->
            p.clusters[c].clear()
            p.clusters[c].addAll(levels.map { it.copy() })
        }
        p.gpuLevels.clear()
        p.gpuLevels.addAll(gpuLevels.map { it.copy() })
        p.monitor = monitor.copy()
        p.sic = sic.copy()
        return p
    }

    // -- CPU level operations --

    fun addCpuLevel(cluster: Int) {
        val levels = clusterLevels[cluster]
        if (levels.size >= CustomProfileContract.MAX_CPU_LEVELS) return
        val table = perfTuner.cpuClusters
            .firstOrNull { it.index == cluster }?.availableKhz?.sorted() ?: return

        val newLevel = if (levels.isEmpty()) {
            ThrottleLevel(
                trigMc = 40_000L, clrMc = 38_000L,
                freq = table[table.size / 2],
            )
        } else {
            val last = levels.last()
            val lowerFreq = table.lastOrNull { it < last.freq } ?: table.first()
            ThrottleLevel(
                trigMc = last.trigMc + 2_000L,
                clrMc = last.trigMc,
                freq = lowerFreq,
            )
        }
        levels.add(newLevel)
        dirty = true
    }

    fun removeCpuLevel(cluster: Int, index: Int) {
        if (index in clusterLevels[cluster].indices) {
            clusterLevels[cluster].removeAt(index)
            dirty = true
        }
    }

    fun updateCpuLevel(cluster: Int, index: Int, level: ThrottleLevel) {
        if (index in clusterLevels[cluster].indices) {
            clusterLevels[cluster][index] = level
            dirty = true
        }
    }

    // -- GPU level operations --

    fun addGpuLevel() {
        if (gpuLevels.size >= CustomProfileContract.GPU_MAX_LEVELS) return
        val tableMhz = perfTuner.gpuInfo.availableHz.map { it / 1_000_000L }.sorted()
        if (tableMhz.isEmpty()) return
        val newLevel = if (gpuLevels.isEmpty()) {
            ThrottleLevel(45_000L, 43_000L, tableMhz[tableMhz.size / 2])
        } else {
            val last = gpuLevels.last()
            val lower = tableMhz.lastOrNull { it < last.freq } ?: tableMhz.first()
            ThrottleLevel(last.trigMc + 3_000L, last.trigMc + 1_000L, lower)
        }
        gpuLevels.add(newLevel)
        dirty = true
    }

    fun removeGpuLevel(index: Int) {
        if (index in gpuLevels.indices) {
            gpuLevels.removeAt(index)
            dirty = true
        }
    }

    fun updateGpuLevel(index: Int, level: ThrottleLevel) {
        if (index in gpuLevels.indices) {
            gpuLevels[index] = level
            dirty = true
        }
    }

    // -- Smart wizard / presets --

    fun generateFromWizard(presetName: String? = null) {
        val generated = CurveSuggestionEngine.generateFullProfile(
            perfTuner = perfTuner,
            cfg = wizardConfig,
            name = name.ifBlank { presetName ?: "Custom" },
            id = profileId,
        )
        generated.clusters.forEachIndexed { c, levels ->
            clusterLevels[c].clear()
            clusterLevels[c].addAll(levels.map { it.copy() })
        }
        gpuLevels.clear()
        gpuLevels.addAll(generated.gpuLevels.map { it.copy() })
        if (name.isBlank()) name = presetName ?: "Custom"
        dirty = true
        statusMessage = "Curve generated from wizard settings"
    }

    fun applyPreset(preset: CurvePreset) {
        wizardConfig = preset.config
        generateFromWizard(preset.name)
        statusMessage = "Preset \"${preset.name}\" applied"
    }

    fun autoFillCluster(cluster: Int) {
        val c = perfTuner.cpuClusters.firstOrNull { it.index == cluster } ?: return
        val curve = CurveSuggestionEngine.suggestCpuCurve(c, wizardConfig)
        clusterLevels[cluster].clear()
        clusterLevels[cluster].addAll(curve.map { it.copy() })
        dirty = true
    }

    // -- Validation and persistence --

    fun validate(): List<String> {
        val errors = repository.validate(toProfile())
        validationErrors.clear()
        validationErrors.addAll(errors)
        return errors
    }

    fun save(): Boolean {
        if (validate().isNotEmpty()) {
            statusMessage = "Fix validation errors before saving"
            return false
        }
        repository.save(toProfile())
        dirty = false
        statusMessage = "Profile saved"
        return true
    }

    fun saveAndApply(): Boolean {
        if (!save()) return false
        repository.applyToDaemon(toProfile())
        statusMessage = "Profile saved and applied"
        return true
    }

    fun applyNow(): Boolean {
        if (validate().isNotEmpty()) {
            statusMessage = "Fix validation errors before applying"
            return false
        }
        repository.applyToDaemon(toProfile())
        statusMessage = "Profile applied for testing"
        return true
    }

    fun clusterDescription(cluster: Int): String {
        val c = perfTuner.cpuClusters.firstOrNull { it.index == cluster } ?: return ""
        return CurveSuggestionEngine.describeCluster(c, clusterLevels[cluster])
    }

    fun clearStatus() {
        statusMessage = null
    }
}
