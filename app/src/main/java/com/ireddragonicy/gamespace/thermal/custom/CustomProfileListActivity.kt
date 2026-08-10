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
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.ui.theme.GameSpaceTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lists every user-created custom thermal profile — the entry point for the
 * whole feature. Tap a card to edit, bolt to make it the active profile,
 * copy to fork it, trash to delete. The FAB opens the seed picker, then the
 * editor.
 */
@AndroidEntryPoint(ComponentActivity::class)
class CustomProfileListActivity : Hilt_CustomProfileListActivity() {

    @Inject lateinit var repository: CustomProfileRepository

    /**
     * Bumped on every resume. The editor is a separate activity, so without
     * this the list still shows the pre-edit snapshot when the user comes back.
     */
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GameSpaceTheme {
                ListRoot(repository, refreshTick.intValue, onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }

    companion object {
        /**
         * Open the profile *list*. It used to launch the editor directly, so
         * "Custom thermal profiles" in settings skipped the list entirely and
         * dropped the user into a blank new-profile form with no way to see
         * or manage what they had already saved.
         */
        fun start(context: Context) {
            context.startActivity(
                Intent(context, CustomProfileListActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * Which stored profile the thermal controller is currently running.
 *
 * The selection is an index into the stored array offset by
 * [CustomProfileContract.CUSTOM_PROFILE_BASE] — that is the contract
 * ThermalController reads, so writing this setting (rather than only pushing
 * props) is what makes an activation survive the next `applyAll()`.
 */
private fun activeArrayIndex(context: Context): Int =
    Settings.System.getIntForUser(
        context.contentResolver, ThermalProfiles.KEY_THERMAL_PROFILE, 0, UserHandle.USER_CURRENT
    ) - CustomProfileContract.CUSTOM_PROFILE_BASE

private fun setActiveArrayIndex(context: Context, arrayIndex: Int) {
    Settings.System.putIntForUser(
        context.contentResolver,
        ThermalProfiles.KEY_THERMAL_PROFILE,
        CustomProfileContract.CUSTOM_PROFILE_BASE + arrayIndex,
        UserHandle.USER_CURRENT,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListRoot(
    repository: CustomProfileRepository,
    refreshTick: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var localRefresh by remember { mutableIntStateOf(0) }
    val revision = refreshTick + localRefresh

    val profiles = remember(revision) { repository.loadAll() }
    val sources = remember(revision) { repository.listImportSources() }
    val activeIndex = remember(revision) { activeArrayIndex(context) }

    var pickerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<CustomThermalProfile?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Custom Thermal Profiles",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${profiles.size} saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (profiles.isNotEmpty()) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete all profiles") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                },
                                onClick = { menuOpen = false; confirmResetAll = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { pickerOpen = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) { Icon(Icons.Rounded.Add, contentDescription = "Create profile") }
        },
    ) { pad ->
        if (profiles.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(pad))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                itemsIndexed(profiles) { index, profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = index == activeIndex,
                        onEdit = {
                            CustomProfileEditorActivity.start(context, profileId = profile.id)
                        },
                        onActivate = {
                            setActiveArrayIndex(context, index)
                            repository.applyToDaemon(profile)
                            localRefresh++
                            scope.launch {
                                snackbarHostState.showSnackbar("\"${profile.name}\" is now active")
                            }
                        },
                        onDuplicate = {
                            CustomProfileEditorActivity.start(context, sourceId = profile.id)
                        },
                        onDelete = { confirmDelete = profile },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        if (pickerOpen) {
            SourcePickerSheet(
                sources = sources,
                blankLabel = "Start blank",
                onBlank = {
                    pickerOpen = false
                    CustomProfileEditorActivity.start(context)
                },
                onPick = { s ->
                    pickerOpen = false
                    CustomProfileEditorActivity.start(context, sourceId = s.id)
                },
                onDismiss = { pickerOpen = false },
            )
        }

        confirmDelete?.let { target ->
            ConfirmDialog(
                title = "Delete \"${target.name.ifBlank { "(unnamed)" }}\"?",
                body = "This profile will be removed permanently.",
                confirmLabel = "Delete",
                onConfirm = {
                    repository.delete(target.id)
                    confirmDelete = null
                    localRefresh++
                },
                onDismiss = { confirmDelete = null },
            )
        }

        if (confirmResetAll) {
            ConfirmDialog(
                title = "Delete all profiles?",
                body = "All ${profiles.size} custom thermal profiles will be removed and " +
                    "thermal control returns to the built-in profiles.",
                confirmLabel = "Delete all",
                onConfirm = {
                    repository.resetAll()
                    Settings.System.putIntForUser(
                        context.contentResolver, ThermalProfiles.KEY_THERMAL_PROFILE, 0,
                        UserHandle.USER_CURRENT,
                    )
                    confirmResetAll = false
                    localRefresh++
                    scope.launch { snackbarHostState.showSnackbar("All profiles deleted") }
                },
                onDismiss = { confirmResetAll = false },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Thermostat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "No custom profiles yet",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap + and pick a one-click preset to get started",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: CustomThermalProfile,
    isActive: Boolean,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onEdit,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name.ifBlank { "(unnamed)" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = profile.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onActivate, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Bolt,
                    contentDescription = "Set active",
                    tint = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "Duplicate",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** One-line description: what this profile actually does, not just its size. */
private fun CustomThermalProfile.summary(): String {
    if (!hasCurves) return "No throttle levels — unrestricted"
    val onset = (cpu.values.flatten() + gpu).minOfOrNull { it.trigMc }
    val onsetText = onset?.let { " · from ${"%.0f".format(it.mcToC())}°C" }.orEmpty()
    return "$totalCpuLevels CPU · ${gpu.size} GPU levels$onsetText"
}
