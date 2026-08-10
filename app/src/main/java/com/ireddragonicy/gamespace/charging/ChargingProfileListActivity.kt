/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.charging

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ireddragonicy.gamespace.ui.theme.GameSpaceTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint(ComponentActivity::class)
class ChargingProfileListActivity : Hilt_ChargingProfileListActivity() {

    @Inject lateinit var repository: ChargingProfileRepository
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GameSpaceTheme {
                ListRoot(revision = refreshTick.intValue, onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ListRoot(revision: Int, onBack: () -> Unit) {
        val context = this
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var refresh by remember { mutableIntStateOf(0) }
        val profiles = remember(revision, refresh) { repository.loadAll() }
        val globalId = remember(revision, refresh) { repository.getGlobalProfileId() }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Charging Profiles", fontWeight = FontWeight.SemiBold)
                            Text("${profiles.size} saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { startActivity(editorIntent(context, null, null)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) { Icon(Icons.Rounded.Add, "Create") }
            },
        ) { pad ->
            if (profiles.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Bolt, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No charging profiles yet",
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap + and pick a preset to start",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(profiles, key = { it.id }) { p ->
                        val isActive = p.id == globalId
                        ProfileRow(
                            profile = p,
                            isActive = isActive,
                            onEdit = { startActivity(editorIntent(context, p.id, null)) },
                            onActivate = {
                                repository.setGlobalProfileId(p.id)
                                repository.applyToDaemon(p)
                                refresh++
                                scope.launch { snackbar.showSnackbar("\"${p.name}\" is now active") }
                            },
                            onDelete = {
                                repository.delete(p.id)
                                refresh++
                            },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    @Composable
    private fun ProfileRow(
        profile: CustomChargingProfile,
        isActive: Boolean,
        onEdit: () -> Unit,
        onActivate: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val maxWatt = profile.tiers.maxOfOrNull { it.maxUa }
            ?.let { ChargingProfiles.uaToWatt(it) } ?: 0f
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isActive)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            onClick = onEdit,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.name.ifBlank { "(unnamed)" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        if (isActive) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.Check, "Active",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${profile.tiers.size} tiers · up to ${"%.0f".format(maxWatt)}W · suspend ${"%.0f".format(ChargingProfiles.mcToC(profile.emergencySuspendMc))}°C",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onActivate, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Bolt, "Activate",
                        tint = if (isActive) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Delete, "Delete",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ChargingProfileListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun editorIntent(context: Context, profileId: String?, sourceId: String?) =
            Intent(context, ChargingProfileEditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                profileId?.let { putExtra("profile_id", it) }
                sourceId?.let { putExtra("source_id", it) }
            }
    }
}
