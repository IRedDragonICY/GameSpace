/* --- ireddragonicy/gamespace/hub/GameHubActivity.kt --- */
package com.ireddragonicy.gamespace.hub

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import com.ireddragonicy.gamespace.gamebar.batteryTempColor
import com.ireddragonicy.gamespace.gamebar.chamferShape
import com.ireddragonicy.gamespace.gamebar.heatColor
import com.ireddragonicy.gamespace.gamebar.rememberCurrentTime
import com.ireddragonicy.gamespace.telemetry.TelemetrySnapshot
import com.ireddragonicy.gamespace.ui.theme.appIsDarkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import com.ireddragonicy.gamespace.data.DolbyProfileClient
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import com.ireddragonicy.gamespace.data.banner.BannerSource
import com.ireddragonicy.gamespace.data.banner.GameBannerRepository
import java.io.File
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
import com.ireddragonicy.gamespace.data.model.AppRef
import com.ireddragonicy.gamespace.gamebar.PerfEditorStyle
import com.ireddragonicy.gamespace.gamebar.PerformanceEditor
import com.ireddragonicy.gamespace.gamebar.colorModes
import com.ireddragonicy.gamespace.gamebar.getThermalProfileColor
import com.ireddragonicy.gamespace.utils.rememberDrawablePainter
import com.ireddragonicy.gamespace.preferences.AppListPreferences
import com.ireddragonicy.gamespace.preferences.appselector.AppSelectorActivity
import com.ireddragonicy.gamespace.settings.PerAppSettingsActivity
import com.ireddragonicy.gamespace.settings.fpsstats.FpsStatsActivity
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.ireddragonicy.gamespace.ui.theme.GameSpaceTheme

class GameHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GameSpaceTheme {
                GameHubScreen()
            }
        }
    }
}


@Composable
private fun GameHubScreen() {
    val context = LocalContext.current
    val entryPoint = remember { EntryPointAccessors.fromApplication(context.applicationContext, ServiceViewEntryPoint::class.java) }
    val systemSettings = remember { entryPoint.systemSettings() }
    val appSettings = remember { entryPoint.appSettings() }
    val perfTuner = remember { entryPoint.perfTuner() }
    val bannerRepo = remember { entryPoint.bannerRepository() }
    val bannerSource = remember { BannerSource.fromKey(appSettings.bannerSource) }

    // FIX: list diganti secara atomik (bukan clear()+add) supaya LazyColumn
    // diff-nya mulus dan tidak ada flicker saat game di-remove.
    var games by remember { mutableStateOf<List<AppRef>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var carouselMode by remember { mutableStateOf(appSettings.hubCarouselMode) }

    LaunchedEffect(refreshKey) {
        val pm = context.packageManager
        games = systemSettings.userGames.mapNotNull { game: UserGame ->
            runCatching {
                val info = pm.getApplicationInfo(game.packageName, 0)
                AppRef(game.packageName, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
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

    // ── LANDSCAPE: dedicated "command-center" front page (vertical stays untouched) ──
    if (isLandscape) {
        HorizontalHubScreen(
            games = games,
            refreshKey = refreshKey,
            bannerRepo = bannerRepo,
            bannerSource = bannerSource,
            onOpenSettings = openPerAppSettings,
        )
        return
    }

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
                                bannerRepo = bannerRepo,
                                bannerSource = bannerSource,
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
                                    bannerRepo = bannerRepo,
                                    bannerSource = bannerSource,
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
                        bannerRepo = bannerRepo,
                        bannerSource = bannerSource,
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
        IconButton(onClick = { context.startActivity(Intent(context, GlobalProfileActivity::class.java)) }) {
            Icon(painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled), contentDescription = "Global profile", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { context.startActivity(Intent(context, HubSettingsActivity::class.java)) }) {
            Icon(painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled), contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GameHeroCard(
    game: AppRef,
    perfTuner: PerfTuner,
    onChanged: () -> Unit,
    onOpenSettings: () -> Unit,
    refreshKey: Int,
    bannerRepo: GameBannerRepository,
    bannerSource: BannerSource,
) {
    val context = LocalContext.current
    var tunerOpen by remember(game.packageName) { mutableStateOf(false) }
    var profile by remember(game.packageName, tunerOpen) { mutableStateOf(perfTuner.loadProfile(game.packageName) ?: PerfTuner.PerfProfile()) }

    var bannerFile by remember(game.packageName, bannerSource) {
        mutableStateOf(bannerRepo.getCached(game.packageName)?.localPath?.let { File(it) })
    }
    LaunchedEffect(game.packageName, bannerSource) {
        val banner = bannerRepo.getOrFetch(game.packageName, bannerSource)
        banner?.localPath?.let { bannerFile = File(it) }
    }
    val bannerBitmap by remember(bannerFile) {
        derivedStateOf {
            bannerFile?.takeIf { it.exists() }
                ?.runCatching { BitmapFactory.decodeFile(absolutePath) }
                ?.getOrNull()
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            if (bannerBitmap != null) {
                Image(
                    bitmap = bannerBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                        )
                    )
                )
            } else {
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                        )
                    )
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Image(
                    rememberDrawablePainter(game.icon),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    game.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(modifier = Modifier.padding(20.dp)) {
            GameFeatureRow(game.packageName, refreshKey)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(onClick = { tunerOpen = !tunerOpen }) {
                    Icon(painterResource(R.drawable.materialsymbols_ic_tune_rounded_filled), "Perf tuner")
                }
                FilledTonalIconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled), "Settings")
                }
            }
            AnimatedVisibility(visible = tunerOpen) {
                PerformanceEditor(
                    perfTuner = perfTuner, profile = profile, style = PerfEditorStyle.material(),
                    onCommit = { profile = it; perfTuner.saveProfileFor(game.packageName, it); onChanged() },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { launchGame(context, game.packageName) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Play", style = MaterialTheme.typography.labelLarge, fontSize = 16.sp)
            }
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
    game: AppRef,
    perfTuner: PerfTuner,
    onChanged: () -> Unit,
    onOpenSettings: () -> Unit,
    refreshKey: Int,
    bannerRepo: GameBannerRepository,
    bannerSource: BannerSource,
) {
    val context = LocalContext.current
    var tunerOpen by remember(game.packageName) { mutableStateOf(false) }
    var profile by remember(game.packageName, tunerOpen) { mutableStateOf(perfTuner.loadProfile(game.packageName) ?: PerfTuner.PerfProfile()) }

    var bannerFile by remember(game.packageName, bannerSource) {
        mutableStateOf(bannerRepo.getCached(game.packageName)?.localPath?.let { File(it) })
    }
    LaunchedEffect(game.packageName, bannerSource) {
        val banner = bannerRepo.getOrFetch(game.packageName, bannerSource)
        banner?.localPath?.let { bannerFile = File(it) }
    }
    val bannerBitmap by remember(bannerFile) {
        derivedStateOf {
            bannerFile?.takeIf { it.exists() }
                ?.runCatching { BitmapFactory.decodeFile(absolutePath) }
                ?.getOrNull()
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (bannerBitmap != null) {
                Image(
                    bitmap = bannerBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Black.copy(alpha = 0.85f),
                            )
                        )
                    )
                )
            }
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
                        color = if (bannerBitmap != null) Color.White else Color.Unspecified,
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
                            tint = if (tunerOpen) MaterialTheme.colorScheme.primary else if (bannerBitmap != null) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Box(
                        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onOpenSettings),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled), "Settings",
                            tint = if (bannerBitmap != null) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        perfTuner = perfTuner, profile = profile, style = PerfEditorStyle.material(),
                        onCommit = { profile = it; perfTuner.saveProfileFor(game.packageName, it); onChanged() },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThermalProfileChip(packageName: String, refreshKey: Int) {
    val context = LocalContext.current
    val store = com.ireddragonicy.gamespace.utils.rememberPerAppStore()
    var menuOpen by remember { mutableStateOf(false) }
    // FIX: refreshKey jadi key remember → nilai dibaca ulang dari store
    // setiap kali Hub ke-refresh (mis. selesai edit dari layar per-app).
    var currentIdx by remember(packageName, refreshKey) { mutableStateOf(store.thermalProfile(packageName)) }
    // The compact chip only offers the game-relevant scenes; the full list
    // lives behind Configure Game.
    val options = remember(menuOpen) {
        ThermalProfiles.options(
            Settings.System.getStringForUser(
                context.contentResolver, ThermalProfiles.KEY_CUSTOM_PROFILES,
                UserHandle.USER_CURRENT
            ),
            builtinIndices = ThermalProfiles.GAMING_INDICES,
        )
    }
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
    var enabled by remember(packageName, refreshKey) { mutableStateOf(store.afmeEnabled(packageName)) }
    var mult by remember(packageName, refreshKey) { mutableIntStateOf(store.afmeMultiplier(packageName)) }
    val active = enabled
    val current = if (enabled) mult else 0
    DropdownChip(
        iconRes = R.drawable.materialsymbols_ic_auto_awesome_rounded_filled,
        label = if (active) "AFME ${mult}×" else "AFME",
        activeColor = Color(0xFF00E5FF),
        isActive = active,
        options = remember {
            listOf(
                ChipOption(0, "Off"),
                ChipOption(2, "AFME 2×"),
                ChipOption(3, "AFME 3×"),
                ChipOption(4, "AFME 4×"),
            )
        },
        selected = current,
        onSelect = { value ->
            if (value == 0) {
                store.setAfmeEnabled(packageName, false); enabled = false
            } else {
                store.setAfmeMultiplier(packageName, value)   // tier
                store.setAfmeEnabled(packageName, true)       // nyala
                mult = value; enabled = true
            }
        },
    )
}

/**
 * NEW: chip Display Style — ganti mode warna langsung dari kartu.
 * Original (default) → abu-abu; mode lain → primary.
 * store.setDisplayStyle() langsung apply live kalau game-nya sedang jalan
 * (ActiveSessionStore), dan tersimpan per-game untuk sesi berikutnya.
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
    val active = level > 0
    DropdownChip(
        iconRes = R.drawable.materialsymbols_ic_tune_rounded_filled,
        label = if (active) "MSAA ${level}×" else "MSAA",
        activeColor = Color(0xFF00E676),
        isActive = active,
        options = remember { listOf(ChipOption(0, "Off"), ChipOption(2, "2×"), ChipOption(4, "4×")) },
        selected = level,
        onSelect = { value ->
            level = value
            store.setGpuMsaa(packageName, value)
        },
    )
}

/**
 * NEW: chip AF (Anisotropic Filtering) interaktif.
 * Off → abu-abu (onSurfaceVariant), label "AF".
 * 2×..16× → kuning emas (0xFFFFD700), label "AF 2×..16×".
 */
@Composable
private fun AfChip(packageName: String, store: PerAppSettingStore, refreshKey: Int) {
    var level by remember(packageName, refreshKey) { mutableIntStateOf(store.gpuAf(packageName)) }
    val active = level > 0
    DropdownChip(
        iconRes = R.drawable.materialsymbols_ic_tune_rounded_filled,
        label = if (active) "AF ${level}×" else "AF",
        activeColor = Color(0xFFFFD700),
        isActive = active,
        options = remember {
            listOf(
                ChipOption(0, "Off"),
                ChipOption(2, "2×"),
                ChipOption(4, "4×"),
                ChipOption(8, "8×"),
                ChipOption(16, "16×"),
            )
        },
        selected = level,
        onSelect = { value ->
            level = value
            store.setGpuAf(packageName, value)
        },
    )
}

/**
 * NEW: chip Texture Filtering Quality interaktif.
 * Default → abu-abu, Speed/Balanced/Quality → cyan (0xFF00B0FF).
 */
@Composable
private fun TextureQualityChip(packageName: String, store: PerAppSettingStore, refreshKey: Int) {
    var quality by remember(packageName, refreshKey) { mutableIntStateOf(store.gpuTexQuality(packageName)) }
    val active = quality > 0
    val label = when (quality) {
        1 -> "Tex: Speed"
        2 -> "Tex: Balanced"
        3 -> "Tex: Quality"
        else -> "Texture"
    }
    DropdownChip(
        iconRes = R.drawable.materialsymbols_ic_tune_rounded_filled,
        label = label,
        activeColor = Color(0xFF00B0FF),
        isActive = active,
        options = remember {
            listOf(
                ChipOption(0, "Default"),
                ChipOption(1, "Speed"),
                ChipOption(2, "Balanced"),
                ChipOption(3, "Quality"),
            )
        },
        selected = quality,
        onSelect = { value ->
            quality = value
            store.setGpuTexQuality(packageName, value)
        },
    )
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
    val store = com.ireddragonicy.gamespace.utils.rememberPerAppStore()
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
        val dolbyProfile = remember(packageName, refreshKey) {
            if (com.ireddragonicy.gamespace.gamebar.video.DolbyBridge.isAvailable(context)) {
                DolbyProfileClient.getProfile(context, packageName)
            } else {
                DolbyProfileClient.PROFILE_DEFAULT
            }
        }
        if (dolbyProfile >= 0) {
            FeatureChip(
                icon = Icons.Rounded.GraphicEq,
                label = "Dolby: ${DolbyProfileClient.profileName(dolbyProfile)}",
                color = Color(0xFF00B0FF),
            )
        }
        if (colorEnhance) FeatureChip(Icons.Rounded.Palette, "Color+", Color(0xFFE040FB))
    }
}

private fun launchGame(context: android.content.Context, packageName: String) {
    context.packageManager.getLaunchIntentForPackage(packageName)?.let { context.startActivity(it) }
}

// ════════════════════════════════════════════════════════════════════════════
//  LANDSCAPE HUB — "command center" front page (Material You, responsive)
//  Wide ≥840dp : [Vitals] · [Stage coverflow] · [Library rail]
//  Med  600–840: [Stage coverflow] · [Library rail]
//  Narrow <600 : [Stage coverflow]
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HorizontalHubScreen(
    games: List<AppRef>,
    refreshKey: Int,
    bannerRepo: GameBannerRepository,
    bannerSource: BannerSource,
    onOpenSettings: (String) -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    // Live device vitals — ref-counted, only runs while this landscape screen lives.
    val telemetryBus = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext, ServiceViewEntryPoint::class.java
        ).telemetryBus()
    }
    DisposableEffect(telemetryBus) {
        telemetryBus.start()
        onDispose { telemetryBus.stop() }
    }
    val snap by telemetryBus.snapshot.collectAsState()

    // Add-game launcher lives here so the landscape screen is self-contained.
    val addLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Refresh is owned by the parent — the ContentObserver in GameHubScreen
        // already reacts to game-list writes, so nothing to bump here.
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(AppListPreferences.EXTRA_APP)
        }
    }
    val onAdd = {
        addLauncher.launch(Intent(context, AppSelectorActivity::class.java))
    }

    val pagerState = rememberPagerState { games.size }
    val scope = rememberCoroutineScope()

    // ── KUNCI FIX #1: nol-kan inset Scaffold, kita kelola sendiri secara eksplisit.
    // M3 Scaffold default contentWindowInsets = 0, itulah sebabnya brand dulu
    // mentok status bar. Di sini kita pakai modifier inset yang anti-double.
    Scaffold(
        containerColor = scheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            ArenaBackground() // ambient yang sudah ditenangkan

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .navigationBarsPadding(),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val medium = maxWidth >= 600.dp

                    // ── DEFAULT TERTUTUP ── panggung tengah adalah kesan pertama.
                    // Vitals & library adalah panel on-demand, bukan perabot tetap;
                    // keduanya wipe terbuka hanya saat diminta lewat toolbar.
                    var vitalsOpen by remember { mutableStateOf(false) }
                    var libraryOpen by remember { mutableStateOf(false) }

                    val vitalsTarget = 138.dp
                    val libraryTarget = if (maxWidth >= 840.dp) 184.dp else 164.dp

                    val vitalsW by animateDpAsState(
                        if (vitalsOpen && medium) vitalsTarget else 0.dp,
                        tween(300, easing = FastOutSlowInEasing), label = "vitalsW",
                    )
                    val vitalsAlpha by animateFloatAsState(
                        if (vitalsOpen && medium) 1f else 0f, tween(220), label = "vitalsA",
                    )
                    val libraryW by animateDpAsState(
                        if (libraryOpen && medium) libraryTarget else 0.dp,
                        tween(300, easing = FastOutSlowInEasing), label = "libraryW",
                    )
                    val libraryAlpha by animateFloatAsState(
                        if (libraryOpen && medium) 1f else 0f, tween(220), label = "libraryA",
                    )

                    Column(Modifier.fillMaxSize()) {
                        TopRail(
                            gameCount = games.size,
                            snap = snap,
                            vitalsOpen = vitalsOpen,
                            libraryOpen = libraryOpen,
                            showPanelToggles = medium,
                            onToggleVitals = { vitalsOpen = !vitalsOpen },
                            onToggleLibrary = { libraryOpen = !libraryOpen },
                            onAdd = onAdd,
                            onDiag = {
                                context.startActivity(
                                    Intent(context, com.ireddragonicy.gamespace.settings.diagnostics.DiagnosticsActivity::class.java)
                                )
                            },
                            onStats = { context.startActivity(Intent(context, FpsStatsActivity::class.java)) },
                            onSettings = { context.startActivity(Intent(context, HubSettingsActivity::class.java)) },
                        )

                        if (games.isEmpty()) {
                            EmptyLandscape(onAdd = onAdd)
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // SAYAP KIRI — width ter-animasi + clip: saat 0,
                                // konten benar-benar hilang (tidak "nongol").
                                Box(
                                    Modifier
                                        .width(vitalsW)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(14.dp)),
                                ) {
                                    DeviceVitalsColumn(
                                        snap = snap,
                                        modifier = Modifier
                                            .width(vitalsTarget)
                                            .fillMaxHeight()
                                            .alpha(vitalsAlpha),
                                    )
                                }

                                // TENGAH — weight(1): melebar memenuhi ruang begitu
                                // sayap menutup. Inilah "tengah luas" yang dinamis.
                                CenterStage(
                                    games = games,
                                    refreshKey = refreshKey,
                                    pagerState = pagerState,
                                    scope = scope,
                                    bannerRepo = bannerRepo,
                                    bannerSource = bannerSource,
                                    onOpenSettings = onOpenSettings,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )

                                // SAYAP KANAN
                                Box(
                                    Modifier
                                        .width(libraryW)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(14.dp)),
                                ) {
                                    LibraryRail(
                                        games = games,
                                        pagerState = pagerState,
                                        scope = scope,
                                        onOpenSettings = onOpenSettings,
                                        modifier = Modifier
                                            .width(libraryTarget)
                                            .fillMaxHeight()
                                            .alpha(libraryAlpha),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Animated perspective "arena" stage ──────────────────────────────────────
@Composable
private fun ArenaBackground() {
    val scheme = MaterialTheme.colorScheme
    val isDark = appIsDarkTheme()
    // Alpha diturunkan supaya arena jadi lapisan ambient lembut, bukan
    // garis terang yang membelah tengah dan ikut "berteriak".
    val gridColor = scheme.onSurface.copy(alpha = if (isDark) 0.05f else 0.04f)
    val glow = scheme.primary
    val tertiary = scheme.tertiary
    val vignette = (if (isDark) Color.Black else scheme.surfaceVariant)
        .copy(alpha = if (isDark) 0.55f else 0.45f)

    val infinite = rememberInfiniteTransition(label = "arena")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "arena_phase",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "arena_pulse",
    )

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.62f
        val cx = w / 2f

        // Vignette — soft, theme-aware (NOT an aurora blob).
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, vignette),
                center = Offset(cx, horizon),
                radius = w * 0.85f,
            )
        )

        // Converging vertical lines (perspective).
        val cols = 16
        val spreadBottom = w / (cols * 1.3f)
        val spreadTop = w / (cols * 7f)
        for (i in -cols..cols) {
            val bx = cx + i * spreadBottom
            val tx = cx + i * spreadTop
            drawLine(gridColor, Offset(tx, horizon), Offset(bx, h), 1f)
        }

        // Horizontal lines that scroll toward the viewer (the "alive" cue).
        val rows = 14
        for (i in 0..rows) {
            val f = ((i + phase) % rows) / rows.toFloat()
            val y = horizon + (h - horizon) * f.pow(2.2f)
            if (y in horizon..h) {
                val a = (0.25f + 0.75f * f).coerceIn(0f, 1f)
                drawLine(gridColor.copy(alpha = gridColor.alpha * a), Offset(0f, y), Offset(w, y), 1f)
            }
        }

        // Horizon glow — sekarang halus, bukan pita terang.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(glow.copy(alpha = 0f), glow.copy(alpha = 0.16f * pulse), glow.copy(alpha = 0f)),
                startY = horizon - 30f, endY = horizon + 30f,
            ),
            topLeft = Offset(0f, horizon - 30f), size = Size(w, 60f),
        )

        // Concentric stage rings + a rotating highlight sweep.
        for (r in 1..3) {
            val rw = w * (0.16f * r)
            val rh = 16f * r
            drawArc(
                color = if (r % 2 == 0) tertiary.copy(alpha = 0.10f) else glow.copy(alpha = 0.11f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(cx - rw, horizon - rh),
                size = Size(rw * 2f, rh * 2f),
                style = Stroke(1.4f),
            )
        }
        val outerW = w * 0.52f
        val outerH = 52f
        drawArc(
            color = glow.copy(alpha = 0.30f * pulse),
            startAngle = phase * 360f, sweepAngle = 70f, useCenter = false,
            topLeft = Offset(cx - outerW, horizon - outerH),
            size = Size(outerW * 2f, outerH * 2f),
            style = Stroke(2.4f, cap = StrokeCap.Round),
        )
    }
}

// ── Top rail: brand + live clock/battery + actions ──────────────────────────
@Composable
private fun TopRail(
    gameCount: Int,
    snap: TelemetrySnapshot,
    vitalsOpen: Boolean,
    libraryOpen: Boolean,
    showPanelToggles: Boolean,
    onToggleVitals: () -> Unit,
    onToggleLibrary: () -> Unit,
    onAdd: () -> Unit,
    onDiag: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val time = rememberCurrentTime()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(26.dp).clip(chamferShape(8.dp, 3.dp)).background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) { Text("G", color = scheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp) }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("GAME", color = scheme.primary, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 10.sp)
            Spacer(Modifier.width(4.dp))
            Text("SPACE", color = scheme.onSurface, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text("$gameCount titles", color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(bottom = 1.dp))

        Spacer(Modifier.weight(1f))

        HubPill {
            Icon(Icons.Rounded.Schedule, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(time, color = scheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(6.dp))
        val bat = snap.batteryCapacity
        HubPill {
            Icon(
                if (snap.batteryCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull,
                null,
                tint = if (bat in 1..20) scheme.error else if (snap.batteryCharging) Color(0xFF4CAF50) else scheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (bat > 0) "$bat%" else "--", color = scheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            if (snap.batteryTempC > 0f) {
                Spacer(Modifier.width(5.dp))
                Text("%.0f°".format(snap.batteryTempC), color = batteryTempColor(snap.batteryTempC), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.width(4.dp))
        if (showPanelToggles) {
            // Toggle VITALS (sayap kiri) — menyala saat terbuka.
            RailIcon(
                res = R.drawable.ic_memory,
                desc = "Toggle vitals",
                active = vitalsOpen,
                onClick = onToggleVitals,
            )
            // Toggle LIBRARY (sayap kanan)
            RailIcon(
                res = R.drawable.materialsymbols_ic_view_list_rounded_filled,
                desc = "Toggle library",
                active = libraryOpen,
                onClick = onToggleLibrary,
            )
        }
        RailIcon(icon = Icons.Rounded.Add, desc = "Add game", onClick = onAdd)
        RailIcon(icon = Icons.Rounded.Info, desc = "Diagnostics", onClick = onDiag)
        RailIcon(res = R.drawable.materialsymbols_ic_monitoring_rounded_filled, desc = "FPS stats", onClick = onStats)
        RailIcon(res = R.drawable.materialsymbols_ic_settings_rounded_filled, desc = "Settings", onClick = onSettings)
    }
}

/** Tombol aksi 30dp yang rapat — konsisten dengan bahasa panel in-game. */
@Composable
private fun RailIcon(
    res: Int? = null,
    icon: ImageVector? = null,
    desc: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (active) scheme.primary else scheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) scheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (res != null) Icon(painterResource(res), desc, tint = tint, modifier = Modifier.size(17.dp))
        else if (icon != null) Icon(icon, desc, tint = tint, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun HubPill(content: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.surfaceContainerHigh)
            .border(0.5.dp, scheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ── Left column: live device vitals (real TelemetryBus data) ────────────────
@Composable
private fun DeviceVitalsColumn(snap: TelemetrySnapshot, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    val cpuDetail = buildList {
        val k = snap.cpuFreqMaxKhz
        if (k >= 1_000_000) add("%.1fG".format(k / 1_000_000.0))
        else if (k > 0) add("${k / 1000}M")
        if (snap.cpuTempC > 0f) add("%.0f°".format(snap.cpuTempC))
    }.joinToString(" · ").ifEmpty { null }

    val gpuDetail = buildList {
        if (snap.gpuFreqMhz > 0) add("${snap.gpuFreqMhz}M")
        if (snap.gpuTempC > 0f) add("%.0f°".format(snap.gpuTempC))
    }.joinToString(" · ").ifEmpty { null }

    val batDetail = buildList {
        if (snap.batteryPowerW > 0f) add("%.1fW".format(snap.batteryPowerW))
        if (snap.batteryTempC > 0f) add("%.0f°".format(snap.batteryTempC))
    }.joinToString(" · ").ifEmpty { null }

    // verticalScroll = jaring pengaman. Di device pendek, stack bisa lebih
    // tinggi dari panel: dulu ia meluber & kepotong, sekarang ia scroll halus.
    // Kalau muat (umumnya), scroll tidak aktif — tidak terasa sama sekali.
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(end = 2.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VITALS", color = scheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (snap.thermalHeadroomReduced) scheme.errorContainer else scheme.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    if (snap.thermalHeadroomReduced) "THR" else "OK",
                    color = if (snap.thermalHeadroomReduced) scheme.onErrorContainer else scheme.onPrimaryContainer,
                    fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                )
            }
        }

        // Empat baris ramping (bukan grid ring) → bobot visual turun drastis,
        // karakter gauge tetap lewat mini-ring 30dp. Warna ring = tonal role
        // (kohesif), yang bicara kesehatan = angka + warna suhu.
        VitalsRow("CPU", snap.cpuUsageTotal / 100f, "${snap.cpuUsageTotal}%", cpuDetail, heatColor(snap.cpuHeat), scheme.primary)
        VitalsRow("GPU", snap.gpuUsage / 100f, "${snap.gpuUsage}%", gpuDetail, heatColor(snap.gpuHeat), scheme.tertiary)
        VitalsRow("RAM", snap.ramPct / 100f, "%.1fG".format(snap.ramUsedGb), "%.0f%%".format(snap.ramPct), scheme.onSurfaceVariant, scheme.secondary)
        VitalsRow(
            "BAT", snap.batteryCapacity / 100f,
            if (snap.batteryCapacity > 0) "${snap.batteryCapacity}%" else "--",
            batDetail,
            if (snap.batteryCharging) Color(0xFF4CAF50) else batteryTempColor(snap.batteryTempC),
            scheme.primary,
        )

        // Footer NEMPEL di bawah stack (bukan didorong ke dasar layar seperti
        // dulu — itulah penyebab "CORE/GPU/DDR kepotong"). Divider tipis
        // menjadikannya "baris ringkasan", bukan kartu yang melayang.
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(scheme.outlineVariant.copy(alpha = 0.4f)))
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoStat("CORE", if (snap.cpuFreqMaxKhz > 0) "%.2fG".format(snap.cpuFreqMaxKhz / 1_000_000.0) else "—", Modifier.weight(1f))
            MonoStat("GPU", if (snap.gpuFreqMhz > 0) "${snap.gpuFreqMhz}M" else "—", Modifier.weight(1f))
            MonoStat("DDR", if (snap.ddrTempC > 0f) "%.0f°".format(snap.ddrTempC) else "—", Modifier.weight(1f))
        }
    }
}

@Composable
private fun VitalsRow(
    label: String, ratio: Float, value: String,
    detail: String?, detailColor: Color, ring: Color,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MiniRing(ratio, ring)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = ring, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Text(value, color = scheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (!detail.isNullOrEmpty()) {
                Text(detail, color = detailColor, fontFamily = FontFamily.Monospace, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** Ring murni tanpa teks di dalam — indikator visual kecil & tenang. */
@Composable
private fun MiniRing(ratio: Float, color: Color) {
    val anim by animateFloatAsState(ratio.coerceIn(0f, 1f), tween(600), label = "mr")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.size(30.dp)) {
        val s = 3.dp.toPx()
        val pad = s / 2f + 1.dp.toPx()
        val d = 30.dp.toPx() - pad * 2
        val tl = Offset(pad, pad)
        val sz = Size(d, d)
        drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
        drawArc(color, -90f, 360f * anim, false, tl, sz, style = Stroke(s, cap = StrokeCap.Round))
    }
}

@Composable
private fun MonoStat(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(label, color = scheme.onSurfaceVariant, fontSize = 7.sp, letterSpacing = 1.sp)
        Text(value, color = scheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ── Center stage: coverflow + actions ───────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CenterStage(
    games: List<AppRef>,
    refreshKey: Int,
    pagerState: androidx.compose.foundation.pager.PagerState,
    scope: CoroutineScope,
    bannerRepo: GameBannerRepository,
    bannerSource: BannerSource,
    onOpenSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val safeCurrent = pagerState.currentPage.coerceIn(0, (games.size - 1).coerceAtLeast(0))
    val focused = games.getOrNull(safeCurrent)

    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Kartu dominan; saat rail tertutup, maxWidth naik → kartu ikut besar.
            val cardW = (maxWidth * 0.84f).coerceAtLeast(200.dp)
            val sidePad = ((maxWidth - cardW) / 2f).coerceAtLeast(0.dp)
            val cardH = maxHeight * 0.96f

            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(cardW),
                contentPadding = PaddingValues(horizontal = sidePad),
                pageSpacing = 10.dp,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val offset = (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
                val abs = abs(offset).coerceIn(0f, 1f)
                val scale = lerp(1f, 0.82f, abs)
                val alpha = lerp(1f, 0.4f, abs)
                val isFocused = abs < 0.5f

                games.getOrNull(page)?.let { game ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex((1f - abs) * 10f)
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale; this.alpha = alpha
                                translationY = lerp(0f, 26.dp.toPx(), abs)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        CoverflowGameCard(
                            game = game,
                            isFocused = isFocused,
                            cardHeight = cardH,
                            bannerRepo = bannerRepo,
                            bannerSource = bannerSource,
                            refreshKey = refreshKey,
                            onActivate = {
                                if (isFocused) launchGame(context, game.packageName)
                                else scope.launch { pagerState.animateScrollToPage(page) }
                            },
                            onSettings = { onOpenSettings(game.packageName) },
                        )
                    }
                }
            }
        }

        // Page dots.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            games.forEachIndexed { i, _ ->
                val sel = i == safeCurrent
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.3f))
                        .then(if (sel) Modifier.size(14.dp, 3.dp) else Modifier.size(3.dp)),
                )
            }
        }

        // Action row — angular LAUNCH (chamfer) contrasts the rounded M3 chips.
        StageActions(
            focusedPkg = focused?.packageName,
            onLaunch = { focused?.let { launchGame(context, it.packageName) } },
            onTune = { focused?.let { onOpenSettings(it.packageName) } },
        )
    }
}

@Composable
private fun CoverflowGameCard(
    game: AppRef,
    isFocused: Boolean,
    cardHeight: Dp,
    bannerRepo: GameBannerRepository,
    bannerSource: BannerSource,
    refreshKey: Int,
    onActivate: () -> Unit,
    onSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    // Banner (same source of truth as the vertical cards).
    var bannerFile by remember(game.packageName, bannerSource) {
        mutableStateOf(bannerRepo.getCached(game.packageName)?.localPath?.let { File(it) })
    }
    LaunchedEffect(game.packageName, bannerSource) {
        bannerRepo.getOrFetch(game.packageName, bannerSource)?.localPath?.let { bannerFile = File(it) }
    }
    val bannerCache = com.ireddragonicy.gamespace.utils.rememberBannerBitmapCache()
    val bannerBitmap by remember(bannerFile) {
        derivedStateOf { bannerFile?.takeIf { it.exists() }?.let { bannerCache.get(it.absolutePath) } }
    }

    // Real per-game kicker (thermal profile name).
    val store = com.ireddragonicy.gamespace.utils.rememberPerAppStore()
    val tIdx = remember(game.packageName, refreshKey) { store.thermalProfile(game.packageName) }
    val tName = ThermalProfiles.PROFILE_NAMES.getOrElse(tIdx) { "Custom" }

    // Gentle floating only on the focused card.
    val float = rememberInfiniteTransition(label = "float_${game.packageName}")
    val floatV by float.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fv")
    val density = LocalDensity.current
    val floatPx = with(density) { 4.dp.toPx() }

    val cardShape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
            .then(
                if (isFocused) Modifier.shadow(20.dp, cardShape, ambientColor = scheme.primary.copy(0.5f), spotColor = scheme.primary.copy(0.5f))
                else Modifier
            )
            .graphicsLayer { if (isFocused) translationY = (floatV * 2f - 1f) * floatPx }
            .clip(cardShape)
            .background(scheme.surfaceContainerHigh, cardShape)
            .clickable { onActivate() },
    ) {
        // Banner / fallback gradient.
        if (bannerBitmap != null) {
            Image(
                bitmap = bannerBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f), scheme.surfaceContainerHigh)
                    )
                )
            )
        } else {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(scheme.primaryContainer.copy(alpha = 0.5f), scheme.surfaceContainerHigh)
                    )
                )
            )
        }

        // Top corner actions (only meaningful on focus; still tappable, consume the tap).
        if (isFocused) {
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                Box(
                    modifier = Modifier.size(30.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)).clickable { onSettings() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled), "Settings", tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }

        // Bottom identity block.
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            // Scroll-reveal detail only on the focused card.
            if (isFocused) {
                Text(tName.uppercase(), color = scheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = rememberDrawablePainter(game.icon),
                    contentDescription = null,
                    modifier = Modifier.size(if (isFocused) 46.dp else 34.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    game.label,
                    color = if (bannerBitmap != null) Color.White else scheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isFocused) 22.sp else 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFocused) {
                key(game.packageName) {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(280)) + slideInVertically(tween(320)) { it / 3 },
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            GameFeatureRow(game.packageName, refreshKey)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageActions(
    focusedPkg: String?,
    onLaunch: () -> Unit,
    onTune: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val launchShape = chamferShape(14.dp, 4.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SATU aksi sekunder. Dulu PERF & TUNE membuka halaman yang sama,
        // jadi satu tombol "TUNE" menghapus duplikat tanpa kehilangan fungsi.
        SecondaryStageButton(R.drawable.materialsymbols_ic_tune_rounded_filled, "TUNE", onTune)

        Box(
            modifier = Modifier
                .weight(1f).height(46.dp)
                .graphicsLayer {
                    scaleX = if (pressed) 0.98f else 1f
                    scaleY = if (pressed) 0.98f else 1f
                }
                .shadow(if (pressed) 5.dp else 14.dp, launchShape, ambientColor = scheme.primary.copy(0.5f), spotColor = scheme.primary.copy(0.5f))
                .background(scheme.primary, launchShape)
                .clickable(interactionSource = interaction, indication = null, enabled = focusedPkg != null, onClick = onLaunch),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.materialsymbols_ic_rocket_launch_rounded_filled), null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("LAUNCH", color = scheme.onPrimary, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SecondaryStageButton(icon: Int, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = chamferShape(10.dp, 3.dp)
    Column(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(scheme.surfaceContainerHigh, shape)
            .border(0.5.dp, scheme.outlineVariant, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), label, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(label, color = scheme.onSurfaceVariant, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

// ── Right rail: navigable library (highlight follows the pager) ─────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRail(
    games: List<AppRef>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    scope: CoroutineScope,
    onOpenSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val current = pagerState.currentPage.coerceIn(0, (games.size - 1).coerceAtLeast(0))
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer.copy(alpha = 0.55f))
            .padding(8.dp),
    ) {
        Text("LIBRARY", color = scheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 8.sp, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            games.forEachIndexed { i, game ->
                val sel = i == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) scheme.primaryContainer else Color.Transparent)
                        .clickable { scope.launch { pagerState.animateScrollToPage(i) } }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = rememberDrawablePainter(game.icon),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        game.label,
                        color = if (sel) scheme.onPrimaryContainer else scheme.onSurface,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = painterResource(R.drawable.materialsymbols_ic_settings_rounded_filled),
                        "Settings", tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(13.dp).clickable { onOpenSettings(game.packageName) },
                    )
                }
            }
        }
    }
}

// ── Landscape empty state ───────────────────────────────────────────────────
@Composable
private fun EmptyLandscape(onAdd: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.materialsymbols_ic_joystick_rounded_filled),
                null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("No games in your space yet", color = scheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("Add a title to build your horizontal command center", color = scheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd, shape = chamferShape(12.dp, 4.dp)) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add game", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}
