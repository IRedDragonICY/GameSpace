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
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.ireddragonicy.gamespace.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.DockSort
import com.ireddragonicy.gamespace.data.DockSource
import com.ireddragonicy.gamespace.gamebar.SidebarMode
import com.ireddragonicy.gamespace.data.model.AppRef
import com.ireddragonicy.gamespace.utils.rememberDrawablePainter
import com.ireddragonicy.gamespace.utils.rememberInstalledAppsRepository
import com.ireddragonicy.gamespace.ui.settings.*
import com.ireddragonicy.gamespace.ui.theme.GameSpaceTheme
import kotlinx.coroutines.launch

/**
 * Everything that decides *whether* a sidebar appears and *which flavour* it is.
 *
 * These all live in `Settings.System`, not SharedPreferences: the reader is
 * `GameListManager` inside system_server, which cannot see this app's private prefs.
 * That is why the toggles here write Settings.System directly instead of
 * SharedPreferences like the hub ones.
 *
 * Per-app *video toolbox* values (frame gen target, upscale, colour) deliberately do
 * NOT live here — they are edited in the toolbox itself, against the app you are
 * watching, which is the only place the choice has context.
 */
class SidebarSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GameSpaceTheme {
                SidebarSettingsScreen(onBack = { finish() })
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SidebarSettingsActivity::class.java))
        }
    }
}

// Keys mirrored from GameListManager. Kept as literals on both sides on purpose:
// the framework class is not on this app's compile classpath as a constant holder,
// and Settings keys are a wire format — renaming one without the other must fail
// loudly at runtime, not be silently papered over by a shared symbol.
private const val KEY_ENABLED = "sidebar_enabled"
private const val KEY_MODE_GAME = "sidebar_mode_game"
private const val KEY_MODE_VIDEO = "sidebar_mode_video"
private const val KEY_MODE_PLAIN = "sidebar_mode_plain"
private const val KEY_VIDEO_LIST = "sidebar_video_list"
private const val KEY_EXCLUDED = "sidebar_excluded_list"

/** Tri-state override for a package in [KEY_VIDEO_LIST]. */
private enum class VideoOverride { AUTO, FORCE_ON, FORCE_OFF }

@Composable
private fun SidebarSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var enabled by remember { mutableStateOf(getFlag(context, KEY_ENABLED, true)) }
    var showVideoApps by remember { mutableStateOf(false) }
    var showExcluded by remember { mutableStateOf(false) }

    val appSettings = remember { AppSettings(context.applicationContext) }
    var dockMax by remember { mutableIntStateOf(appSettings.dockMaxApps) }
    var dockSort by remember { mutableStateOf(appSettings.dockSort) }
    var dockSource by remember { mutableStateOf(appSettings.dockSource) }
    var autoFill by remember { mutableStateOf(appSettings.dockAutoFill) }
    var refreshOnOpen by remember { mutableStateOf(appSettings.dockRefreshOnOpen) }
    var resetTick by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Sidebar", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                SettingsSwitchRow(
                    title = "Sidebar",
                    subtitle = "Floating panel over the app you are using",
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        putFlag(context, KEY_ENABLED, it)
                    },
                )
            }

            item { SettingsSection("Show it in") }
            item {
                var gameMode by remember { mutableStateOf(getFlag(context, KEY_MODE_GAME, true)) }
                SettingsSwitchRow(
                    title = "Games",
                    subtitle = "Full game panel — tiles, thermal, FPS, tuner",
                    checked = gameMode,
                    onCheckedChange = {
                        gameMode = it
                        putFlag(context, KEY_MODE_GAME, it)
                    },
                    enabled = enabled,
                )
            }
            item {
                var videoMode by remember { mutableStateOf(getFlag(context, KEY_MODE_VIDEO, true)) }
                SettingsSwitchRow(
                    title = "Video players",
                    subtitle = "Video toolbox — frame gen, upscale, picture, Dolby",
                    checked = videoMode,
                    onCheckedChange = {
                        videoMode = it
                        putFlag(context, KEY_MODE_VIDEO, it)
                    },
                    enabled = enabled,
                )
            }
            item {
                var plainMode by remember { mutableStateOf(getFlag(context, KEY_MODE_PLAIN, false)) }
                SettingsSwitchRow(
                    title = "Other apps",
                    subtitle = "App strip only, no panel",
                    checked = plainMode,
                    onCheckedChange = {
                        plainMode = it
                        putFlag(context, KEY_MODE_PLAIN, it)
                    },
                    enabled = enabled,
                )
            }

            item { SettingsSection("App strip") }
            item {
                SettingsIntSliderRow(
                    label = "Number of apps",
                    value = dockMax,
                    range = 5..30,
                    onCommit = {
                        dockMax = it
                        appSettings.dockMaxApps = it
                    },
                    valueText = { "$it" },
                )
            }
            item {
                SettingsDropdownRow(
                    label = "App source",
                    valueText = dockSource.label,
                    options = DockSource.values().toList(),
                    isSelected = { it == dockSource },
                    onSelect = {
                        dockSource = it
                        appSettings.dockSource = it
                    },
                    renderItem = { s ->
                        Text(
                            text = s.label,
                            fontWeight = if (s == dockSource) FontWeight.Bold else FontWeight.Normal,
                            color = if (s == dockSource) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                )
            }
            item {
                SettingsDropdownRow(
                    label = "Sort free slots by",
                    valueText = dockSort.label,
                    options = DockSort.values().toList(),
                    isSelected = { it == dockSort },
                    onSelect = {
                        dockSort = it
                        appSettings.dockSort = it
                    },
                    renderItem = { s ->
                        Text(
                            text = s.label,
                            fontWeight = if (s == dockSort) FontWeight.Bold else FontWeight.Normal,
                            color = if (s == dockSort) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                )
            }
            item {
                SettingsSwitchRow(
                    title = "Auto-fill free slots",
                    subtitle = "Fill slots you haven't pinned with suggestions",
                    checked = autoFill,
                    onCheckedChange = {
                        autoFill = it
                        appSettings.dockAutoFill = it
                    },
                )
            }
            item {
                SettingsSwitchRow(
                    title = "Re-sort when the panel opens",
                    subtitle = "Pinned apps stay put — only free slots are re-rolled",
                    checked = refreshOnOpen,
                    onCheckedChange = {
                        refreshOnOpen = it
                        appSettings.dockRefreshOnOpen = it
                    },
                )
            }
            item {
                val hiddenTotal = remember(resetTick) {
                    listOf(SidebarMode.MODE_GAME, SidebarMode.MODE_VIDEO, SidebarMode.MODE_PLAIN)
                        .sumOf { appSettings.getDockHidden(it).size }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            listOf(SidebarMode.MODE_GAME, SidebarMode.MODE_VIDEO, SidebarMode.MODE_PLAIN)
                                .forEach { m ->
                                    appSettings.setDockPinned(m, emptyList())
                                    appSettings.setDockHidden(m, emptySet())
                                }
                            resetTick++
                            scope.launch { snackbar.showSnackbar("Dock reset — hidden apps restored") }
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Reset dock",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (hiddenTotal > 0)
                                "Clear pins and unhide $hiddenTotal hidden app(s)"
                            else "Clear pins and rebuild the strip from scratch",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SettingsSection("Customization") }
            item {
                SettingsLinkRow(
                    title = "Sidebar Studio",
                    subtitle = "Customize rail orientation, shapes, icon styles, and colors",
                    enabled = enabled,
                    onClick = {
                        com.ireddragonicy.gamespace.settings.sidebarstudio.SidebarStudioActivity.start(context)
                    }
                )
            }

            item { SettingsSection("Per-app") }
            item {
                SettingsLinkRow(
                    title = "Video apps",
                    subtitle = "Force the video toolbox on or off for specific apps",
                    enabled = enabled,
                    onClick = { showVideoApps = true }
                )
            }
            item {
                SettingsLinkRow(
                    title = "Excluded apps",
                    subtitle = "Apps that never get a sidebar",
                    enabled = enabled,
                    onClick = { showExcluded = true }
                )
            }

            item {
                Text(
                    text = "Video players are detected automatically while they play. " +
                        "Use the list above only when detection gets an app wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }

    if (showVideoApps) {
        VideoAppsSheet(onDismiss = { showVideoApps = false })
    }
    if (showExcluded) {
        ExcludedAppsSheet(onDismiss = { showExcluded = false })
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Settings.System helpers
// ──────────────────────────────────────────────────────────────────────────

private fun getFlag(context: Context, key: String, default: Boolean): Boolean =
    Settings.System.getIntForUser(
        context.contentResolver, key, if (default) 1 else 0, UserHandle.USER_CURRENT
    ) != 0

private fun putFlag(context: Context, key: String, value: Boolean) {
    runCatching {
        Settings.System.putIntForUser(
            context.contentResolver, key, if (value) 1 else 0, UserHandle.USER_CURRENT
        )
    }
}

private fun readMap(context: Context, key: String): Map<String, String> {
    val raw = Settings.System.getStringForUser(
        context.contentResolver, key, UserHandle.USER_CURRENT
    ) ?: return emptyMap()
    return raw.split(";")
        .mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim()
            else null
        }
        .toMap()
}

private fun writeMap(context: Context, key: String, map: Map<String, String>) {
    val raw = map.entries.joinToString(";") { "${it.key}=${it.value}" }
    runCatching {
        Settings.System.putStringForUser(
            context.contentResolver, key, raw, UserHandle.USER_CURRENT
        )
    }
}

private fun readSet(context: Context, key: String): Set<String> {
    val raw = Settings.System.getStringForUser(
        context.contentResolver, key, UserHandle.USER_CURRENT
    ) ?: return emptySet()
    return raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

private fun writeSet(context: Context, key: String, set: Set<String>) {
    runCatching {
        Settings.System.putStringForUser(
            context.contentResolver, key, set.joinToString(";"), UserHandle.USER_CURRENT
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// App pickers
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun rememberInstalledApps(): List<AppRef> {
    val repo = rememberInstalledAppsRepository()
    var apps by remember { mutableStateOf<List<AppRef>>(emptyList()) }
    LaunchedEffect(Unit) { apps = repo.launchableApps() }
    return apps
}

/**
 * Tri-state list. Tapping an app cycles Auto → Video → Not video → Auto, so the common
 * case (auto-detection missed my player) is one tap, and the rarer "stop treating this
 * as video" is two — without a separate screen or a mode switch.
 */
@Composable
private fun VideoAppsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = rememberInstalledApps()
    var overrides by remember { mutableStateOf(readMap(context, KEY_VIDEO_LIST)) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Video apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Tap to cycle: Auto → Always → Never",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (apps.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(apps.size) { i ->
                        val app = apps[i]
                        val state = when (overrides[app.packageName]) {
                            "1" -> VideoOverride.FORCE_ON
                            "0" -> VideoOverride.FORCE_OFF
                            else -> VideoOverride.AUTO
                        }
                        AppRow(
                            app = app,
                            trailing = {
                                Text(
                                    text = when (state) {
                                        VideoOverride.AUTO -> "Auto"
                                        VideoOverride.FORCE_ON -> "Always"
                                        VideoOverride.FORCE_OFF -> "Never"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = when (state) {
                                        VideoOverride.AUTO -> MaterialTheme.colorScheme.onSurfaceVariant
                                        VideoOverride.FORCE_ON -> MaterialTheme.colorScheme.primary
                                        VideoOverride.FORCE_OFF -> MaterialTheme.colorScheme.error
                                    },
                                )
                            },
                            onClick = {
                                val next = overrides.toMutableMap()
                                when (state) {
                                    VideoOverride.AUTO -> next[app.packageName] = "1"
                                    VideoOverride.FORCE_ON -> next[app.packageName] = "0"
                                    VideoOverride.FORCE_OFF -> next.remove(app.packageName)
                                }
                                overrides = next
                                writeMap(context, KEY_VIDEO_LIST, next)
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ExcludedAppsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = rememberInstalledApps()
    var excluded by remember { mutableStateOf(readSet(context, KEY_EXCLUDED)) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Excluded apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            if (apps.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(apps.size) { i ->
                        val app = apps[i]
                        val isExcluded = excluded.contains(app.packageName)
                        AppRow(
                            app = app,
                            trailing = { Checkbox(checked = isExcluded, onCheckedChange = null) },
                            onClick = {
                                val next = if (isExcluded) excluded - app.packageName
                                else excluded + app.packageName
                                excluded = next
                                writeSet(context, KEY_EXCLUDED, next)
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppRef,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = rememberDrawablePainter(app.icon),
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}
