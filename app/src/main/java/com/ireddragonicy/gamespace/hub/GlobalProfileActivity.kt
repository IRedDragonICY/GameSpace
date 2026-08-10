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
package com.ireddragonicy.gamespace.hub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ireddragonicy.gamespace.charging.ChargingProfileListActivity
import com.ireddragonicy.gamespace.gamebar.PerfEditorStyle
import com.ireddragonicy.gamespace.gamebar.PerformanceEditor
import com.ireddragonicy.gamespace.thermal.KernelNodeIO
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.thermal.ThermalNodes
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.thermal.custom.CustomProfileEditorActivity
import com.ireddragonicy.gamespace.ui.settings.ChargingProfileRow
import com.ireddragonicy.gamespace.ui.settings.SettingsCard
import com.ireddragonicy.gamespace.ui.settings.SettingsDivider
import com.ireddragonicy.gamespace.ui.settings.SettingsLinkRow
import com.ireddragonicy.gamespace.ui.settings.SettingsSection
import com.ireddragonicy.gamespace.ui.settings.SettingsSwitchRow
import com.ireddragonicy.gamespace.ui.settings.ThermalProfileRow
import com.ireddragonicy.gamespace.ui.theme.GameSpaceTheme
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * GLOBAL PROFILE — the always-on tuning baseline, i.e. the kernel-manager view.
 *
 * Everything here applies system-wide and survives reboot, which is what makes
 * it different from the per-app editor: a game profile is scoped to its session
 * and layers *over* these values field by field (see [PerfTuner.applyGlobal]).
 * Leaving a game therefore lands back here rather than on hardware defaults.
 *
 * Every control on this screen is the same component the per-game editors use —
 * [PerformanceEditor] for frequencies, [ThermalProfileRow] for the thermal
 * scene, [ChargingProfileRow] for the charge curve — so "global" is a scope,
 * not a second dialect of the same settings.
 */
class GlobalProfileActivity : ComponentActivity() {
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GameSpaceTheme {
                GlobalProfileScreen(revision = refreshTick.intValue, onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalProfileScreen(revision: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val perfTuner = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext, ServiceViewEntryPoint::class.java
        ).perfTuner()
    }

    var profile by remember { mutableStateOf(perfTuner.loadGlobalProfile()) }

    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Global Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    text = "Applies system-wide and survives reboot. " +
                        "A per-game profile overrides these values for its session only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            item {
                SettingsSection("Frequencies")
                SettingsCard {
                    PerformanceEditor(
                        perfTuner = perfTuner,
                        profile = profile,
                        style = PerfEditorStyle.material(),
                        onCommit = {
                            profile = it
                            perfTuner.saveGlobalProfile(it)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }

            item {
                SettingsSection("Thermal")
                SettingsCard { GlobalThermalRow() }
            }

            item {
                SettingsSection("Charging")
                SettingsCard { GlobalChargingRows(revision = revision) }
            }

            item { Column(modifier = Modifier.height(24.dp)) {} }
        }
    }
}

@Composable
private fun GlobalThermalRow() {
    val context = LocalContext.current
    val resolver = context.contentResolver

    // Bumped when the editor saves, so the option list and the selection both
    // re-read; without it a profile created from the sheet would not appear
    // until the screen was reopened.
    var revision by remember { mutableIntStateOf(0) }

    // Keyed reads, not recomposition-time reads: both are ContentResolver
    // round trips and this row recomposes on every scroll frame.
    val options = remember(revision) {
        ThermalProfiles.options(
            Settings.System.getStringForUser(
                resolver, ThermalProfiles.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT
            )
        )
    }
    var selected by remember {
        mutableStateOf(
            Settings.System.getIntForUser(
                resolver, ThermalProfiles.KEY_THERMAL_PROFILE, 0, UserHandle.USER_CURRENT
            )
        )
    }

    fun apply(index: Int) {
        selected = index
        Settings.System.putIntForUser(
            resolver, ThermalProfiles.KEY_THERMAL_PROFILE, index, UserHandle.USER_CURRENT
        )
    }

    // Same "create → use in one breath" flow the per-app editor has: the editor
    // hands back the new profile's stable id, which we resolve to its array
    // index and select immediately.
    val createProfile = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val savedId = result.data
            ?.getStringExtra(CustomProfileEditorActivity.EXTRA_SAVED_ID)
            ?.takeIf { result.resultCode == Activity.RESULT_OK }
            ?: return@rememberLauncherForActivityResult
        revision++
        ThermalProfiles.parseCustomProfiles(
            Settings.System.getStringForUser(
                resolver, ThermalProfiles.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT
            )
        ).find { it.id == savedId }?.let { apply(it.index) }
    }

    ThermalProfileRow(
        options = options,
        selected = selected,
        // ThermalController observes this key and applies the scene; writing it
        // is the whole commit.
        onSelect = ::apply,
        onCreateCustom = {
            createProfile.launch(Intent(context, CustomProfileEditorActivity::class.java))
        },
    )
}

@Composable
private fun GlobalChargingRows(revision: Int = 0) {
    val context = LocalContext.current
    val chargingRepo = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext, ServiceViewEntryPoint::class.java
        ).chargingProfileRepository()
    }

    val profiles = remember(revision) { chargingRepo.loadAll() }
    var globalId by remember(revision) { mutableStateOf(chargingRepo.getGlobalProfileId()) }
    var bypass by remember {
        mutableStateOf(
            Settings.System.getIntForUser(
                context.contentResolver, ThermalProfiles.KEY_BYPASS_CHARGING, 0,
                UserHandle.USER_CURRENT,
            ) == 1
        )
    }
    val bypassSupported = remember { KernelNodeIO.exists(ThermalNodes.BYPASS_CHARGE) }

    ChargingProfileRow(
        profiles = profiles,
        selectedId = globalId,
        onSelect = { id ->
            globalId = id
            chargingRepo.setGlobalProfileId(id)
            chargingRepo.applyActiveProfile(null)
        },
    )
    SettingsDivider()
    SettingsSwitchRow(
        title = "Bypass charging",
        subtitle = if (bypassSupported)
            "Run the system from the charger and keep the battery out of the loop"
        else
            "Not supported by this kernel",
        checked = bypass,
        enabled = bypassSupported,
        onCheckedChange = { on ->
            bypass = on
            Settings.System.putIntForUser(
                context.contentResolver, ThermalProfiles.KEY_BYPASS_CHARGING,
                if (on) 1 else 0, UserHandle.USER_CURRENT,
            )
        },
    )
    SettingsDivider()
    SettingsLinkRow(
        title = "Manage curves",
        subtitle = "Create and edit the charge-current profiles offered above",
        onClick = {
            context.startActivity(Intent(context, ChargingProfileListActivity::class.java))
        },
    )
}
