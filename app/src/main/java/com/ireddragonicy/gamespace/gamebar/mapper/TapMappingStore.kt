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
package com.ireddragonicy.gamespace.gamebar.mapper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TapMappingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var nextId = 0

    fun load(packageName: String): List<TapMapping> {
        val json = prefs.getString(key(packageName), null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TapMapping(
                    id = o.getInt("id"),
                    keyCode = o.getInt("keyCode"),
                    xPercent = o.getDouble("xPercent").toFloat(),
                    yPercent = o.getDouble("yPercent").toFloat(),
                    label = o.optString("label", TapMapping.keyCodeToLabel(o.getInt("keyCode"))),
                )
            }
            nextId = (list.maxOfOrNull { it.id } ?: -1) + 1
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(packageName: String, mappings: List<TapMapping>) {
        val arr = JSONArray()
        mappings.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("keyCode", m.keyCode)
                put("xPercent", m.xPercent.toDouble())
                put("yPercent", m.yPercent.toDouble())
                put("label", m.label)
            })
        }
        prefs.edit().putString(key(packageName), arr.toString()).apply()
    }

    fun clear(packageName: String) {
        prefs.edit().remove(key(packageName)).apply()
    }

    fun generateId(): Int = nextId++

    private fun key(packageName: String) = "${KEY_PREFIX}$packageName"

    companion object {
        private const val PREFS_NAME = "tap_mappings"
        private const val KEY_PREFIX = "mappings_"
    }
}