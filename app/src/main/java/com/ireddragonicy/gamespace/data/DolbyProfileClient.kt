/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Thin IPC client for per-app Dolby profile in LunarisDolby.
 *
 * SSOT: all data resides in LunarisDolby's [AppProfileManager]
 * (SharedPreferences "app_profiles"). This client does NOT store local state,
 * has NO cache, and has NO internal DB. Every call = single IPC round-trip
 * to [DolbyAppProfileProvider] via [android.content.ContentResolver.call].
 *
 * Complexity: O(1) per operation. No polling, no observer.
 *
 * Graceful degradation: if LunarisDolby is not installed or the provider
 * is unavailable, all methods return default (profile = -1, empty map).
 * GameSpace functions normally without per-app Dolby.
 *
 * Profile names must be in sync with LunarisDolby's R.array.dolby_profile_entries.
 * If LunarisDolby adds a new profile, add it to [PROFILE_NAMES] here.
 */
object DolbyProfileClient {

    private const val TAG = "DolbyProfileClient"
    private const val AUTHORITY = "org.lunaris.dolby.app_profiles"
    private val URI: Uri = Uri.parse("content://$AUTHORITY")

    // ── Method names (must match DolbyAppProfileProvider) ──
    private const val METHOD_GET_PROFILE = "get_profile"
    private const val METHOD_SET_PROFILE = "set_profile"
    private const val METHOD_REMOVE_PROFILE = "remove_profile"
    private const val METHOD_GET_ALL = "get_all"

    // ── Bundle keys (must match DolbyAppProfileProvider) ──
    private const val KEY_PROFILE = "profile"
    private const val KEY_SUCCESS = "success"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_PROFILES = "profiles"

    /**
     * Profile names — mirrored from LunarisDolby R.array.dolby_profile_entries.
     * Index = profile value (0..6).
     */
    val PROFILE_NAMES = arrayOf(
        "Dynamic", "Movie", "Music", "Game", "Work", "Casual", "Mood"
    )

    val PROFILE_COUNT: Int get() = PROFILE_NAMES.size

    /** -1 = "follow global default" (no per-app override). */
    const val PROFILE_DEFAULT = -1

    /**
     * Fetch per-app Dolby profile for [pkg].
     * @return profile index (0..6), or [PROFILE_DEFAULT] if missing or error.
     */
    fun getProfile(context: Context, pkg: String): Int {
        return try {
            val result = context.contentResolver.call(
                URI, METHOD_GET_PROFILE, pkg, null
            )
            result?.getInt(KEY_PROFILE, PROFILE_DEFAULT) ?: PROFILE_DEFAULT
        } catch (e: Exception) {
            Log.w(TAG, "getProfile($pkg) failed", e)
            PROFILE_DEFAULT
        }
    }

    /**
     * Set per-app Dolby profile for [pkg].
     * @param profile 0..6, or [PROFILE_DEFAULT] to remove override.
     */
    fun setProfile(context: Context, pkg: String, profile: Int) {
        try {
            val extras = Bundle().apply { putInt(KEY_PROFILE, profile) }
            context.contentResolver.call(
                URI, METHOD_SET_PROFILE, pkg, extras
            )
        } catch (e: Exception) {
            Log.w(TAG, "setProfile($pkg, $profile) failed", e)
        }
    }

    /** Remove per-app Dolby profile for [pkg]. */
    fun removeProfile(context: Context, pkg: String) {
        try {
            context.contentResolver.call(
                URI, METHOD_REMOVE_PROFILE, pkg, null
            )
        } catch (e: Exception) {
            Log.w(TAG, "removeProfile($pkg) failed", e)
        }
    }

    /**
     * Fetch all per-app profiles as Map<packageName, profileIndex>.
     * Used by Game Hub to display "Dolby: Game" badges on game cards.
     */
    fun getAllProfiles(context: Context): Map<String, Int> {
        return try {
            val result = context.contentResolver.call(
                URI, METHOD_GET_ALL, null, null
            ) ?: return emptyMap()
            val packages = result.getStringArray(KEY_PACKAGES) ?: return emptyMap()
            val profiles = result.getIntArray(KEY_PROFILES) ?: return emptyMap()
            if (packages.size != profiles.size) return emptyMap()
            packages.zip(profiles.toList()).toMap()
        } catch (e: Exception) {
            Log.w(TAG, "getAllProfiles() failed", e)
            emptyMap()
        }
    }

    /** Display name for profile index. Safe for out-of-range indices. */
    fun profileName(profile: Int): String =
        PROFILE_NAMES.getOrElse(profile) { "Unknown" }
}
