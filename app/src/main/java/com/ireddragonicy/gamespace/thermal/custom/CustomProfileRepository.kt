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
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.ireddragonicy.gamespace.thermal.PerfTuner
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single writer for custom thermal profiles.
 *
 * Responsibilities:
 *  1. Read/write JSON to Settings.System["mithermal_custom_profiles"] (the same
 *     key ThermalController observes, so profiles are picked up automatically).
 *  2. Apply a profile directly to the Rust daemon via chunked system properties
 *     (mirrors ThermalController.applyCustomProfileByIndex) for Test/Apply Now.
 *  3. Validate and snap all frequencies to real kernel OPP tables via [PerfTuner].
 */
@Singleton
class CustomProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val perfTuner: PerfTuner,
) {
    private val resolver = context.contentResolver

    fun loadAll(): List<CustomThermalProfile> {
        val json = Settings.System.getStringForUser(
            resolver, CustomProfileContract.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT
        )
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { CustomThermalProfile.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse custom profiles", e)
            emptyList()
        }
    }

    fun getById(id: String): CustomThermalProfile? = loadAll().firstOrNull { it.id == id }

    /** Semua opsi seed: 16 vendor + profil user sendiri. */
    fun listImportSources(): List<ProfileSource> {
        val customs = loadAll().map { p ->
            ProfileSource.Custom(
                id = p.id,
                name = p.name.ifBlank { "(unnamed)" },
                description = "${p.totalCpuLevels} CPU · ${p.gpuLevels.size} GPU levels",
            )
        }
        return CurveSuggestionEngine.VENDOR_TEMPLATES + customs
    }

    /** Source → profil editable (belum di-save). Vendor = generate, Custom = deep copy. */
    fun materialize(source: ProfileSource, baseId: String? = null): CustomThermalProfile {
        val id = baseId ?: ("custom_" + System.currentTimeMillis())
        return when (source) {
            is ProfileSource.Vendor ->
                CurveSuggestionEngine.generateFromVendor(perfTuner, source, source.name, id)
            is ProfileSource.Custom -> {
                val orig = getById(source.id) ?: CustomThermalProfile(id = id, name = source.name)
                orig.deepCopy(id, orig.name + " (copy)")
            }
        }
    }

    private fun CustomThermalProfile.deepCopy(newId: String, newName: String) =
        CustomThermalProfile(
            id = newId, name = newName,
            clusters = clusters.map { lv -> lv.map { it.copy() }.toMutableList() },
            gpuLevels = gpuLevels.map { it.copy() }.toMutableList(),
            monitor = monitor.copy(),
            sic = sic.copy(),
        )

    fun save(profile: CustomThermalProfile) {
        normalize(profile)
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile else all.add(profile)
        writeAll(all)
        Log.i(TAG, "Custom profile saved: ${profile.name} (${profile.id})")
    }

    fun delete(id: String) {
        writeAll(loadAll().filter { it.id != id })
        Log.i(TAG, "Custom profile deleted: $id")
    }

    private fun writeAll(profiles: List<CustomThermalProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        Settings.System.putStringForUser(
            resolver, CustomProfileContract.KEY_CUSTOM_PROFILES,
            arr.toString(), UserHandle.USER_CURRENT
        )
    }

    /**
     * Snap every frequency to the nearest kernel-supported OPP.
     * CPU in kHz from [PerfTuner.CpuCluster.availableKhz],
     * GPU in MHz from [PerfTuner.GpuInfo.availableHz].
     */
    fun normalize(profile: CustomThermalProfile) {
        perfTuner.cpuClusters.forEach { cluster ->
            val table = cluster.availableKhz
            if (table.isEmpty()) return@forEach
            val levels = profile.clusters.getOrNull(cluster.index) ?: return@forEach
            levels.forEach { lv -> lv.freq = snap(lv.freq, table) }
        }
        val gpuMhz = perfTuner.gpuInfo.availableHz.map { it / 1_000_000L }
        if (gpuMhz.isNotEmpty()) {
            profile.gpuLevels.forEach { lv -> lv.freq = snap(lv.freq, gpuMhz) }
        }
    }

    private fun snap(value: Long, table: List<Long>): Long {
        if (table.isEmpty()) return value
        return table.minByOrNull { kotlin.math.abs(it - value) } ?: value
    }

    /**
     * Validate a profile. Returns a list of error messages (empty means valid).
     * Rules: name required, clear < trigger (hysteresis), triggers ascending
     * within each cluster.
     */
    fun validate(profile: CustomThermalProfile): List<String> {
        val errors = mutableListOf<String>()
        if (profile.name.isBlank()) errors.add("Profile name is required")

        val clusterNames = listOf("Silver", "Gold", "Gold+", "Prime")
        profile.clusters.forEachIndexed { c, levels ->
            val label = clusterNames.getOrElse(c) { "CPU$c" }
            levels.forEachIndexed { i, lv ->
                if (lv.clrMc >= lv.trigMc) {
                    errors.add("$label level ${i + 1}: clear temp must be below trigger")
                }
                if (i > 0 && lv.trigMc <= levels[i - 1].trigMc) {
                    errors.add("$label level ${i + 1}: trigger must increase from previous level")
                }
            }
        }
        profile.gpuLevels.forEachIndexed { i, lv ->
            if (lv.clrMc >= lv.trigMc) errors.add("GPU level ${i + 1}: clear temp must be below trigger")
            if (i > 0 && lv.trigMc <= profile.gpuLevels[i - 1].trigMc) {
                errors.add("GPU level ${i + 1}: trigger must increase")
            }
        }
        return errors
    }

    /**
     * Apply a profile directly to the mi_thermal_engine daemon without waiting
     * for the ContentObserver. Mirrors ThermalController.applyCustomProfileByIndex:
     *  - clusters JSON chunked at 88 bytes
     *  - GPU/MON/SIC written BEFORE the seq bump (prevents torn reads)
     *  - seq incremented to trigger daemon reload
     */
    fun applyToDaemon(profile: CustomThermalProfile) {
        normalize(profile)
        val clustersJson = profile.toJson().getJSONArray("clusters").toString()
        val chunks = clustersJson.chunked(CustomProfileContract.PROP_CHUNK_SIZE)
        if (chunks.size > CustomProfileContract.PROP_CHUNK_MAX) {
            Log.e(TAG, "Profile too large: ${clustersJson.length} bytes")
            return
        }
        chunks.forEachIndexed { i, chunk ->
            SystemProperties.set("${CustomProfileContract.PROP_PREFIX}$i", chunk)
        }
        SystemProperties.set(CustomProfileContract.PROP_GPU, buildGpuCsv(profile))
        SystemProperties.set(
            CustomProfileContract.PROP_MON,
            "${profile.monitor.boostMc},${profile.monitor.hotplugMc}," +
                    "${profile.monitor.backlightMc},${profile.monitor.backlightCap}"
        )
        SystemProperties.set(
            CustomProfileContract.PROP_SIC,
            "${profile.sic.targetMc},${profile.sic.maxFccUa}"
        )
        SystemProperties.set(CustomProfileContract.PROP_COUNT, chunks.size.toString())
        val seq = SystemProperties.getLong(CustomProfileContract.PROP_SEQ, 0L)
        SystemProperties.set(CustomProfileContract.PROP_SEQ, (seq + 1).toString())
        SystemProperties.set(CustomProfileContract.PROP_SS_PROFILE, "custom")
        Log.i(TAG, "Profile '${profile.name}' applied to daemon (${chunks.size} chunks)")
    }

    private fun buildGpuCsv(profile: CustomThermalProfile): String =
        profile.gpuLevels
            .filter { it.trigMc > 0 && it.freq > 0 }
            .joinToString(";") { "${it.trigMc},${it.clrMc},${it.freq}" }

    companion object {
        private const val TAG = "CustomProfileRepo"
    }
}
