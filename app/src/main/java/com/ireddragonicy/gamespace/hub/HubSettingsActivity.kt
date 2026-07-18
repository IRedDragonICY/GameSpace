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

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.PanelTheme
import com.ireddragonicy.gamespace.gamebar.chamferShape
import com.ireddragonicy.gamespace.gamebar.glassBrush
import com.ireddragonicy.gamespace.settings.SettingsActivity
import com.ireddragonicy.gamespace.settings.fpsstats.FpsStatsActivity
import kotlin.math.roundToInt

/**
 * ROG-styled GameSpace settings — the modern face of the old preference
 * screen. Reads/writes the SAME default-SharedPreferences keys the legacy
 * XML prefs used, so both stay in sync and no logic is duplicated. The
 * legacy screen remains only as the editor for the quick-start app picker.
 */
class HubSettingsActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialExpressiveTheme(
                colorScheme = dynamicDarkColorScheme(this),
                motionScheme = MotionScheme.expressive(),
            ) {
                HubSettingsScreen()
            }
        }
    }
}

// ── Row models — one line per setting, same keys as root_preferences.xml ──

private sealed interface SettingRow
private data class Section(val title: String) : SettingRow
private data class Toggle(
    val key: String, val title: String, val subtitle: String, val default: Boolean,
) : SettingRow
private data class Choice(
    val key: String, val title: String, val options: List<Pair<String, String>>,
    val default: String,
) : SettingRow
private data class IntSlider(
    val key: String, val title: String, val min: Int, val max: Int, val default: Int,
) : SettingRow
private data class Link(
    val title: String, val subtitle: String, val iconRes: Int,
    val action: (android.content.Context) -> Unit,
) : SettingRow

private val SETTING_ROWS: List<SettingRow> = listOf(
    Section("LAUNCH OPTIMIZATION"),
    Toggle(
        "game_memory_management", "Smart Memory Management",
        "Clear background apps when launching games", false,
    ),
    Toggle(
        "game_cache_management", "Cache Management",
        "Optimize game cache for faster loading", true,
    ),
    Link("FPS Stats", "View recorded performance sessions",
        R.drawable.materialsymbols_ic_monitoring_rounded_filled) {
        it.startActivity(Intent(it, FpsStatsActivity::class.java))
    },

    Section("IN-GAME EXPERIENCE"),
    Choice(
        "gamespace_panel_color_mode", "Panel Accent",
        listOf(
            "0" to "Material You", "1" to "Cyan", "2" to "Electric Blue",
            "3" to "Emerald", "4" to "Sunset Orange", "5" to "Royal Purple",
            "6" to "Cherry Red", "7" to "Golden", "8" to "Arctic White",
        ),
        "0",
    ),
    Toggle(
        "gamespace_auto_brightness_disabled", "Disable Auto Brightness",
        "Lock brightness while in game", true,
    ),
    Toggle("gamespace_stay_awake", "Stay Awake", "Keep screen on during games", false),
    Toggle(
        "gamespace_danmaku_notification_mode", "Danmaku Notifications",
        "Scrolling bullet-style notifications", true,
    ),
    Toggle(
        "gamespace_pulse_bass_haptics_disabled", "Disable Bass Haptics",
        "No pulse haptics from game audio", false,
    ),
    Toggle("gamespace_auto_dnd", "Auto DND", "Do Not Disturb during game sessions", false),
    Toggle("call_overlay_enabled", "Call Overlay", "Show in-game call banner", true),
    Choice(
        "gamespace_calls_mode", "Incoming Calls",
        listOf("0" to "No action", "1" to "Auto answer", "2" to "Auto reject"),
        "0",
    ),
    Choice(
        "gamespace_ringer_mode", "Ringer Mode",
        listOf(
            "0" to "Silent", "1" to "Vibrate", "2" to "Normal", "3" to "No change",
        ),
        "3",
    ),
    Toggle(
        "gamespace_tfgesture_disabled", "Block 3-Finger Screenshot",
        "Prevent accidental screenshot gesture", false,
    ),
    Toggle("gamespace_lock_gesture", "Lock Gestures", "Block navigation gestures", false),
    Toggle("bypass_charge_enabled", "Bypass Charging", "Power the SoC directly in game", false),
    IntSlider("gamespace_icon_idle_alpha", "Idle Bar Opacity", 5, 100, 25),

    Section("LIBRARY"),
    Link("Quick Start Apps", "Sidebar shortcut apps (legacy editor)",
        R.drawable.materialsymbols_ic_apps_rounded_filled) {
        it.startActivity(Intent(it, SettingsActivity::class.java))
    },
)

@Composable
private fun HubSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(PanelTheme.BaseDark, PanelTheme.BaseDeep))),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(
                            R.drawable.materialsymbols_ic_settings_rounded_filled
                        ),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "SETTINGS",
                        color = PanelTheme.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            items(SETTING_ROWS.size) { i ->
                when (val row = SETTING_ROWS[i]) {
                    is Section -> SectionHeader(row.title, accent)
                    is Toggle -> ToggleCard(row, prefs, accent)
                    is Choice -> ChoiceCard(row, prefs, accent)
                    is IntSlider -> SliderCard(row, prefs, accent)
                    is Link -> LinkCard(row, accent)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    ) {
        Box(Modifier.size(width = 3.dp, height = 12.dp).background(accent))
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
private fun settingCardModifier(accent: Color): Modifier {
    val shape = remember { chamferShape(bigCut = 10.dp, smallCut = 4.dp) }
    return Modifier
        .fillMaxWidth()
        .background(glassBrush(accent), shape)
        .padding(horizontal = 14.dp, vertical = 10.dp)
}

@Composable
private fun ToggleCard(row: Toggle, prefs: android.content.SharedPreferences, accent: Color) {
    var checked by remember { mutableStateOf(prefs.getBoolean(row.key, row.default)) }
    Row(
        modifier = settingCardModifier(accent).clickable {
            checked = !checked
            prefs.edit().putBoolean(row.key, checked).apply()
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, color = PanelTheme.TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Text(row.subtitle, color = PanelTheme.TextDim, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                prefs.edit().putBoolean(row.key, it).apply()
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                checkedThumbColor = Color.Black,
            ),
        )
    }
}

@Composable
private fun ChoiceCard(row: Choice, prefs: android.content.SharedPreferences, accent: Color) {
    var value by remember { mutableStateOf(prefs.getString(row.key, row.default) ?: row.default) }
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = settingCardModifier(accent).clickable { open = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.title, color = PanelTheme.TextPrimary, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
        Box {
            Text(
                text = row.options.find { it.first == value }?.second ?: value,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(PanelTheme.BaseDark),
            ) {
                row.options.forEach { (v, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (v == value) accent else PanelTheme.TextPrimary,
                                fontSize = 12.sp,
                            )
                        },
                        onClick = {
                            value = v
                            prefs.edit().putString(row.key, v).apply()
                            open = false
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            painter = painterResource(R.drawable.materialsymbols_ic_expand_more_rounded_filled),
            contentDescription = null,
            tint = PanelTheme.TextDim,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun SliderCard(row: IntSlider, prefs: android.content.SharedPreferences, accent: Color) {
    var value by remember { mutableFloatStateOf(prefs.getInt(row.key, row.default).toFloat()) }
    Column(modifier = settingCardModifier(accent)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.title, color = PanelTheme.TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Text(
                "${value.roundToInt()}%", color = accent, fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = row.min.toFloat()..row.max.toFloat(),
            onValueChangeFinished = {
                prefs.edit().putInt(row.key, value.roundToInt()).apply()
            },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.08f),
            ),
            modifier = Modifier.height(24.dp),
        )
    }
}

@Composable
private fun LinkCard(row: Link, accent: Color) {
    val context = LocalContext.current
    Row(
        modifier = settingCardModifier(accent).clickable { row.action(context) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(row.iconRes),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, color = PanelTheme.TextPrimary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Text(row.subtitle, color = PanelTheme.TextDim, fontSize = 10.sp)
        }
        Icon(
            painter = painterResource(
                R.drawable.materialsymbols_ic_chevron_right_rounded_filled
            ),
            contentDescription = null,
            tint = PanelTheme.TextDim,
            modifier = Modifier.size(16.dp),
        )
    }
}
