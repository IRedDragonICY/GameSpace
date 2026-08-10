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
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.profile.ChunkedPropPublisher
import com.ireddragonicy.gamespace.profile.DaemonContract
import com.ireddragonicy.gamespace.profile.DaemonProfileRepository
import com.ireddragonicy.gamespace.thermal.PerfTuner
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin specialization over [DaemonProfileRepository]. Serialization, CRUD and
 * chunked daemon publication live in the base; this file owns the thermal
 * shape only: snapping curves to the kernel OPP tables, the daemon payload,
 * validation, and the seed sources offered by the editor.
 */
@Singleton
class CustomProfileRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val perfTuner: PerfTuner,
    propPublisher: ChunkedPropPublisher,
) : DaemonProfileRepository<CustomThermalProfile>(
    context = context,
    propPublisher = propPublisher,
    storageKey = CustomProfileContract.KEY_CUSTOM_PROFILES,
    contract = DaemonContract(
        seqProp = CustomProfileContract.PROP_SEQ,
        countProp = CustomProfileContract.PROP_COUNT,
        chunkPrefix = CustomProfileContract.PROP_PREFIX,
        chunkSize = CustomProfileContract.PROP_CHUNK_SIZE,
        maxChunks = CustomProfileContract.PROP_CHUNK_MAX,
    ),
) {
    override val tag = "CustomProfileRepo"

    override fun serialize(profile: CustomThermalProfile) = profile.toJson()

    override fun deserialize(json: JSONObject): CustomThermalProfile? =
        runCatching { CustomThermalProfile.fromJson(json) }.getOrNull()

    override fun daemonPayload(profile: CustomThermalProfile): String =
        profile.clustersJson().toString()

    override fun preCommitProps(profile: CustomThermalProfile) = mapOf(
        CustomProfileContract.PROP_GPU to buildGpuCsv(profile),
        CustomProfileContract.PROP_MON to
            "${profile.monitor.boostMc},${profile.monitor.hotplugMc}," +
            "${profile.monitor.backlightMc},${profile.monitor.backlightCap}",
        CustomProfileContract.PROP_SIC to
            "${profile.sic.targetMc},${profile.sic.maxFccUa}",
    )

    override fun postCommitProps() =
        mapOf(CustomProfileContract.PROP_SS_PROFILE to "custom")

    private fun buildGpuCsv(profile: CustomThermalProfile): String =
        profile.gpu
            .filter { it.trigMc > 0 && it.freq > 0 }
            .take(CustomProfileContract.GPU_MAX_LEVELS)
            .joinToString(";") { "${it.trigMc},${it.clrMc},${it.freq}" }

    // ── Hardware view ───────────────────────────────────────────────────────

    /** Clusters the daemon can actually address (slot < MAX_CLUSTERS). */
    val addressableClusters: List<PerfTuner.CpuCluster>
        get() = perfTuner.cpuClusters.filter { it.slot < CustomProfileContract.MAX_CLUSTERS }

    /** Display name for a slot, from the kernel topology (never hardcoded). */
    fun clusterName(slot: Int): String =
        addressableClusters.firstOrNull { it.slot == slot }?.name ?: "CPU$slot"

    // ── Normalization ───────────────────────────────────────────────────────

    /**
     * Snap every frequency to a real kernel OPP (kHz for CPU, MHz for GPU),
     * order levels by trigger, and repair hysteresis. Pure — the caller's
     * profile is untouched.
     */
    override fun normalize(profile: CustomThermalProfile): CustomThermalProfile {
        val gpuMhz = perfTuner.gpuInfo.availableHz.map { it / 1_000_000L }
        val cpu = profile.cpu.mapNotNull { (slot, levels) ->
            if (slot !in 0 until CustomProfileContract.MAX_CLUSTERS) return@mapNotNull null
            val table = addressableClusters.firstOrNull { it.slot == slot }?.availableKhz.orEmpty()
            val clean = tidy(levels, table).take(CustomProfileContract.MAX_CPU_LEVELS)
            if (clean.isEmpty()) null else slot to clean
        }.toMap()
        return profile.copy(
            name = profile.name.trim(),
            cpu = cpu,
            gpu = tidy(profile.gpu, gpuMhz).take(CustomProfileContract.GPU_MAX_LEVELS),
        )
    }

    /**
     * Sort by trigger, snap frequencies to [table], and guarantee
     * `0 < clr < trig` on every level — the daemon repairs these too, but
     * doing it here means the UI shows what will actually run.
     */
    private fun tidy(levels: List<ThrottleLevel>, table: List<Long>): List<ThrottleLevel> =
        levels
            .filter { it.trigMc > 0 && it.freq > 0 }
            .sortedBy { it.trigMc }
            .map { lv ->
                val clr = when {
                    lv.clrMc >= lv.trigMc -> lv.trigMc - 1_000L
                    lv.clrMc <= 0L -> lv.trigMc - 2_000L
                    else -> lv.clrMc
                }
                lv.copy(clrMc = clr, freq = snap(lv.freq, table))
            }

    private fun snap(value: Long, table: List<Long>): Long =
        if (table.isEmpty()) value else table.minByOrNull { kotlin.math.abs(it - value) } ?: value

    // ── Validation ──────────────────────────────────────────────────────────

    fun validate(profile: CustomThermalProfile): List<String> {
        val errors = mutableListOf<String>()
        if (profile.name.isBlank()) errors.add("Profile name is required")

        profile.cpu.toSortedMap().forEach { (slot, levels) ->
            errors += curveErrors(clusterName(slot), levels)
        }
        errors += curveErrors("GPU", profile.gpu)
        if (profile.gpu.size > CustomProfileContract.GPU_MAX_LEVELS) {
            errors.add("GPU: at most ${CustomProfileContract.GPU_MAX_LEVELS} levels")
        }
        return errors
    }

    private fun curveErrors(label: String, levels: List<ThrottleLevel>): List<String> {
        val errors = mutableListOf<String>()
        levels.forEachIndexed { i, lv ->
            if (lv.freq <= 0) errors.add("$label level ${i + 1}: pick a frequency")
            if (lv.clrMc >= lv.trigMc) {
                errors.add("$label level ${i + 1}: clear temp must be below trigger")
            }
            if (i > 0 && lv.trigMc <= levels[i - 1].trigMc) {
                errors.add("$label level ${i + 1}: trigger must increase from previous level")
            }
        }
        return errors
    }

    // ── Seed sources ────────────────────────────────────────────────────────

    fun listImportSources(): List<ProfileSource> {
        val customs = loadAll().map { p ->
            ProfileSource.Custom(
                id = p.id,
                name = p.name.ifBlank { "(unnamed)" },
                description = "${p.totalCpuLevels} CPU · ${p.gpu.size} GPU levels",
            )
        }
        return CurveSuggestionEngine.VENDOR_TEMPLATES + customs
    }

    /** Turn a seed source into a concrete profile with [baseId]. */
    fun materialize(source: ProfileSource, baseId: String? = null): CustomThermalProfile {
        val id = baseId ?: CustomThermalProfile.newId()
        return when (source) {
            is ProfileSource.Vendor ->
                CurveSuggestionEngine.generateFromVendor(perfTuner, source, source.name, id)
            is ProfileSource.Custom ->
                getById(source.id)
                    ?.copy(id = id, name = "${source.name} (copy)")
                    ?: CustomThermalProfile(id = id, name = source.name)
        }
    }

    /**
     * Wipe every stored thermal profile. Exposed for the list screen's reset
     * action: a bad curve is far easier to escape by clearing it than by
     * hand-repairing values the UI may no longer be able to express.
     */
    fun resetAll() {
        deleteAll()
        Log.i(tag, "All thermal profiles reset")
    }

    /**
     * Re-publish whatever profile the thermal selection currently points at.
     *
     * The editor's "apply for testing" pushes an uncommitted draft straight to
     * the daemon; this is how a screen undoes that on the way out. A built-in
     * selection resolves to null, which hands the daemon back its vendor table —
     * the correct daemon-side state, since the preview only ever perturbed the
     * custom-profile properties (sconfig and friends stay with ThermalController).
     */
    fun applyActiveProfile() {
        val selection = Settings.System.getIntForUser(
            context.contentResolver, ThermalProfiles.KEY_THERMAL_PROFILE, 0,
            UserHandle.USER_CURRENT,
        )
        val index = selection - ThermalProfiles.CUSTOM_PROFILE_BASE
        applyToDaemon(if (index >= 0) loadAll().getOrNull(index) else null)
    }
}
