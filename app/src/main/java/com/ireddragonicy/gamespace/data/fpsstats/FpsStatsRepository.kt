/*
 * Copyright (C) 2026 IRedDragonICY
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ireddragonicy.gamespace.data.fpsstats

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for storing and retrieving FPS Stats sessions.
 * Uses JSON files stored in app-internal storage.
 *
 * Storage layout:
 *   files/fpsstats/session_[uuid].json  — full session data
 *   SharedPreferences "fpsstats_index"  — lightweight session list
 *
 * IMPORTANT: Deserialization uses manual JsonDeserializers instead of
 * Gson reflection + TypeToken. This is required because R8 strips generic
 * Signature attributes from classes, causing Gson to produce LinkedTreeMap
 * instead of typed objects for List<T> fields. Manual parsing is R8-safe.
 */
@Singleton
class FpsStatsRepository @Inject constructor(
    private val context: Context,
) {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(FpsStatsSession::class.java, FpsStatsSessionDeserializer())
        .create()

    private val storageDir: File
        get() = File(context.filesDir, "fpsstats").also { it.mkdirs() }

    private val indexPrefs by lazy {
        context.getSharedPreferences("fpsstats_index", Context.MODE_PRIVATE)
    }

    /**
     * Save a completed recording session to storage.
     */
    fun saveSession(session: FpsStatsSession) {
        // Write full session JSON — serialization is fine, only deserialization has R8 issues
        val plainGson = Gson()
        val file = File(storageDir, "session_${session.id}.json")
        file.writeText(plainGson.toJson(session))

        // Update index
        val item = SessionListItem(
            id = session.id,
            packageName = session.packageName,
            appName = session.appName,
            startTimeMs = session.startTimeMs,
            durationSec = session.durationSec,
            fpsAvg = session.summary.fpsAvg,
            powerAvgW = session.summary.powerAvgW,
        )
        val currentIndex = loadIndex().toMutableList()
        currentIndex.add(0, item) // Prepend (newest first)
        saveIndex(currentIndex)
    }

    /**
     * Load a full session by ID.
     */
    fun loadSession(sessionId: String): FpsStatsSession? {
        val file = File(storageDir, "session_$sessionId.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), FpsStatsSession::class.java)
        } catch (e: Exception) {
            android.util.Log.e("FpsStatsRepo", "Failed to load session $sessionId", e)
            // Delete corrupted session
            file.delete()
            val idx = loadIndex().toMutableList()
            idx.removeAll { it.id == sessionId }
            saveIndex(idx)
            null
        }
    }

    /**
     * List all sessions (lightweight, no sample data).
     */
    fun listSessions(): List<SessionListItem> = loadIndex()

    /**
     * Delete a session by ID.
     */
    fun deleteSession(sessionId: String) {
        File(storageDir, "session_$sessionId.json").delete()
        val currentIndex = loadIndex().toMutableList()
        currentIndex.removeAll { it.id == sessionId }
        saveIndex(currentIndex)
    }

    /**
     * Delete all sessions.
     */
    fun deleteAllSessions() {
        storageDir.listFiles()?.forEach { it.delete() }
        saveIndex(emptyList())
    }

    /**
     * Export session as JSON string (for sharing).
     */
    fun exportSession(sessionId: String): String? {
        val file = File(storageDir, "session_$sessionId.json")
        return if (file.exists()) file.readText() else null
    }

    /**
     * Get total storage used by FPS Stats (in bytes).
     */
    fun getStorageUsed(): Long {
        return storageDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private var plainGson: Gson = Gson()

    private fun loadIndex(): List<SessionListItem> {
        val json = indexPrefs.getString("sessions", null) ?: return emptyList()
        return try {
            plainGson.fromJson(json, Array<SessionListItem>::class.java).toList()
        } catch (_: Exception) { emptyList() }
    }

    private fun saveIndex(items: List<SessionListItem>) {
        indexPrefs.edit().putString("sessions", plainGson.toJson(items)).apply()
    }
}

/**
 * R8-safe manual deserializer for FpsStatsSession.
 *
 * Why: R8 strips generic Signature attributes. Gson relies on these to know
 * that `samples` is `List<PerformanceSample>` vs `List<?>`. Without Signature,
 * Gson creates LinkedTreeMap objects instead of typed data classes → ClassCastException.
 *
 * This deserializer manually parses each field, avoiding all reflection on generics.
 */
private class FpsStatsSessionDeserializer : JsonDeserializer<FpsStatsSession> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): FpsStatsSession {
        val obj = json.asJsonObject

        return FpsStatsSession(
            id = obj.str("id"),
            packageName = obj.str("packageName"),
            appName = obj.str("appName"),
            startTimeMs = obj.long("startTimeMs"),
            endTimeMs = obj.long("endTimeMs"),
            durationSec = obj.int("durationSec"),
            platform = obj.str("platform"),
            model = obj.str("model"),
            osVersion = obj.str("osVersion"),
            thermalProfile = obj.str("thermalProfile"),
            samples = obj.getAsJsonArray("samples")?.map { parseSample(it.asJsonObject) } ?: emptyList(),
            threadSnapshots = obj.getAsJsonArray("threadSnapshots")?.map { parseThreadSnapshot(it.asJsonObject) } ?: emptyList(),
            summary = parseSummary(obj.getAsJsonObject("summary") ?: JsonObject()),
        )
    }

    private fun parseSample(o: JsonObject): PerformanceSample {
        return PerformanceSample(
            timestampMs = o.long("timestampMs"),
            fps = o.float("fps"),
            frameTimeMs = o.float("frameTimeMs"),
            cpuUsageTotal = o.float("cpuUsageTotal"),
            cpuUsagePerCluster = o.floatList("cpuUsagePerCluster"),
            cpuFreqPerCluster = o.intList("cpuFreqPerCluster"),
            gpuFreqMHz = o.int("gpuFreqMHz"),
            gpuLoadPercent = o.float("gpuLoadPercent"),
            cpuTempC = o.float("cpuTempC"),
            gpuTempC = o.float("gpuTempC"),
            batteryTempC = o.float("batteryTempC"),
            batteryPowerW = o.float("batteryPowerW"),
            batteryCapacity = o.int("batteryCapacity"),
            ddrTempC = o.float("ddrTempC"),
        )
    }

    private fun parseThreadSnapshot(o: JsonObject): ThreadSnapshot {
        return ThreadSnapshot(
            timestampMs = o.long("timestampMs"),
            threads = o.getAsJsonArray("threads")?.map { parseThreadInfo(it.asJsonObject) } ?: emptyList(),
        )
    }

    private fun parseThreadInfo(o: JsonObject): ThreadInfo {
        return ThreadInfo(
            tid = o.int("tid"),
            name = o.str("name"),
            cpuPercent = o.float("cpuPercent"),
        )
    }

    private fun parseSummary(o: JsonObject): SessionSummary {
        return SessionSummary(
            fpsMax = o.float("fpsMax"),
            fpsMin = o.float("fpsMin"),
            fpsAvg = o.float("fpsAvg"),
            fpsVariance = o.float("fpsVariance"),
            smoothnessPercent = o.float("smoothnessPercent"),
            fpsLow5Percent = o.float("fpsLow5Percent"),
            jankCount = o.int("jankCount"),
            bigJankCount = o.int("bigJankCount"),
            frameTimeMaxMs = o.float("frameTimeMaxMs"),
            tempMaxC = o.float("tempMaxC"),
            tempMinC = o.float("tempMinC"),
            tempAvgC = o.float("tempAvgC"),
            powerMaxW = o.float("powerMaxW"),
            powerMinW = o.float("powerMinW"),
            powerAvgW = o.float("powerAvgW"),
        )
    }

    // Safe field accessors — never crash on missing/null fields
    private fun JsonObject.str(key: String): String = get(key)?.asString ?: ""
    private fun JsonObject.int(key: String): Int = get(key)?.asInt ?: 0
    private fun JsonObject.long(key: String): Long = get(key)?.asLong ?: 0L
    private fun JsonObject.float(key: String): Float = get(key)?.asFloat ?: 0f

    private fun JsonObject.floatList(key: String): List<Float> {
        val arr = getAsJsonArray(key) ?: return emptyList()
        return arr.map { it.asFloat }
    }

    private fun JsonObject.intList(key: String): List<Int> {
        val arr = getAsJsonArray(key) ?: return emptyList()
        return arr.map { it.asInt }
    }
}
