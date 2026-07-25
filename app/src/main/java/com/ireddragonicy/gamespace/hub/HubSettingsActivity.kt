/* --- gamespace/hub/HubSettingsActivity.kt --- */
package com.ireddragonicy.gamespace.hub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.rememberDrawablePainter
import com.ireddragonicy.gamespace.settings.fpsstats.FpsStatsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class HubSettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialExpressiveTheme(
                colorScheme = dynamicDarkColorScheme(this),
                motionScheme = MotionScheme.expressive(),
            ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showQuickStartDialog by remember { mutableStateOf(false) }

    val settingRows = remember {
        listOf(
            Section("Launch Optimization"),
            Toggle("game_memory_management", "Smart Memory Management", "Clear background apps when launching games", false),
            Toggle("game_cache_management", "Cache Management", "Optimize game cache for faster loading", true),
            Link("FPS Stats", "View recorded performance sessions", R.drawable.materialsymbols_ic_monitoring_rounded_filled) {
                context.startActivity(Intent(context, FpsStatsActivity::class.java))
            },
            Link("Custom Thermal Profiles", "Create per-temperature throttle profiles with one-click presets", R.drawable.materialsymbols_ic_monitoring_rounded_filled) {
                com.ireddragonicy.gamespace.thermal.custom.CustomProfileListActivity.start(context)
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

            Section("Library"),
            Link("Quick Start Apps", "Sidebar shortcut apps", R.drawable.materialsymbols_ic_apps_rounded_filled) {
                showQuickStartDialog = true
            }
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
                    is Section -> SectionHeader(row.title)
                    is Toggle -> ToggleItem(row, prefs)
                    is Choice -> ChoiceItem(row, prefs)
                    is IntSlider -> SliderItem(row, prefs)
                    is Link -> LinkItem(row)
                }
            }
        }
    }

    if (showQuickStartDialog) {
        QuickStartAppsDialog(
            prefs = prefs,
            onDismiss = { showQuickStartDialog = false }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun ToggleItem(row: Toggle, prefs: android.content.SharedPreferences) {
    var checked by remember { mutableStateOf(prefs.getBoolean(row.key, row.default)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                checked = !checked
                prefs.edit().putBoolean(row.key, checked).apply()
            }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChoiceItem(row: Choice, prefs: android.content.SharedPreferences) {
    var value by remember { mutableStateOf(prefs.getString(row.key, row.default) ?: row.default) }
    var expanded by remember { mutableStateOf(false) }
    
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = row.options.find { it.first == value }?.second ?: value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            row.options.forEach { (v, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        value = v
                        prefs.edit().putString(row.key, v).apply()
                        expanded = false
                    },
                    colors = if (v == value) MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary) else MenuDefaults.itemColors()
                )
            }
        }
    }
}

@Composable
private fun SliderItem(row: IntSlider, prefs: android.content.SharedPreferences) {
    var value by remember { mutableFloatStateOf(prefs.getInt(row.key, row.default).toFloat()) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = row.min.toFloat()..row.max.toFloat(),
            onValueChangeFinished = { prefs.edit().putInt(row.key, value.roundToInt()).apply() }
        )
    }
}

@Composable
private fun LinkItem(row: Link) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { row.action() }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(row.iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Native Compose Quick Start App Picker ──
private data class AppInfo(val packageName: String, val name: String, val icon: Drawable)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickStartAppsDialog(prefs: android.content.SharedPreferences, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(intent, 0).mapNotNull { resolveInfo ->
                try {
                    val appInfo = pm.getApplicationInfo(resolveInfo.activityInfo.packageName, 0)
                    AppInfo(
                        packageName = appInfo.packageName,
                        name = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo)
                    )
                } catch (e: Exception) { null }
            }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
            
            val saved = prefs.getString("quick_start_apps", "") ?: ""
            val savedSet = saved.split(",").filter { it.isNotBlank() }.toSet()

            withContext(Dispatchers.Main) {
                installedApps = apps
                selectedPackages = savedSet
                isLoading = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Select Quick Start Apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(installedApps.size) { i ->
                        val app = installedApps[i]
                        val isSelected = selectedPackages.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedPackages = if (isSelected) selectedPackages - app.packageName
                                    else selectedPackages + app.packageName
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = rememberDrawablePainter(app.icon),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(app.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Checkbox(checked = isSelected, onCheckedChange = null)
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    prefs.edit().putString("quick_start_apps", selectedPackages.joinToString(",")).apply()
                    onDismiss()
                }) { Text("Save") }
            }
        }
    }
}
