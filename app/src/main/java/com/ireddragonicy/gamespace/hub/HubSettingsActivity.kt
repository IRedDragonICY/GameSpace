/* --- gamespace/hub/HubSettingsActivity.kt --- */
package com.ireddragonicy.gamespace.hub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.ui.settings.*
import com.ireddragonicy.gamespace.settings.fpsstats.FpsStatsActivity
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.ui.theme.AppAccentPicker
import com.ireddragonicy.gamespace.ui.theme.AppThemeMode
import com.ireddragonicy.gamespace.ui.theme.AppThemeState
import com.ireddragonicy.gamespace.ui.theme.GameSpaceTheme
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import kotlin.math.roundToInt

class HubSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GameSpaceTheme {
                HubSettingsScreen(onBack = { finish() })
            }
        }
    }
}

// ── Models ──
private sealed interface SettingRow
private data class Section(val title: String) : SettingRow
private data class Toggle(val key: String, val title: String, val subtitle: String, val default: Boolean) : SettingRow
private data class Choice(val key: String, val title: String, val options: List<Pair<String, String>>, val default: String) : SettingRow
private data class IntSlider(val key: String, val title: String, val min: Int, val max: Int, val default: Int) : SettingRow
private data class Link(val title: String, val subtitle: String, val iconRes: Int, val action: () -> Unit) : SettingRow
private object ThemeModeRow : SettingRow
private object AppAccentRow : SettingRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val appSettings = remember { AppSettings(context.applicationContext) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val settingRows = remember {
        listOf(
            Section("Appearance"),
            ThemeModeRow,
            AppAccentRow,
            Section("Launch Optimization"),
            Toggle("game_memory_management", "Smart Memory Management", "Clear background apps when launching games", false),
            Toggle("game_cache_management", "Cache Management", "Optimize game cache for faster loading", true),
            Link("FPS Stats", "View recorded performance sessions", R.drawable.materialsymbols_ic_monitoring_rounded_filled) {
                context.startActivity(Intent(context, FpsStatsActivity::class.java))
            },
            Link("Custom Thermal Profiles", "Create per-temperature throttle profiles with one-click presets", R.drawable.materialsymbols_ic_monitoring_rounded_filled) {
                com.ireddragonicy.gamespace.thermal.custom.CustomProfileListActivity.start(context)
            },
            Link("Charging Profiles", "Create custom charging curves — full control over current & heat", R.drawable.materialsymbols_ic_monitoring_rounded_filled) {
                com.ireddragonicy.gamespace.charging.ChargingProfileListActivity.start(context)
            },

            Section("In-Game Experience"),
            Choice("gamespace_panel_color_mode", "Panel Accent", listOf(
                "0" to "Material You", "1" to "Cyan", "2" to "Electric Blue", "3" to "Emerald",
                "4" to "Sunset Orange", "5" to "Royal Purple", "6" to "Cherry Red", "7" to "Golden", "8" to "Arctic White"
            ), "0"),
            Toggle("gamespace_auto_brightness_disabled", "Disable Auto Brightness", "Lock brightness while in game", true),
            Toggle("gamespace_stay_awake", "Stay Awake", "Keep screen on during games", false),
            Toggle("gamespace_danmaku_notification_mode", "Danmaku Notifications", "Scrolling bullet-style notifications", true),
            Toggle("gamespace_pulse_bass_haptics_disabled", "Disable Bass Haptics", "No pulse haptics from game audio", false),
            Toggle("gamespace_auto_dnd", "Auto DND", "Do Not Disturb during game sessions", false),
            Toggle("call_overlay_enabled", "Call Overlay", "Show in-game call banner", true),
            Choice("gamespace_calls_mode", "Incoming Calls", listOf("0" to "No action", "1" to "Auto answer", "2" to "Auto reject"), "0"),
            Choice("gamespace_ringer_mode", "Ringer Mode", listOf("0" to "Silent", "1" to "Vibrate", "2" to "Normal", "3" to "No change"), "3"),
            Toggle("gamespace_tfgesture_disabled", "Block 3-Finger Screenshot", "Prevent accidental screenshot gesture", false),
            Toggle("gamespace_lock_gesture", "Lock Gestures", "Block navigation gestures", false),
            Toggle("bypass_charge_enabled", "Bypass Charging", "Power the SoC directly in game", false),
            IntSlider("gamespace_icon_idle_alpha", "Idle Bar Opacity", 5, 100, 25),

            Section("Sidebar"),
            Link("Sidebar Studio", "Shape, position, buttons, theme — with live preview", R.drawable.materialsymbols_ic_select_window_rounded_filled) {
                com.ireddragonicy.gamespace.settings.SidebarSettingsActivity.start(context)
            },
            Choice(
                key = "gamespace_banner_source",
                title = "Banner Source",
                options = listOf(
                    "playstore" to "Play Store",
                    "gamespace_art" to "GameSpace Art (coming soon)",
                ),
                default = "playstore",
            ),
            Toggle(
                key = "gamespace_banner_auto_fetch",
                title = "Auto-fetch Banners",
                subtitle = "Download hero banner when a game is added",
                default = true,
            ),
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Game Space Settings", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues
        ) {
            items(settingRows.size) { i ->
                when (val row = settingRows[i]) {
                    is Section -> SettingsSection(row.title)
                    is ThemeModeRow -> ThemeModeItem(appSettings)
                    is AppAccentRow -> AppAccentItem(appSettings)
                    is Toggle -> {
                        var checked by remember { mutableStateOf(prefs.getBoolean(row.key, row.default)) }
                        SettingsSwitchRow(
                            title = row.title,
                            subtitle = row.subtitle,
                            checked = checked,
                            onCheckedChange = {
                                checked = it
                                prefs.edit().putBoolean(row.key, it).apply()
                            },
                        )
                    }
                    is Choice -> {
                        var value by remember { mutableStateOf(prefs.getString(row.key, row.default) ?: row.default) }
                        SettingsDropdownRow(
                            label = row.title,
                            valueText = row.options.find { it.first == value }?.second ?: value,
                            options = row.options,
                            isSelected = { it.first == value },
                            onSelect = {
                                value = it.first
                                prefs.edit().putString(row.key, it.first).apply()
                            },
                            renderItem = { opt -> Text(opt.second) },
                        )
                    }
                    is IntSlider -> {
                        var value by remember { mutableFloatStateOf(prefs.getInt(row.key, row.default).toFloat()) }
                        SettingsSliderRow(
                            label = row.title,
                            value = value,
                            range = row.min.toFloat()..row.max.toFloat(),
                            onCommit = { prefs.edit().putInt(row.key, it.roundToInt()).apply() },
                            valueText = { "${it.roundToInt()}%" },
                        )
                    }
                    is Link -> SettingsLinkRow(
                        title = row.title,
                        subtitle = row.subtitle,
                        onClick = row.action,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModeItem(appSettings: AppSettings) {
    // Baca dari state observable → segmented button ikut pindah saat berubah
    val mode = AppThemeState.themeMode
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(
            "App Theme",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Auto follows the system · Light & Dark are forced",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(
                Triple("Auto", Icons.Rounded.BrightnessAuto, AppThemeMode.AUTO),
                Triple("Light", Icons.Rounded.LightMode, AppThemeMode.LIGHT),
                Triple("Dark", Icons.Rounded.DarkMode, AppThemeMode.DARK),
            )
            options.forEachIndexed { i, (label, icon, value) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = { appSettings.appThemeMode = value },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                ) { Text(label, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun AppAccentItem(appSettings: AppSettings) {
    val mode = AppThemeState.colorMode          // observable → live
    val customArgb = AppThemeState.customColor
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(
            "Accent Color",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Applied to every Game Space screen",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        AppAccentPicker(
            mode = mode,
            customArgb = customArgb,
            onMode = { appSettings.appColorMode = it },
            onCustomColor = { argb, persist -> appSettings.setAppCustomColor(argb, persist) },
        )
    }
}
