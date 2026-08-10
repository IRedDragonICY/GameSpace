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
 * State holder for the custom profile editor.
 *
 * The whole editable document is ONE immutable [draft]. Every edit is a
 * `copy`, which means:
 *  - Compose gets exactly one observable to track instead of five state lists
 *    that could disagree with each other,
 *  - `toProfile()` is free (no defensive deep copy),
 *  - and nothing can hand the repository a list it still mutates afterwards.
 *
 * CPU curves are addressed by **daemon slot**, always taken from
 * [PerfTuner.CpuCluster.slot]. The kernel policy number is never used as an
 * index here — that mismatch was the CPU-tab crash.
 */
@HiltViewModel
class CustomProfileEditorViewModel @Inject constructor(
    private val repository: CustomProfileRepository,
    val perfTuner: PerfTuner,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val requestedId: String? = savedStateHandle.get<String>(EXTRA_ID)
    private val sourceId: String? = savedStateHandle.get<String>(EXTRA_SOURCE_ID)

    val profileId: String = requestedId ?: CustomThermalProfile.newId()
    val isExisting: Boolean = requestedId != null

    /** The single source of truth for everything being edited. */
    var draft by mutableStateOf(CustomThermalProfile(id = profileId))
        private set

    var activeTab by mutableIntStateOf(0)
    var dirty by mutableStateOf(false)
        private set

    /** True while the daemon is running an uncommitted draft from [applyNow]. */
    var previewApplied by mutableStateOf(false)
        private set

    var wizardConfig by mutableStateOf(CurveConfig())
    var validationErrors by mutableStateOf<List<String>>(emptyList())
        private set

    /** Set by save/apply actions; observed by the UI to show a snackbar. */
    var statusMessage by mutableStateOf<String?>(null)

    var importSources by mutableStateOf<List<ProfileSource>>(emptyList())
        private set
    var seededFrom by mutableStateOf<String?>(null)
        private set
    var showImporter by mutableStateOf(false)

    /**
     * Kernel OPP tables are read lazily off the main thread; until this flips
     * the CPU/GPU tabs show a loading state rather than an empty one, so an
     * unprobed device is never mistaken for a device with no clusters.
     */
    var hardwareReady by mutableStateOf(false)
        private set

    /** Clusters the daemon can address, in slot order. */
    val clusters: List<PerfTuner.CpuCluster>
        get() = repository.addressableClusters

    val name: String get() = draft.name
    val hasCurves: Boolean get() = draft.hasCurves

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

    private fun load() {
        repository.getById(profileId)?.let { draft = it; return }
        val source = sourceId?.let { id -> importSources.firstOrNull { it.id == id } } ?: return
        draft = repository.materialize(source, baseId = profileId)
        (source as? ProfileSource.Vendor)?.config?.let { wizardConfig = it }
        seededFrom = source.name + if (source is ProfileSource.Vendor) " (vendor)" else ""
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    private fun edit(block: (CustomThermalProfile) -> CustomThermalProfile) {
        draft = block(draft)
        dirty = true
    }

    fun setName(value: String) = edit { it.copy(name = value) }
    fun setMonitor(value: MonitorLimits) = edit { it.copy(monitor = value) }
    fun setSic(value: SicOverrides) = edit { it.copy(sic = value) }

    fun importFrom(source: ProfileSource) {
        val seeded = repository.materialize(source, baseId = profileId)
        edit {
            it.copy(
                name = it.name.ifBlank { seeded.name },
                cpu = seeded.cpu,
                gpu = seeded.gpu,
            )
        }
        (source as? ProfileSource.Vendor)?.config?.let { wizardConfig = it }
        seededFrom = source.name + if (source is ProfileSource.Vendor) " (vendor)" else ""
        statusMessage = "Curves imported from \"${source.name}\""
    }

    fun clearCurves() {
        edit { it.copy(cpu = emptyMap(), gpu = emptyList()) }
        seededFrom = null
        statusMessage = "Curves cleared"
    }

    // ── CPU levels (slot-addressed) ─────────────────────────────────────────

    fun addCpuLevel(slot: Int) {
        val table = clusters.firstOrNull { it.slot == slot }?.availableKhz?.sorted().orEmpty()
        if (table.isEmpty()) return
        edit { p ->
            p.mapCpu(slot) { levels ->
                if (levels.size >= CustomProfileContract.MAX_CPU_LEVELS) return@mapCpu levels
                levels + nextLevel(levels, table, tempStepMc = 2_000L, firstTrigMc = 40_000L)
            }
        }
    }

    fun removeCpuLevel(slot: Int, index: Int) =
        edit { p -> p.mapCpu(slot) { it.removeIndex(index) } }

    fun updateCpuLevel(slot: Int, index: Int, level: ThrottleLevel) =
        edit { p -> p.mapCpu(slot) { it.replaceIndex(index, level) } }

    fun autoFillCluster(slot: Int) {
        val cluster = clusters.firstOrNull { it.slot == slot } ?: return
        edit { it.withCpu(slot, CurveSuggestionEngine.suggestCpuCurve(cluster, wizardConfig)) }
    }

    fun clusterDescription(slot: Int): String {
        val cluster = clusters.firstOrNull { it.slot == slot } ?: return ""
        return CurveSuggestionEngine.describeCluster(cluster, draft.cpuAt(slot))
    }

    // ── GPU levels ──────────────────────────────────────────────────────────

    val gpuTableMhz: List<Long>
        get() = perfTuner.gpuInfo.availableHz.map { it / 1_000_000L }.sorted()

    fun addGpuLevel() {
        val table = gpuTableMhz
        if (table.isEmpty()) return
        edit { p ->
            if (p.gpu.size >= CustomProfileContract.GPU_MAX_LEVELS) return@edit p
            p.copy(gpu = p.gpu + nextLevel(p.gpu, table, tempStepMc = 3_000L, firstTrigMc = 45_000L))
        }
    }

    fun removeGpuLevel(index: Int) = edit { it.copy(gpu = it.gpu.removeIndex(index)) }

    fun updateGpuLevel(index: Int, level: ThrottleLevel) =
        edit { it.copy(gpu = it.gpu.replaceIndex(index, level)) }

    /**
     * Next step in a curve: one hysteresis band hotter than the last level and
     * one OPP slower, so an added level is always valid without further edits.
     */
    private fun nextLevel(
        levels: List<ThrottleLevel>,
        table: List<Long>,
        tempStepMc: Long,
        firstTrigMc: Long,
    ): ThrottleLevel {
        val last = levels.lastOrNull()
            ?: return ThrottleLevel(
                trigMc = firstTrigMc,
                clrMc = firstTrigMc - 2_000L,
                freq = table[table.size / 2],
            )
        return ThrottleLevel(
            trigMc = last.trigMc + tempStepMc,
            clrMc = last.trigMc,
            freq = table.lastOrNull { it < last.freq } ?: table.first(),
        )
    }

    // ── Wizard / presets ────────────────────────────────────────────────────

    fun generateFromWizard(presetName: String? = null) {
        val generated = CurveSuggestionEngine.generateFullProfile(
            perfTuner = perfTuner,
            cfg = wizardConfig,
            name = draft.name.ifBlank { presetName ?: "Custom" },
            id = profileId,
        )
        edit {
            it.copy(
                name = it.name.ifBlank { presetName ?: "Custom" },
                cpu = generated.cpu,
                gpu = generated.gpu,
            )
        }
        statusMessage = "Curve generated from wizard settings"
    }

    fun applyPreset(preset: CurvePreset) {
        wizardConfig = preset.config
        generateFromWizard(preset.name)
        statusMessage = "Preset \"${preset.name}\" applied"
    }

    // ── Validation and persistence ──────────────────────────────────────────

    private fun validate(): List<String> =
        repository.validate(draft).also { validationErrors = it }

    fun save(): Boolean {
        if (validate().isNotEmpty()) {
            statusMessage = "Fix validation errors before saving"
            return false
        }
        repository.save(draft)
        dirty = false
        statusMessage = "Profile saved"
        return true
    }

    fun saveAndApply(): Boolean {
        if (!save()) return false
        repository.applyToDaemon(draft)
        previewApplied = false
        statusMessage = "Profile saved and applied"
        return true
    }

    fun applyNow(): Boolean {
        if (validate().isNotEmpty()) {
            statusMessage = "Fix validation errors before applying"
            return false
        }
        repository.applyToDaemon(draft)
        previewApplied = true
        statusMessage = "Profile applied for testing"
        return true
    }

    /**
     * Hand the daemon back the profile that is actually selected.
     *
     * [applyNow] pushes an uncommitted draft on purpose so the user can feel the
     * curve, but the daemon reads `persist.` properties — leaving the editor
     * without this strands that draft as the live throttle table permanently,
     * across reboots, while the selected profile never runs.
     */
    fun revertPreview() {
        if (!previewApplied) return
        previewApplied = false
        repository.applyActiveProfile()
    }

    fun clearStatus() {
        statusMessage = null
    }

    companion object {
        const val EXTRA_ID = "profile_id"
        const val EXTRA_SOURCE_ID = "source_id"
    }
}

// ── Small immutable list helpers (out-of-range is a no-op, never a crash) ────

private fun List<ThrottleLevel>.removeIndex(index: Int): List<ThrottleLevel> =
    if (index !in indices) this else filterIndexed { i, _ -> i != index }

private fun List<ThrottleLevel>.replaceIndex(index: Int, value: ThrottleLevel): List<ThrottleLevel> =
    if (index !in indices) this else mapIndexed { i, old -> if (i == index) value else old }
