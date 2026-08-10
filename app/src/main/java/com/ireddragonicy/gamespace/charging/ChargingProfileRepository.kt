/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.charging

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.ireddragonicy.gamespace.profile.ChunkedPropPublisher
import com.ireddragonicy.gamespace.profile.DaemonContract
import android.util.Log
import com.ireddragonicy.gamespace.profile.DaemonProfileRepository
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A11: thin specialization over [DaemonProfileRepository]. CRUD, JSON array
 * persistence, normalization and the chunked daemon publication all live in
 * the base. This file only provides the charging shape (SIC tiers payload),
 * hardware-band snapping, selection state, and reference cleanup.
 */
@Singleton
class ChargingProfileRepository @Inject constructor(
    @ApplicationContext context: Context,
    propPublisher: ChunkedPropPublisher,
) : DaemonProfileRepository<CustomChargingProfile>(
    context = context,
    propPublisher = propPublisher,
    storageKey = ChargingProfiles.KEY_CUSTOM_PROFILES,
    contract = DaemonContract(
        seqProp = ChargingProfiles.PROP_SEQ,
        countProp = ChargingProfiles.PROP_COUNT,
        chunkPrefix = ChargingProfiles.PROP_CHUNK_PREFIX,
        chunkSize = ChargingProfiles.PROP_CHUNK_SIZE,
        maxChunks = ChargingProfiles.PROP_CHUNK_MAX,
        modeProp = ChargingProfiles.PROP_MODE,
        globalModeValue = "global",
    ),
) {
    override val tag = "ChargingProfileRepo"

    override fun serialize(profile: CustomChargingProfile) = profile.toJson()
    override fun deserialize(json: JSONObject): CustomChargingProfile? =
        runCatching { CustomChargingProfile.fromJson(json) }.getOrNull()
    override fun daemonPayload(profile: CustomChargingProfile): String =
        profile.toDaemonJson().toString()

    override fun preCommitProps(profile: CustomChargingProfile) =
        mapOf(
            ChargingProfiles.PROP_MODE to "custom",
            ThermalProfiles.PROP_MAX_FCC to ChargingProfiles.FCC_HARD_MAX_UA.toString(),
        )

    /** Clear dangling references so we never point at a deleted profile. */
    override fun onDeleted(id: String) {
        if (getGlobalProfileId() == id) setGlobalProfileId(null)
        writeAppProfiles(readAppProfiles().filterValues { it != id })
    }

    fun validate(profile: CustomChargingProfile): List<String> {
        val errors = mutableListOf<String>()
        if (profile.name.isBlank()) errors.add("Profile name is required")
        if (profile.tiers.isEmpty()) errors.add("At least one tier is required")
        if (profile.emergencyResumeMc >= profile.emergencySuspendMc) {
            errors.add("Emergency resume temp must be below suspend temp")
        }
        profile.tiers.forEachIndexed { i, t ->
            val label = "Tier ${i + 1}"
            if (t.trigMc <= 0) errors.add("$label: trigger must be > 0")
            if (t.maxUa <= 0) errors.add("$label: max current must be > 0")
            if (t.clrMc >= t.trigMc) errors.add("$label: clear must be below trigger")
            if (t.minUa > t.maxUa) errors.add("$label: min current exceeds max")
            if (i > 0 && t.trigMc <= profile.tiers[i - 1].trigMc) {
                errors.add("$label: trigger must increase from previous tier")
            }
        }
        return errors
    }

    /**
     * Snap currents to the hardware band, repair hysteresis and order tiers by
     * trigger. Pure and idempotent — returns the corrected profile.
     */
    override fun normalize(profile: CustomChargingProfile): CustomChargingProfile {
        val tiers = profile.tiers
            .sortedBy { it.trigMc }
            .take(ChargingProfiles.MAX_TIERS)
            .map { t ->
                val maxUa = t.maxUa.coerceIn(
                    ChargingProfiles.FCC_HARD_MIN_UA, ChargingProfiles.FCC_HARD_MAX_UA
                )
                t.copy(
                    maxUa = maxUa,
                    minUa = t.minUa.coerceIn(ChargingProfiles.FCC_HARD_MIN_UA, maxUa),
                    clrMc = when {
                        t.clrMc >= t.trigMc -> t.trigMc - 1_000L
                        t.clrMc <= 0L -> t.trigMc - 2_000L
                        else -> t.clrMc
                    },
                    ks = t.ks.coerceAtLeast(0L),
                    ki = t.ki.coerceAtLeast(0L),
                    kc = t.kc.coerceAtLeast(0L),
                )
            }
        return profile.copy(
            name = profile.name.trim(),
            tiers = tiers,
            emergencyResumeMc =
                if (profile.emergencyResumeMc >= profile.emergencySuspendMc)
                    profile.emergencySuspendMc - 3_000L
                else profile.emergencyResumeMc,
        )
    }

    // ---- Selection ----

    fun getGlobalProfileId(): String? =
        Settings.System.getStringForUser(
            context.contentResolver, ChargingProfiles.KEY_GLOBAL_PROFILE, UserHandle.USER_CURRENT
        )?.takeIf { it.isNotEmpty() }

    fun setGlobalProfileId(id: String?) {
        Settings.System.putStringForUser(
            context.contentResolver, ChargingProfiles.KEY_GLOBAL_PROFILE,
            id ?: "", UserHandle.USER_CURRENT
        )
    }

    fun getAppProfileId(pkg: String): String? = readAppProfiles()[pkg]

    fun setAppProfileId(pkg: String, id: String?) {
        val map = readAppProfiles().toMutableMap()
        if (id == null) map.remove(pkg) else map[pkg] = id
        writeAppProfiles(map)
    }

    /** per-app override → global → null (= vendor GLOBAL). */
    fun resolveActiveProfileId(foregroundPkg: String?): String? {
        foregroundPkg?.let { getAppProfileId(it)?.let { id -> return id } }
        return getGlobalProfileId()
    }

    private fun readAppProfiles(): Map<String, String> {
        val raw = Settings.System.getStringForUser(
            context.contentResolver, ChargingProfiles.KEY_APP_PROFILES, UserHandle.USER_CURRENT
        ) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.getString(k) }
            m
        } catch (e: Exception) { emptyMap() }
    }

    private fun writeAppProfiles(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        Settings.System.putStringForUser(
            context.contentResolver, ChargingProfiles.KEY_APP_PROFILES,
            o.toString(), UserHandle.USER_CURRENT
        )
    }

    /** Convenience: resolve + load + apply for the current foreground app. */
    fun applyActiveProfile(foregroundPkg: String?) {
        val id = resolveActiveProfileId(foregroundPkg)
        applyToDaemon(id?.let { getById(it) })
    }

    /**
     * Wipe every persisted charging profile and the selections pointing at them.
     * Exposed for the list screen's reset action — with the daemon returning
     * to its vendor GLOBAL table, this is the clean way out of a bad profile.
     */
    fun resetAll() {
        deleteAll()
        setGlobalProfileId(null)
        writeAppProfiles(emptyMap())
        applyToDaemon(null) // hands the daemon back to the vendor GLOBAL table
        Log.i(tag, "All charging profiles reset; daemon on vendor GLOBAL")
    }
}