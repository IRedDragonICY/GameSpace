/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.charging

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ChargingProfileEditorViewModel @Inject constructor(
    val repository: ChargingProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val profileId: String =
        savedStateHandle.get<String>("profile_id") ?: CustomChargingProfile.newId()
    val isExisting: Boolean = savedStateHandle.get<String>("profile_id") != null
    private val sourceId: String? = savedStateHandle.get<String>("source_id")

    var name by mutableStateOf("")
    val tiers = mutableStateListOf<ChargingTier>()
    var suspendMc by mutableLongStateOf(48_000L)
    var resumeMc by mutableLongStateOf(45_000L)

    var dirty by mutableStateOf(false)
    /** True while the daemon is running an uncommitted draft from [applyNow]. */
    var previewApplied by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
    var seededFrom by mutableStateOf<String?>(null)
    val validationErrors = mutableStateListOf<String>()

    // wizard config (live)
    var wizardConfig by mutableStateOf(ChargingConfig())

    init {
        refreshAndLoad()
    }

    private fun refreshAndLoad() {
        val existing = repository.getById(profileId)
        if (existing != null) {
            loadFrom(existing)
            return
        }
        val source = sourceId?.let { id ->
            ChargingSuggestionEngine.PRESETS.firstOrNull { it.id == id }
                ?: ChargingSuggestionEngine.VENDOR_TEMPLATES.firstOrNull { it.id == id }
                ?: repository.getById(id)?.let { p ->
                    ChargingSource.Vendor(p.id, p.name, "copy", p.tiers.toList())
                }
        }
        if (source != null) {
            importFrom(source)
        } else {
            tiers.add(ChargingTier())
        }
    }

    private fun loadFrom(p: CustomChargingProfile) {
        name = p.name
        suspendMc = p.emergencySuspendMc
        resumeMc = p.emergencyResumeMc
        tiers.clear()
        tiers.addAll(p.tiers)
    }

    fun importFrom(source: ChargingSource) {
        when (source) {
            is ChargingSource.Preset -> {
                wizardConfig = source.config
                val gen = ChargingSuggestionEngine.generateProfile(source.config, source.name, profileId)
                loadFrom(gen)
                if (name.isBlank()) name = source.name
            }
            is ChargingSource.Vendor -> {
                tiers.clear()
                tiers.addAll(source.tiers)
                if (name.isBlank()) name = source.name
            }
        }
        seededFrom = source.name
        dirty = true
        statusMessage = "Imported from \"${source.name}\""
    }

    fun addTier() {
        if (tiers.size >= ChargingProfiles.MAX_TIERS) return
        val last = tiers.lastOrNull()
        tiers.add(
            last?.let {
                ChargingTier(
                    trigMc = it.trigMc + 2000, clrMc = it.trigMc,
                    targetMc = it.trigMc + 1000,
                    maxUa = (it.maxUa * 0.6f).toLong().coerceAtLeast(ChargingProfiles.FCC_HARD_MIN_UA),
                    minUa = (it.minUa * 0.6f).toLong().coerceAtLeast(ChargingProfiles.FCC_HARD_MIN_UA),
                    ks = it.ks, ki = it.ki, kc = it.kc,
                )
            } ?: ChargingTier()
        )
        dirty = true
    }

    fun removeTier(index: Int) {
        if (index in tiers.indices) { tiers.removeAt(index); dirty = true }
    }

    fun updateTier(index: Int, tier: ChargingTier) {
        if (index in tiers.indices) { tiers[index] = tier; dirty = true }
    }

    fun applyPreset(preset: ChargingSource.Preset) {
        importFrom(preset)
    }

    fun generateFromWizard() {
        val gen = ChargingSuggestionEngine.generateProfile(
            wizardConfig, name.ifBlank { "Custom" }, profileId
        )
        tiers.clear(); tiers.addAll(gen.tiers)
        suspendMc = gen.emergencySuspendMc
        resumeMc = gen.emergencyResumeMc
        dirty = true
        statusMessage = "Tiers generated from wizard"
    }

    fun toProfile() = CustomChargingProfile(
        id = profileId, name = name.trim(),
        tiers = tiers.toList(),
        emergencySuspendMc = suspendMc, emergencyResumeMc = resumeMc,
    )

    fun validate(): List<String> {
        val errs = repository.validate(toProfile())
        validationErrors.clear(); validationErrors.addAll(errs)
        return errs
    }

    fun save(): Boolean {
        if (validate().isNotEmpty()) { statusMessage = "Fix validation errors first"; return false }
        repository.save(toProfile())
        dirty = false
        statusMessage = "Profile saved"
        return true
    }

    fun saveAndApply(): Boolean {
        if (!save()) return false
        repository.setGlobalProfileId(profileId)
        repository.applyToDaemon(toProfile())
        previewApplied = false // this draft IS the selection now
        statusMessage = "Saved & set as active"
        return true
    }

    fun applyNow(): Boolean {
        if (validate().isNotEmpty()) { statusMessage = "Fix validation errors first"; return false }
        repository.applyToDaemon(toProfile())
        previewApplied = true
        statusMessage = "Applied for testing"
        return true
    }

    /**
     * Hand the daemon back the profile that is actually selected.
     *
     * [applyNow] pushes an uncommitted draft on purpose so the user can feel the
     * curve, but the daemon's input is `persist.` properties — leaving the editor
     * without this strands that draft as the live charging table permanently,
     * across reboots, while the profile the user selected never runs. Plain
     * [save] deliberately does not clear the flag: saving a profile is not
     * activating it.
     */
    fun revertPreview() {
        if (!previewApplied) return
        previewApplied = false
        repository.applyActiveProfile(null)
    }

    fun clearStatus() { statusMessage = null }
}
