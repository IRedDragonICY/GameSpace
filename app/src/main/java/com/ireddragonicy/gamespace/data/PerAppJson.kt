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
package com.ireddragonicy.gamespace.data

import org.json.JSONObject

/**
 * Per-app setting maps stored as one JSON object per Settings.System key:
 * `{"com.example.game": <value>, ...}`.
 *
 * Pure string-level logic, no Android dependencies — unit-tested. Writing a
 * null value removes the entry (= revert to default), matching the historical
 * behavior of every per-app key in this app. Corrupt input is treated as an
 * empty map rather than thrown.
 */
object PerAppJson {

    private fun parse(json: String?): JSONObject = try {
        JSONObject(json ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    /** Returns the updated JSON document; null [value] removes the entry. */
    fun put(json: String?, pkg: String, value: Any?): String {
        val obj = parse(json)
        if (value == null) obj.remove(pkg) else obj.put(pkg, value)
        return obj.toString()
    }

    fun getString(json: String?, pkg: String, default: String): String =
        parse(json).optString(pkg, default).ifEmpty { default }

    fun getInt(json: String?, pkg: String, default: Int): Int =
        parse(json).optInt(pkg, default)

    fun getBoolean(json: String?, pkg: String, default: Boolean): Boolean =
        parse(json).optBoolean(pkg, default)

    fun has(json: String?, pkg: String): Boolean = parse(json).has(pkg)
}
