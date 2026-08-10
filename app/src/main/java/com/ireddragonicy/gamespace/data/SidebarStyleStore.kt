/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single writer untuk style sidebar. Pola yang sama dengan [MonitorSettings]:
 * SharedPreferences sebagai sumber kebenaran + satu `mutableStateOf` observable
 * supaya setiap Composable yang membaca [current] otomatis re-compose saat style
 * berubah — preview dan strip asli tetap sinkron tanpa plumbing observer.
 */
@Singleton
class SidebarStyleStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sidebar_style", Context.MODE_PRIVATE)

    /** State observable — baca ini di composition untuk live update. */
    var current by mutableStateOf(load())
        private set

    private fun load(): SidebarStyle {
        val json = prefs.getString(KEY_STYLE, null) ?: return SidebarStyle.DEFAULT
        return runCatching { SidebarStyle.fromJson(org.json.JSONObject(json)) }
            .getOrDefault(SidebarStyle.DEFAULT)
    }

    /** Ganti style utuh (dipakai Studio saat user mengedit). */
    fun update(style: SidebarStyle) {
        current = style
        prefs.edit().putString(KEY_STYLE, style.toJson().toString()).apply()
    }

    /** Mutasi parsial — ergonomis untuk satu toggle/slider di Studio. */
    fun mutate(transform: (SidebarStyle) -> SidebarStyle) {
        update(transform(current))
    }

    fun reset() = update(SidebarStyle.DEFAULT)

    companion object {
        private const val KEY_STYLE = "style_v2"
    }
}
