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
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.data.UserGame
import com.ireddragonicy.gamespace.gamebar.PanelChromeOverlay
import com.ireddragonicy.gamespace.gamebar.PerformanceEditor
import com.ireddragonicy.gamespace.gamebar.PanelTheme
import com.ireddragonicy.gamespace.gamebar.getThermalProfileColor
import com.ireddragonicy.gamespace.gamebar.rememberDrawablePainter
import com.ireddragonicy.gamespace.gamebar.chamferShape
import com.ireddragonicy.gamespace.gamebar.glassBrush
import com.ireddragonicy.gamespace.preferences.AppListPreferences
import com.ireddragonicy.gamespace.preferences.appselector.AppSelectorActivity
import com.ireddragonicy.gamespace.settings.PerAppSettingsActivity
import com.ireddragonicy.gamespace.settings.fpsstats.FpsStatsActivity
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONArray
import org.json.JSONObject

/**
 * GAME SPACE hub — the launcher-facing home of the app, in the same ROG
 * design language as the in-game overlay (Game Turbo-class game manager).
 *
 *  - QUICK LAUNCH rail: horizontal, thumb-reach game cards
 *  - GAME LIBRARY: vertical list (2-column grid in landscape) with per-game
 *    thermal profile chips (built-ins + user customs) and the full per-game
 *    CPU/GPU tuner (same editor as the overlay, backed by PerfTuner)
 *  - Add games via the existing selector; open the classic settings anytime
 */
class GameHubActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialExpressiveTheme(
                colorScheme = dynamicDarkColorScheme(this),
                motionScheme = MotionScheme.expressive(),
            ) {
                GameHubScreen()
            }
        }
    }
}

private data class GameEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

private data class ProfileOption(val index: Int, val name: String)

@Composable
private fun GameHubScreen() {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext, ServiceViewEntryPoint::class.java
        )
    }
    val systemSettings = remember { entryPoint.systemSettings() }
    val appSettings = remember { entryPoint.appSettings() }
    val perfTuner = remember { entryPoint.perfTuner() }
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary

    val games = remember { mutableStateListOf<GameEntry>() }
    var refreshKey by remember { mutableStateOf(0) }
    var carouselMode by remember { mutableStateOf(appSettings.hubCarouselMode) }

    LaunchedEffect(refreshKey) {
        games.clear()
        val pm = context.packageManager
        systemSettings.userGames.forEach { game: UserGame ->
            runCatching {
                val info = pm.getApplicationInfo(game.packageName, 0)
                games.add(
                    GameEntry(
                        packageName = game.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        icon = pm.getApplicationIcon(info),
                    )
                )
            }
        }
    }

    val selectorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(AppListPreferences.EXTRA_APP)?.let { pkg ->
                val current = systemSettings.userGames.orEmpty()
                if (current.none { it.packageName == pkg }) {
                    systemSettings.userGames = current + UserGame(pkg)
                }
                refreshKey++
            }
        }
    }

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        containerColor = PanelTheme.BaseDeep,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    selectorLauncher.launch(Intent(context, AppSelectorActivity::class.java))
                },
                containerColor = accent,
                shape = chamferShape(bigCut = 14.dp, smallCut = 5.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_add_rounded_filled),
                    contentDescription = "Add game",
                    tint = Color.Black,
                )
            }
        },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(PanelTheme.BaseDark, PanelTheme.BaseDeep)
                    )
                )
                .padding(insets)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    HubHeader(
                        accent = accent,
                        gameCount = games.size,
                        carouselMode = carouselMode,
                        onToggleLayout = {
                            carouselMode = !carouselMode
                            appSettings.hubCarouselMode = carouselMode
                        },
                    )
                }

                if (games.isNotEmpty()) {
                    item {
                        HubSectionTitle("QUICK LAUNCH", accent)
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(games, key = { "quick_${it.packageName}" }) { game ->
                                QuickLaunchCard(game, accent) {
                                    launchGame(context, game.packageName)
                                }
                            }
                        }
                    }
                }

                item { HubSectionTitle("GAME LIBRARY", accent) }

                if (games.isEmpty()) {
                    item { EmptyLibraryCard(accent) }
                } else if (carouselMode) {
                    // Hero carousel — one big Game Turbo-style card per swipe
                    item {
                        val pagerState = rememberPagerState { games.size }
                        HorizontalPager(
                            state = pagerState,
                            pageSpacing = 12.dp,
                            contentPadding = PaddingValues(horizontal = 24.dp),
                        ) { page ->
                            GameHeroCard(games[page], accent, perfTuner) { refreshKey++ }
                        }
                    }
                } else if (isLandscape) {
                    items(games.chunked(2), key = { it.first().packageName }) { rowGames ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowGames.forEach { game ->
                                Box(modifier = Modifier.weight(1f)) {
                                    GameLibraryCard(game, accent, perfTuner) { refreshKey++ }
                                }
                            }
                            if (rowGames.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    items(games, key = { it.packageName }) { game ->
                        GameLibraryCard(game, accent, perfTuner) { refreshKey++ }
                    }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) } // FAB clearance
            }
        }
    }
}

@Composable
private fun HubHeader(
    accent: Color,
    gameCount: Int,
    carouselMode: Boolean,
    onToggleLayout: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.materialsymbols_ic_sports_esports_rounded_filled),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = "GAME SPACE",
                color = PanelTheme.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Text(
                text = "$gameCount GAMES REGISTERED",
                color = accent.copy(alpha = 0.8f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // Library layout: vertical list ⇄ hero carousel
        Icon(
            painter = painterResource(
                if (carouselMode) R.drawable.materialsymbols_ic_view_list_rounded_filled
                else R.drawable.materialsymbols_ic_view_carousel_rounded_filled
            ),
            contentDescription = "Toggle library layout",
            tint = accent,
            modifier = Modifier
                .size(24.dp)
                .clickable(onClick = onToggleLayout),
        )
        Spacer(modifier = Modifier.width(16.dp))
        // Global FPS stats sessions
        Icon(
            painter = painterResource(R.drawable.materialsymbols_ic_monitoring_rounded_filled),
            contentDescription = "FPS stats",
            tint = PanelTheme.TextDim,
            modifier = Modifier
                .size(24.dp)
                .clickable {
                    context.startActivity(Intent(context, FpsStatsActivity::class.java))
                },
        )
        Spacer(modifier = Modifier.width(16.dp))
        Icon(
            painter = painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled),
            contentDescription = "Settings",
            tint = PanelTheme.TextDim,
            modifier = Modifier
                .size(24.dp)
                .clickable {
                    context.startActivity(Intent(context, HubSettingsActivity::class.java))
                },
        )
    }
}

/**
 * Hero card for carousel mode — one full-width Game Turbo-style card per
 * game: big art, profile chip, inline perf tuner and a prominent launch bar.
 */
@Composable
private fun GameHeroCard(
    game: GameEntry,
    accent: Color,
    perfTuner: PerfTuner,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val cardShape = remember { chamferShape(bigCut = 20.dp, smallCut = 7.dp) }
    var tunerOpen by remember(game.packageName) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(glassBrush(accent), cardShape),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = rememberDrawablePainter(game.icon),
                contentDescription = game.label,
                modifier = Modifier.size(96.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = game.label,
                color = PanelTheme.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            ThermalProfileChip(game.packageName, accent)
            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled),
                    contentDescription = "Perf tuner",
                    tint = if (tunerOpen) accent else PanelTheme.TextDim,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { tunerOpen = !tunerOpen },
                )
                Spacer(modifier = Modifier.width(20.dp))
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled),
                    contentDescription = "Per-app settings",
                    tint = PanelTheme.TextDim,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            context.startActivity(
                                Intent(context, PerAppSettingsActivity::class.java).apply {
                                    putExtra("package_name", game.packageName)
                                }
                            )
                        },
                )
                Spacer(modifier = Modifier.width(20.dp))
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_monitoring_rounded_filled),
                    contentDescription = "FPS stats",
                    tint = PanelTheme.TextDim,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            context.startActivity(Intent(context, FpsStatsActivity::class.java))
                        },
                )
            }

            AnimatedVisibility(visible = tunerOpen) {
                var profile by remember(game.packageName, tunerOpen) {
                    mutableStateOf(
                        perfTuner.loadProfile(game.packageName) ?: PerfTuner.PerfProfile()
                    )
                }
                PerformanceEditor(
                    perfTuner = perfTuner,
                    profile = profile,
                    accent = accent,
                    onCommit = {
                        profile = it
                        perfTuner.saveProfileFor(game.packageName, it)
                        onChanged()
                    },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            // Prominent launch bar — the thumb target
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(accent, remember { chamferShape(bigCut = 12.dp, smallCut = 4.dp) })
                    .clickable { launchGame(context, game.packageName) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_rocket_launch_rounded_filled),
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "LAUNCH",
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
            }
        }
        PanelChromeOverlay(accent = accent, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun HubSectionTitle(title: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 12.dp)
                .background(accent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            color = PanelTheme.TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun QuickLaunchCard(game: GameEntry, accent: Color, onLaunch: () -> Unit) {
    val cardShape = remember { chamferShape(bigCut = 12.dp, smallCut = 4.dp) }
    Box(
        modifier = Modifier
            .size(width = 84.dp, height = 100.dp)
            .background(glassBrush(accent), cardShape)
            .clickable(onClick = onLaunch),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = rememberDrawablePainter(game.icon),
                contentDescription = game.label,
                modifier = Modifier.size(52.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = game.label,
                color = PanelTheme.TextPrimary,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PanelChromeOverlay(accent = accent, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun EmptyLibraryCard(accent: Color) {
    val cardShape = remember { chamferShape() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(glassBrush(accent), cardShape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.materialsymbols_ic_joystick_rounded_filled),
            contentDescription = null,
            tint = accent.copy(alpha = 0.6f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "NO GAMES YET",
            color = PanelTheme.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Text(
            text = "Tap + to add your first game",
            color = PanelTheme.TextDim,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun GameLibraryCard(
    game: GameEntry,
    accent: Color,
    perfTuner: PerfTuner,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val cardShape = remember { chamferShape(bigCut = 14.dp, smallCut = 5.dp) }
    var tunerOpen by remember(game.packageName) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(glassBrush(accent), cardShape),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = rememberDrawablePainter(game.icon),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.label,
                        color = PanelTheme.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    ThermalProfileChip(game.packageName, accent)
                }

                // PERF TUNER toggle
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled),
                    contentDescription = "Perf tuner",
                    tint = if (tunerOpen) accent else PanelTheme.TextDim,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { tunerOpen = !tunerOpen },
                )
                Spacer(modifier = Modifier.width(12.dp))
                // Per-app settings
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled),
                    contentDescription = "Per-app settings",
                    tint = PanelTheme.TextDim,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            context.startActivity(
                                Intent(context, PerAppSettingsActivity::class.java).apply {
                                    putExtra("package_name", game.packageName)
                                }
                            )
                        },
                )
                Spacer(modifier = Modifier.width(12.dp))
                // Launch
                Icon(
                    painter = painterResource(R.drawable.materialsymbols_ic_rocket_launch_rounded_filled),
                    contentDescription = "Launch",
                    tint = accent,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { launchGame(context, game.packageName) },
                )
            }

            // Full per-game CPU/GPU DVFS editor (same engine as the overlay)
            AnimatedVisibility(visible = tunerOpen) {
                var profile by remember(game.packageName, tunerOpen) {
                    mutableStateOf(perfTuner.loadProfile(game.packageName) ?: PerfTuner.PerfProfile())
                }
                PerformanceEditor(
                    perfTuner = perfTuner,
                    profile = profile,
                    accent = accent,
                    onCommit = {
                        profile = it
                        perfTuner.saveProfileFor(game.packageName, it)
                        onChanged()
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        PanelChromeOverlay(accent = accent.copy(alpha = 0.6f), modifier = Modifier.matchParentSize())
    }
}

/**
 * Per-game thermal profile selector chip — built-in profiles plus user
 * custom profiles (indices >= 1000), written to mithermal_app_profiles.
 * GameSpace's ThermalController applies it live on foreground change.
 */
@Composable
private fun ThermalProfileChip(packageName: String, accent: Color) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var currentIdx by remember(packageName) {
        mutableStateOf(readAppProfile(context, packageName))
    }
    val options = remember(menuOpen) { loadProfileOptions(context) }
    val label = options.find { it.index == currentIdx }?.name
        ?: ThermalProfiles.PROFILE_NAMES.getOrElse(0) { "Auto" }
    val color = getThermalProfileColor(currentIdx)
    val chipShape = remember { chamferShape(bigCut = 6.dp, smallCut = 2.dp) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(color.copy(alpha = 0.14f), chipShape)
                .clickable { menuOpen = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(color, chamferShape(bigCut = 2.dp, smallCut = 1.dp))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                painter = painterResource(R.drawable.materialsymbols_ic_expand_more_rounded_filled),
                contentDescription = null,
                tint = color.copy(alpha = 0.7f),
                modifier = Modifier.size(10.dp),
            )
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.background(PanelTheme.BaseDark),
        ) {
            options.forEach { option ->
                val optColor = getThermalProfileColor(option.index)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(optColor, chamferShape(2.dp, 1.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = option.name,
                                color = if (option.index == currentIdx) optColor
                                else PanelTheme.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = if (option.index == currentIdx) FontWeight.Bold
                                else FontWeight.Normal,
                            )
                        }
                    },
                    onClick = {
                        currentIdx = option.index
                        writeAppProfile(context, packageName, option.index)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

// ── Data helpers ────────────────────────────────────────────────────────────

private fun launchGame(context: android.content.Context, packageName: String) {
    context.packageManager.getLaunchIntentForPackage(packageName)?.let {
        context.startActivity(it)
    }
}

/** Gaming-relevant built-ins + every user custom profile. */
private fun loadProfileOptions(context: android.content.Context): List<ProfileOption> {
    val gamingIndices = listOf(0, 2, 3, 4, 5, 6, 7, 16)
    val options = gamingIndices.map {
        ProfileOption(it, ThermalProfiles.PROFILE_NAMES[it])
    }.toMutableList()

    val json = Settings.System.getStringForUser(
        context.contentResolver, ThermalProfiles.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT
    )
    if (!json.isNullOrEmpty()) {
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                options.add(
                    ProfileOption(
                        index = ThermalProfiles.CUSTOM_PROFILE_BASE + i,
                        name = arr.getJSONObject(i).optString("name", "Custom #${i + 1}"),
                    )
                )
            }
        }
    }
    return options
}

private fun readAppProfile(context: android.content.Context, packageName: String): Int {
    val json = Settings.System.getStringForUser(
        context.contentResolver, ThermalProfiles.KEY_APP_PROFILES, UserHandle.USER_CURRENT
    ) ?: return 0
    return runCatching { JSONObject(json).optInt(packageName, 0) }.getOrDefault(0)
}

private fun writeAppProfile(context: android.content.Context, packageName: String, index: Int) {
    runCatching {
        val json = Settings.System.getStringForUser(
            context.contentResolver, ThermalProfiles.KEY_APP_PROFILES, UserHandle.USER_CURRENT
        ) ?: "{}"
        val obj = JSONObject(json)
        if (index == 0) obj.remove(packageName) else obj.put(packageName, index)
        Settings.System.putStringForUser(
            context.contentResolver, ThermalProfiles.KEY_APP_PROFILES,
            obj.toString(), UserHandle.USER_CURRENT
        )
    }
}
