/* --- ireddragonicy/gamespace/hub/GameHubActivity.kt --- */
package com.ireddragonicy.gamespace.hub

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.data.UserGame
import com.ireddragonicy.gamespace.gamebar.PerformanceEditor
import com.ireddragonicy.gamespace.gamebar.colorModes
import com.ireddragonicy.gamespace.gamebar.getThermalProfileColor
import com.ireddragonicy.gamespace.gamebar.rememberDrawablePainter
import com.ireddragonicy.gamespace.preferences.AppListPreferences
import com.ireddragonicy.gamespace.preferences.appselector.AppSelectorActivity
import com.ireddragonicy.gamespace.settings.PerAppSettingsActivity
import com.ireddragonicy.gamespace.settings.fpsstats.FpsStatsActivity
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONArray

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

private data class GameEntry(val packageName: String, val label: String, val icon: Drawable?)
private data class ProfileOption(val index: Int, val name: String)

@Composable
private fun GameHubScreen() {
    val context = LocalContext.current
    val entryPoint = remember { EntryPointAccessors.fromApplication(context.applicationContext, ServiceViewEntryPoint::class.java) }
    val systemSettings = remember { entryPoint.systemSettings() }
    val appSettings = remember { entryPoint.appSettings() }
    val perfTuner = remember { entryPoint.perfTuner() }

    // FIX: list diganti secara atomik (bukan clear()+add) supaya LazyColumn
    // diff-nya mulus dan tidak ada flicker saat game di-remove.
    var games by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var carouselMode by remember { mutableStateOf(appSettings.hubCarouselMode) }

    LaunchedEffect(refreshKey) {
        val pm = context.packageManager
        games = systemSettings.userGames.mapNotNull { game: UserGame ->
            runCatching {
                val info = pm.getApplicationInfo(game.packageName, 0)
                GameEntry(game.packageName, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
            }.getOrNull()
        }
    }

    // FIX (jaring pengaman): observe kunci Settings "gamespace_game_list".
    // Remove dari mana pun — panel in-game, AppListPreferences, atau layar
    // per-app — menulis kunci ini, jadi Hub selalu ikut ter-refresh walau
    // kita tidak membuka flow-nya sendiri.
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refreshKey++ }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor("gamespace_game_list"), false, observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    val selectorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(AppListPreferences.EXTRA_APP)?.let { pkg ->
                val current = systemSettings.userGames.orEmpty()
                if (current.none { it.packageName == pkg }) systemSettings.userGames = current + UserGame(pkg)
            }
        }
        refreshKey++
    }

    // FIX (inti bug remove): per-app settings dibuka FOR RESULT.
    // Kembali dari sana — game di-remove (RESULT_OK + PREF_UNREGISTER) atau
    // tuning-nya diedit — library + semua feature chip langsung di-refresh.
    val perAppSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshKey++ }

    val openPerAppSettings: (String) -> Unit = { pkg ->
        perAppSettingsLauncher.launch(
            Intent(context, PerAppSettingsActivity::class.java)
                .putExtra(PerAppSettingsActivity.EXTRA_PACKAGE, pkg)
        )
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { selectorLauncher.launch(Intent(context, AppSelectorActivity::class.java)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add game")
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                HubHeader(
                    gameCount = games.size,
                    carouselMode = carouselMode,
                    onToggleLayout = {
                        carouselMode = !carouselMode
                        appSettings.hubCarouselMode = carouselMode
                    }
                )
            }
            if (games.isNotEmpty()) {
                item { Text("Quick Launch", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(games, key = { "quick_${it.packageName}" }) { game ->
                            QuickLaunchCard(game) { launchGame(context, game.packageName) }
                        }
                    }
                }
            }
            item { Text("Game Library", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp)) }
            if (games.isEmpty()) {
                item { EmptyLibraryCard() }
            } else if (carouselMode) {
                item {
                    val pagerState = rememberPagerState { games.size }
                    HorizontalPager(state = pagerState, pageSpacing = 16.dp, contentPadding = PaddingValues(horizontal = 24.dp)) { page ->
                        // getOrNull: aman saat list menyusut (game baru di-remove)
                        // sebelum pager selesai clamp halamannya.
                        games.getOrNull(page)?.let { game ->
                            GameHeroCard(
                                game = game,
                                perfTuner = perfTuner,
                                onChanged = { refreshKey++ },
                                onOpenSettings = { openPerAppSettings(game.packageName) },
                                refreshKey = refreshKey,
                            )
                        }
                    }
                }
            } else if (isLandscape) {
                items(games.chunked(2), key = { it.first().packageName }) { rowGames ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        rowGames.forEach { game ->
                            Box(modifier = Modifier.weight(1f)) {
                                GameLibraryCard(
                                    game = game,
                                    perfTuner = perfTuner,
                                    onChanged = { refreshKey++ },
                                    onOpenSettings = { openPerAppSettings(game.packageName) },
                                    refreshKey = refreshKey,
                                )
                            }
                        }
                        if (rowGames.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            } else {
                items(games, key = { it.packageName }) { game ->
                    GameLibraryCard(
                        game = game,
                        perfTuner = perfTuner,
                        onChanged = { refreshKey++ },
                        onOpenSettings = { openPerAppSettings(game.packageName) },
                        refreshKey = refreshKey,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun HubHeader(gameCount: Int, carouselMode: Boolean, onToggleLayout: () -> Unit) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Game Space", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("$gameCount games installed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onToggleLayout) {
            Icon(
                painterResource(if (carouselMode) R.drawable.materialsymbols_ic_view_list_rounded_filled else R.drawable.materialsymbols_ic_view_carousel_rounded_filled),
                contentDescription = "Toggle layout", tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { context.startActivity(Intent(context, com.ireddragonicy.gamespace.settings.diagnostics.DiagnosticsActivity::class.java)) }) {
            Icon(Icons.Rounded.Info, contentDescription = "Diagnostics", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { context.startActivity(Intent(context, FpsStatsActivity::class.java)) }) {
            Icon(painterResource(R.drawable.materialsymbols_ic_monitoring_rounded_filled), contentDescription = "FPS stats", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { context.startActivity(Intent(context, HubSettingsActivity::class.java)) }) {
            Icon(painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled), contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GameHeroCard(
    game: GameEntry,
    perfTuner: PerfTuner,
    onChanged: () -> Unit,
    onOpenSettings: () -> Unit,
    refreshKey: Int,
) {
    val context = LocalContext.current
    var tunerOpen by remember(game.packageName) { mutableStateOf(false) }
    var profile by remember(game.packageName, tunerOpen) { mutableStateOf(perfTuner.loadProfile(game.packageName) ?: PerfTuner.PerfProfile()) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(rememberDrawablePainter(game.icon), contentDescription = null, modifier = Modifier.size(100.dp).clip(RoundedCornerShape(20.dp)))
            Spacer(modifier = Modifier.height(16.dp))
            Text(game.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(8.dp))
            GameFeatureRow(game.packageName, refreshKey)
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(onClick = { tunerOpen = !tunerOpen }) { Icon(painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled), "Perf tuner") }
                FilledTonalIconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled), "Settings")
                }
            }
            AnimatedVisibility(visible = tunerOpen) {
                PerformanceEditor(
                    perfTuner = perfTuner, profile = profile, accent = MaterialTheme.colorScheme.primary,
                    onCommit = { profile = it; perfTuner.saveProfileFor(game.packageName, it); onChanged() },
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { launchGame(context, game.packageName) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Play", style = MaterialTheme.typography.labelLarge, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun QuickLaunchCard(game: GameEntry, onLaunch: () -> Unit) {
    ElevatedCard(
        onClick = onLaunch,
        modifier = Modifier.size(90.dp, 100.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(rememberDrawablePainter(game.icon), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(modifier = Modifier.height(8.dp))
            Text(game.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyLibraryCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.materialsymbols_ic_joystick_rounded_filled), null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Text("No Games Yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Tap the + button to add your games", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GameLibraryCard(
    game: GameEntry,
    perfTuner: PerfTuner,
    onChanged: () -> Unit,
    onOpenSettings: () -> Unit,
    refreshKey: Int,
) {
    val context = LocalContext.current
    var tunerOpen by remember(game.packageName) { mutableStateOf(false) }
    var profile by remember(game.packageName, tunerOpen) { mutableStateOf(perfTuner.loadProfile(game.packageName) ?: PerfTuner.PerfProfile()) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    rememberDrawablePainter(game.icon), contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    game.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).clickable { tunerOpen = !tunerOpen },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled), "Perf tuner",
                        tint = if (tunerOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                // FIX: buka per-app settings FOR RESULT (lewat callback launcher),
                // bukan startActivity() biasa — supaya Hub tahu saat kita kembali.
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled), "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { launchGame(context, game.packageName) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.materialsymbols_ic_rocket_launch_rounded_filled), "Launch",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            GameFeatureRow(game.packageName, refreshKey)
            AnimatedVisibility(visible = tunerOpen) {
                PerformanceEditor(
                    perfTuner = perfTuner, profile = profile, accent = MaterialTheme.colorScheme.primary,
                    onCommit = { profile = it; perfTuner.saveProfileFor(game.packageName, it); onChanged() },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ThermalProfileChip(packageName: String, refreshKey: Int) {
    val context = LocalContext.current
    val store = remember { PerAppSettingStore(context) }
    var menuOpen by remember { mutableStateOf(false) }
    // FIX: refreshKey jadi key remember → nilai dibaca ulang dari store
    // setiap kali Hub ke-refresh (mis. selesai edit dari layar per-app).
    var currentIdx by remember(packageName, refreshKey) { mutableStateOf(store.thermalProfile(packageName)) }
    val options = remember(menuOpen) { loadProfileOptions(context) }
    val label = options.find { it.index == currentIdx }?.name ?: ThermalProfiles.PROFILE_NAMES.getOrElse(0) { "Auto" }
    val color = getThermalProfileColor(currentIdx)
    val iconRes = remember(label) {
        when {
            label.contains("Battery", ignoreCase = true) || label.contains("Saver", ignoreCase = true) -> R.drawable.materialsymbols_ic_battery_saver_rounded_filled
            label.contains("Performance", ignoreCase = true) || label.contains("Extreme", ignoreCase = true) -> R.drawable.materialsymbols_ic_rocket_launch_rounded_filled
            label.contains("Balanced", ignoreCase = true) -> R.drawable.materialsymbols_ic_joystick_rounded_filled
            else -> R.drawable.materialsymbols_ic_tune_rounded_filled
        }
    }
    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = 0.16f),
            modifier = Modifier.height(22.dp).clickable { menuOpen = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 7.dp)
            ) {
                Icon(painterResource(iconRes), null, tint = color, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    trailingIcon = if (option.index == currentIdx) {
                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        currentIdx = option.index
                        store.setThermalProfile(packageName, option.index)
                        menuOpen = false
                    }
                )
            }
        }
    }
}

/**
 * NEW: chip AFME interaktif — selalu tampil di kartu game.
 * Off  → abu-abu (onSurfaceVariant), label "AFME".
 * On   → cyan khas AFME (0xFF00E5FF), label "AFME 2×/3×/4×".
 * Tulis lewat PerAppSettingStore.setAfmeMultiplier() sehingga sysprop
 * persist.sys.afme.* ikut tersinkron → tile AFME di panel in-game dan
 * FpsInteractor otomatis mengikuti.
 */
@Composable
private fun AfmeChip(packageName: String, store: PerAppSettingStore, refreshKey: Int) {
    var multiplier by remember(packageName, refreshKey) { mutableIntStateOf(store.afmeMultiplier(packageName)) }
    var menuOpen by remember { mutableStateOf(false) }
    val active = multiplier > 0
    val color = if (active) Color(0xFF00E5FF) else MaterialTheme.colorScheme.onSurfaceVariant
    val options = remember { listOf(0 to "Off", 2 to "2×", 3 to "3×", 4 to "4×") }

    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = if (active) 0.16f else 0.10f),
            modifier = Modifier.height(22.dp).clickable { menuOpen = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 7.dp)
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = color, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = if (active) "AFME ${multiplier}×" else "AFME",
                    color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = if (value == multiplier) {
                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        multiplier = value
                        store.setAfmeMultiplier(packageName, value)
                        menuOpen = false
                    }
                )
            }
        }
    }
}

/**
 * NEW: chip Display Style — ganti mode warna langsung dari kartu.
 * Original (default) → abu-abu; mode lain → primary.
 * store.setDisplayStyle() langsung apply live kalau game-nya sedang jalan
 * (ActiveGameHolder), dan tersimpan per-game untuk sesi berikutnya.
 */
@Composable
private fun DisplayStyleChip(packageName: String, store: PerAppSettingStore, refreshKey: Int) {
    var modeId by remember(packageName, refreshKey) { mutableIntStateOf(store.displayStyle(packageName)) }
    var menuOpen by remember { mutableStateOf(false) }
    val mode = colorModes.firstOrNull { it.id == modeId } ?: colorModes.first()
    val active = modeId != 0
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = if (active) 0.16f else 0.10f),
            modifier = Modifier.height(22.dp).clickable { menuOpen = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 7.dp)
            ) {
                Icon(painterResource(mode.iconRes), null, tint = color, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(mode.label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            colorModes.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    leadingIcon = {
                        Icon(painterResource(m.iconRes), null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = if (m.id == modeId) {
                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        modeId = m.id
                        store.setDisplayStyle(packageName, m.id)
                        menuOpen = false
                    }
                )
            }
        }
    }
}

/**
 * NEW: chip MSAA (Anti-Aliasing) interaktif.
 * Off → abu-abu (onSurfaceVariant), label "MSAA".
 * 2× / 4× → hijau (0xFF00E676), label "MSAA 2×/4×".
 */
@Composable
private fun MsaaChip(packageName: String, store: PerAppSettingStore, refreshKey: Int) {
    var level by remember(packageName, refreshKey) { mutableIntStateOf(store.gpuMsaa(packageName)) }
    var menuOpen by remember { mutableStateOf(false) }
    val active = level > 0
    val color = if (active) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
    val options = remember { listOf(0 to "Off", 2 to "2×", 4 to "4×") }

    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = if (active) 0.16f else 0.10f),
            modifier = Modifier.height(22.dp).clickable { menuOpen = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 7.dp)
            ) {
                Icon(painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled), null, tint = color, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = if (active) "MSAA ${level}×" else "MSAA",
                    color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = if (value == level) {
                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        level = value
                        store.setGpuMsaa(packageName, value)
                        menuOpen = false
                    }
                )
            }
        }
    }
}

/**
 * NEW: chip AF (Anisotropic Filtering) interaktif.
 * Off → abu-abu (onSurfaceVariant), label "AF".
 * 2×..16× → kuning emas (0xFFFFD700), label "AF 2×..16×".
 */
@Composable
private fun AfChip(packageName: String, store: PerAppSettingStore, refreshKey: Int) {
    var level by remember(packageName, refreshKey) { mutableIntStateOf(store.gpuAf(packageName)) }
    var menuOpen by remember { mutableStateOf(false) }
    val active = level > 0
    val color = if (active) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant
    val options = remember { listOf(0 to "Off", 2 to "2×", 4 to "4×", 8 to "8×", 16 to "16×") }

    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = if (active) 0.16f else 0.10f),
            modifier = Modifier.height(22.dp).clickable { menuOpen = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 7.dp)
            ) {
                Icon(painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled), null, tint = color, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = if (active) "AF ${level}×" else "AF",
                    color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = if (value == level) {
                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        level = value
                        store.setGpuAf(packageName, value)
                        menuOpen = false
                    }
                )
            }
        }
    }
}

/**
 * NEW: chip Texture Filtering Quality interaktif.
 * Default → abu-abu, Speed/Balanced/Quality → cyan (0xFF00B0FF).
 */
@Composable
private fun TextureQualityChip(packageName: String, store: PerAppSettingStore, refreshKey: Int) {
    var quality by remember(packageName, refreshKey) { mutableIntStateOf(store.gpuTexQuality(packageName)) }
    var menuOpen by remember { mutableStateOf(false) }
    val active = quality > 0
    val color = if (active) Color(0xFF00B0FF) else MaterialTheme.colorScheme.onSurfaceVariant
    val options = remember { listOf(0 to "Default", 1 to "Speed", 2 to "Balanced", 3 to "Quality") }
    val label = when (quality) {
        1 -> "Tex: Speed"
        2 -> "Tex: Balanced"
        3 -> "Tex: Quality"
        else -> "Texture"
    }

    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = if (active) 0.16f else 0.10f),
            modifier = Modifier.height(22.dp).clickable { menuOpen = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 7.dp)
            ) {
                Icon(painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled), null, tint = color, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = if (value == quality) {
                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        quality = value
                        store.setGpuTexQuality(packageName, value)
                        menuOpen = false
                    }
                )
            }
        }
    }
}

/** Read-only mini feature pill: coloured background + Material icon + label. */
@Composable
private fun FeatureChip(icon: ImageVector, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(22.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(11.dp))
        Spacer(modifier = Modifier.width(3.dp))
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Satu baris pill status yang bisa di-scroll horizontal:
 * Thermal (dropdown) · AFME (dropdown) · MSAA (dropdown) · AF (dropdown) ·
 * Texture Quality (dropdown) · Display Style (dropdown) · Color+ (read-only).
 */
@Composable
private fun GameFeatureRow(packageName: String, refreshKey: Int) {
    val context = LocalContext.current
    val store = remember { PerAppSettingStore(context) }
    val colorEnhance = remember(packageName, refreshKey) { store.colorEnhance(packageName) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThermalProfileChip(packageName, refreshKey)
        AfmeChip(packageName, store, refreshKey)
        MsaaChip(packageName, store, refreshKey)
        AfChip(packageName, store, refreshKey)
        TextureQualityChip(packageName, store, refreshKey)
        DisplayStyleChip(packageName, store, refreshKey)
        if (colorEnhance) FeatureChip(Icons.Rounded.Palette, "Color+", Color(0xFFE040FB))
    }
}

private fun launchGame(context: android.content.Context, packageName: String) {
    context.packageManager.getLaunchIntentForPackage(packageName)?.let { context.startActivity(it) }
}

private fun loadProfileOptions(context: android.content.Context): List<ProfileOption> {
    val gamingIndices = listOf(0, 2, 3, 4, 5, 6, 7, 16)
    val options = gamingIndices.map { ProfileOption(it, ThermalProfiles.PROFILE_NAMES[it]) }.toMutableList()
    val json = Settings.System.getStringForUser(context.contentResolver, ThermalProfiles.KEY_CUSTOM_PROFILES, UserHandle.USER_CURRENT)
    if (!json.isNullOrEmpty()) {
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) options.add(ProfileOption(ThermalProfiles.CUSTOM_PROFILE_BASE + i, arr.getJSONObject(i).optString("name", "Custom #${i + 1}")))
        }
    }
    return options
}
