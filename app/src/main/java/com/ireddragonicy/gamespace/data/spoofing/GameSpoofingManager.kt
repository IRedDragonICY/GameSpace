/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ireddragonicy.gamespace.data.spoofing

import android.content.ContentResolver
import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.ireddragonicy.gamespace.data.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class SpoofDeviceProfile(
    val id: String,
    val name: String,
    val isCustom: Boolean = false,
    val props: Map<String, String>
)

@Singleton
class GameSpoofingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "GameSpoofingManager"
        const val KEY_SPOOF_GAMEPROPS_CONFIG = "spoof_gameprops_config"
        const val KEY_CUSTOM_SPOOF_PROFILES = "custom_spoof_profiles"

        val BUILTIN_PROFILES = listOf(
            SpoofDeviceProfile(
                id = "rm11pro",
                name = "Red Magic 11 Pro",
                props = mapOf(
                    "BRAND" to "Nubia",
                    "DEVICE" to "Red Magic 11 Pro",
                    "MANUFACTURER" to "ZTE",
                    "MODEL" to "NX809J"
                )
            ),
            SpoofDeviceProfile(
                id = "rog8pro",
                name = "ROG Phone 8 Pro",
                props = mapOf(
                    "BRAND" to "asus",
                    "DEVICE" to "ROG Phone 8 Pro",
                    "MANUFACTURER" to "asus",
                    "MODEL" to "ASUS_AI2401_A"
                )
            ),
            SpoofDeviceProfile(
                id = "s24ultra",
                name = "Galaxy S24 Ultra",
                props = mapOf(
                    "BRAND" to "samsung",
                    "DEVICE" to "Galaxy S24 Ultra",
                    "MANUFACTURER" to "samsung",
                    "MODEL" to "SM-S928B"
                )
            ),
            SpoofDeviceProfile(
                id = "mi13pro",
                name = "Xiaomi 13 Pro",
                props = mapOf(
                    "BRAND" to "Xiaomi",
                    "DEVICE" to "Xiaomi 13 Pro",
                    "MANUFACTURER" to "Xiaomi",
                    "MODEL" to "2210132C"
                )
            ),
            SpoofDeviceProfile(
                id = "bs5pro",
                name = "Black Shark 5 Pro",
                props = mapOf(
                    "BRAND" to "blackshark",
                    "DEVICE" to "Black Shark 5 Pro",
                    "MANUFACTURER" to "blackshark",
                    "MODEL" to "SHARK KTUS-A0"
                )
            )
        )
    }

    private val resolver: ContentResolver get() = context.contentResolver

    /** Reads overall spoofing enabled status and per-game property mapping from Settings.Secure */
    fun readConfig(): Pair<Boolean, Map<String, Map<String, String>>> {
        val raw = try {
            Settings.Secure.getStringForUser(resolver, KEY_SPOOF_GAMEPROPS_CONFIG, UserHandle.USER_CURRENT)
        } catch (_: Exception) { null }

        if (raw.isNullOrEmpty()) return false to emptyMap()

        return try {
            val json = JSONObject(raw)
            val isEnabled = json.optBoolean("enabled", false)
            val gamesMap = mutableMapOf<String, Map<String, String>>()
            val gamesObj = json.optJSONObject("games")
            gamesObj?.keys()?.forEach { pkg ->
                val propsObj = gamesObj.getJSONObject(pkg)
                val props = mutableMapOf<String, String>()
                propsObj.keys().forEach { k -> props[k] = propsObj.getString(k) }
                gamesMap[pkg] = props
            }
            isEnabled to gamesMap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse spoof_gameprops_config", e)
            false to emptyMap()
        }
    }

    /** Writes current spoofing config back to Settings.Secure */
    fun writeConfig(enabled: Boolean, gamesMap: Map<String, Map<String, String>>) {
        try {
            val json = JSONObject()
            json.put("enabled", enabled)
            val gamesObj = JSONObject()
            gamesMap.forEach { (pkg, props) ->
                val propsObj = JSONObject()
                props.forEach { (k, v) -> propsObj.put(k, v) }
                gamesObj.put(pkg, propsObj)
            }
            json.put("games", gamesObj)

            Settings.Secure.putStringForUser(
                resolver,
                KEY_SPOOF_GAMEPROPS_CONFIG,
                json.toString(2),
                UserHandle.USER_CURRENT
            )
            Log.i(TAG, "Successfully updated spoof_gameprops_config in Settings.Secure")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write spoof_gameprops_config", e)
        }
    }

    fun isGlobalEnabled(): Boolean = readConfig().first

    fun setGlobalEnabled(enabled: Boolean) {
        val current = readConfig()
        writeConfig(enabled, current.second)
    }

    /** Returns current assigned profile for a specific game package */
    fun getProfileForGame(packageName: String): SpoofDeviceProfile? {
        val (_, gamesMap) = readConfig()
        val props = gamesMap[packageName] ?: return null

        // Try matching built-in or custom profiles
        val allProfiles = getAllProfiles()
        return allProfiles.find { it.props == props } ?: SpoofDeviceProfile(
            id = "custom_${packageName.hashCode()}",
            name = props["MODEL"] ?: "Custom Device",
            isCustom = true,
            props = props
        )
    }

    /** Set or update a device profile for a game */
    fun setProfileForGame(packageName: String, profile: SpoofDeviceProfile?) {
        val (enabled, gamesMap) = readConfig()
        val updatedMap = gamesMap.toMutableMap()

        if (profile != null) {
            updatedMap[packageName] = profile.props
        } else {
            updatedMap.remove(packageName)
        }

        writeConfig(enabled, updatedMap)
    }

    /** Remove spoofing configuration for a game */
    fun removeProfileForGame(packageName: String) {
        setProfileForGame(packageName, null)
    }

    /** Reads user-created custom profiles from Settings */
    fun getCustomProfiles(): List<SpoofDeviceProfile> {
        val raw = appSettings.getString(KEY_CUSTOM_SPOOF_PROFILES, "")
        if (raw.isEmpty()) return emptyList()

        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "custom_$i")
                val name = obj.optString("name", "Custom Profile ${i + 1}")
                val propsObj = obj.optJSONObject("props") ?: JSONObject()
                val props = mutableMapOf<String, String>()
                propsObj.keys().forEach { k -> props[k] = propsObj.getString(k) }
                SpoofDeviceProfile(id, name, isCustom = true, props = props)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse custom spoof profiles", e)
            emptyList()
        }
    }

    /** Adds or updates a user-created custom profile */
    fun saveCustomProfile(profile: SpoofDeviceProfile) {
        val list = getCustomProfiles().toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            list[index] = profile
        } else {
            list.add(profile)
        }

        val arr = JSONArray()
        list.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            val propsObj = JSONObject()
            p.props.forEach { (k, v) -> propsObj.put(k, v) }
            obj.put("props", propsObj)
            arr.put(obj)
        }
        appSettings.putString(KEY_CUSTOM_SPOOF_PROFILES, arr.toString())
    }

    /** Deletes a custom profile by ID */
    fun deleteCustomProfile(profileId: String) {
        val list = getCustomProfiles().filterNot { it.id == profileId }
        val arr = JSONArray()
        list.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            val propsObj = JSONObject()
            p.props.forEach { (k, v) -> propsObj.put(k, v) }
            obj.put("props", propsObj)
            arr.put(obj)
        }
        appSettings.putString(KEY_CUSTOM_SPOOF_PROFILES, arr.toString())
    }

    /** Returns all available profiles: Built-in + User Custom */
    fun getAllProfiles(): List<SpoofDeviceProfile> {
        return BUILTIN_PROFILES + getCustomProfiles()
    }
}
