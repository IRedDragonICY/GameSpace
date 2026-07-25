/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2022-2026 crDroid Android Project
 * Copyright (C) 2025 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ireddragonicy.gamespace.settings

import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import dagger.hilt.android.AndroidEntryPoint
import com.ireddragonicy.gamespace.ui.viewmodel.PerAppSettingsViewModel
import androidx.activity.result.contract.ActivityResultContracts
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.thermal.custom.CustomProfileEditorActivity

/**
 * Now a flat Material You Compose activity. The body is [PerAppSettingsScreen]
 * (tabbed + grouped). The legacy [PerAppSettingsFragment] / per_app_preferences
 * XML are no longer used and may be deleted.
 */
@AndroidEntryPoint(ComponentActivity::class)
class PerAppSettingsActivity : Hilt_PerAppSettingsActivity() {

    private val viewModel: PerAppSettingsViewModel by viewModels()

    // Opens the custom-profile editor FOR RESULT. On save the editor hands back
    // the new profile's id; we resolve it to its array index and select it for
    // this game in one shot — so "create → use" is a single breath, and the
    // sheet (which re-reads on open) shows it immediately too.
    private val createProfileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val savedId = result.data?.getStringExtra(
                CustomProfileEditorActivity.EXTRA_SAVED_ID
            )
            if (savedId != null) {
                resolveCustomIndex(savedId)?.let { idx ->
                    viewModel.updateThermalProfile(
                        ThermalProfiles.CUSTOM_PROFILE_BASE + idx
                    )
                }
            }
        }
    }

    private fun resolveCustomIndex(id: String): Int? {
        val json = Settings.System.getStringForUser(
            contentResolver,
            ThermalProfiles.KEY_CUSTOM_PROFILES,
            UserHandle.USER_CURRENT,
        ) ?: return null
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).firstOrNull {
                arr.getJSONObject(it).optString("id") == id
            }
        }.getOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        viewModel.loadGame(packageName)

        setContent {
            val scheme = if (isSystemInDarkTheme()) {
                dynamicDarkColorScheme(this)
            } else {
                dynamicLightColorScheme(this)
            }
            MaterialTheme(colorScheme = scheme) {
                PerAppSettingsScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onRemoved = {
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(PREF_UNREGISTER, viewModel.packageName),
                        )
                        finish()
                    },
                    onLaunchCreate = { sourceId ->
                        createProfileLauncher.launch(
                            Intent(this@PerAppSettingsActivity, CustomProfileEditorActivity::class.java).apply {
                                if (sourceId != null) {
                                    putExtra(CustomProfileEditorActivity.EXTRA_SOURCE_ID, sourceId)
                                }
                            },
                        )
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val PREF_UNREGISTER = "per_app_unregister"
    }
}
