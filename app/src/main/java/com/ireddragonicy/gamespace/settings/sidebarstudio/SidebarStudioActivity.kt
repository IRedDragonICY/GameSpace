/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(ExperimentalMaterial3Api::class)
package com.ireddragonicy.gamespace.settings.sidebarstudio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.CornerStyle
import com.ireddragonicy.gamespace.data.EdgePreset
import com.ireddragonicy.gamespace.data.HandleAspect
import com.ireddragonicy.gamespace.data.HandleFill
import com.ireddragonicy.gamespace.data.HandlePreset
import com.ireddragonicy.gamespace.data.IconShape
import com.ireddragonicy.gamespace.data.LabelMode
import com.ireddragonicy.gamespace.data.SidebarStyle
import com.ireddragonicy.gamespace.data.SidebarStyleStore
import com.ireddragonicy.gamespace.data.SidebarStyleTransfer
import com.ireddragonicy.gamespace.data.StripAccentMode
import com.ireddragonicy.gamespace.data.StripButtonSpec
import com.ireddragonicy.gamespace.data.StripLayout
import com.ireddragonicy.gamespace.data.StripPosition
import com.ireddragonicy.gamespace.gamebar.handleShape
import com.ireddragonicy.gamespace.gamebar.readableOn
import com.ireddragonicy.gamespace.ui.settings.SettingsCard
import com.ireddragonicy.gamespace.ui.settings.SettingsDivider
import com.ireddragonicy.gamespace.ui.settings.SettingsSection
import com.ireddragonicy.gamespace.ui.theme.APP_ACCENT_PRESETS
import com.ireddragonicy.gamespace.ui.theme.AppAccentPicker
import com.ireddragonicy.gamespace.ui.theme.APP_COLOR_MODE_CUSTOM
import com.ireddragonicy.gamespace.ui.theme.GameSpaceTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint(ComponentActivity::class)
class SidebarStudioActivity : Hilt_SidebarStudioActivity() {
    @Inject lateinit var store: SidebarStyleStore
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GameSpaceTheme { StudioScreen(store) { finish() } } }
    }
    companion object { fun start(context: Context) { context.startActivity(Intent(context, SidebarStudioActivity::class.java)) } }
}

private data class ChipItem<T>(
    val value: T,
    val label: String,
    val shape: @Composable (sel: Boolean, accent: Color, scheme: androidx.compose.material3.ColorScheme) -> Unit,
)

@Composable
private fun <T> VisualChipRow(
    items: List<ChipItem<T>>,
    selected: T,
    target: EditTarget,
    focus: (EditTarget) -> Unit,
    onSelect: (T) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val sel = item.value == selected
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (sel) accent.copy(0.18f) else scheme.surfaceContainerHigh)
                    .border(if (sel) 1.dp else 0.5.dp, if (sel) accent else scheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable { focus(target); onSelect(item.value) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.width(20.dp).height(18.dp), contentAlignment = Alignment.Center) {
                    item.shape(sel, accent, scheme)
                }
                Text(
                    item.label,
                    color = if (sel) accent else scheme.onSurface,
                    fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun MiniHandle(preset: HandlePreset, sel: Boolean, accent: Color, scheme: androidx.compose.material3.ColorScheme) {
    val g = preset.geometry
    val fill = if (sel) accent else scheme.onSurfaceVariant
    val shape = handleShape(g, dockedOnLeft = false)
    val (w, h) = when (g.aspect) {
        HandleAspect.LINE -> 3.dp to 16.dp
        HandleAspect.DOT -> 14.dp to 14.dp
        else -> 9.dp to 16.dp
    }
    if (g.fill == HandleFill.NONE) {
        Icon(
            painterResource(R.drawable.materialsymbols_ic_chevron_right_rounded_filled),
            null,
            tint = fill,
            modifier = Modifier.size(14.dp),
        )
    } else {
        Box(
            Modifier.width(w).height(h).clip(shape).background(fill)
                .then(if (g.outline) Modifier.border(0.5.dp, Color.White.copy(0.35f), shape) else Modifier),
        )
    }
}

@Composable
private fun MiniEdge(preset: EdgePreset, sel: Boolean, accent: Color, scheme: androidx.compose.material3.ColorScheme) {
    val c = if (sel) accent else scheme.onSurfaceVariant
    val sh = RoundedCornerShape(3.dp)
    Box(
        Modifier.width(22.dp).height(14.dp).clip(sh).background(scheme.surfaceContainerHighest).then(
            when (preset) {
                EdgePreset.NEON -> Modifier.border(3.dp, c.copy(0.25f), sh).border(1.5.dp, c.copy(0.85f), sh)
                EdgePreset.GLOW -> Modifier.border(2.dp, c.copy(0.6f), sh)
                EdgePreset.MINIMAL -> Modifier.border(1.dp, c.copy(0.7f), sh)
                EdgePreset.NONE -> Modifier.border(0.5.dp, c.copy(0.3f), sh)
                EdgePreset.CUSTOM -> Modifier.border(1.5.dp, c, sh)
            }),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioScreen(store: SidebarStyleStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val style = store.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var activeTarget by remember { mutableStateOf<EditTarget?>(null) }
    val focus = { t: EditTarget -> activeTarget = t }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = scheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Sidebar Studio", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { store.reset(); scope.launch { snackbar.showSnackbar("Reset to default") } }) {
                        Icon(Icons.Rounded.Refresh, "Reset")
                    }
                    IconButton(onClick = { SidebarStyleTransfer.share(context, style) }) {
                        Icon(Icons.Rounded.Share, "Share")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = scheme.background,
                    scrolledContainerColor = scheme.surfaceContainer,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            SidebarPreview(style = style, activeTarget = activeTarget)
            Text(
                "Tip: tap any control below — the matching part lights up in the preview.",
                color = scheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            SettingsSection("Handle (trigger)")
            SettingsCard {
                VisualChipRow(
                    HandlePreset.entries.map { p ->
                        ChipItem(p, p.label) { sel, ac, sc -> MiniHandle(p, sel, ac, sc) }
                    },
                    style.handlePreset,
                    EditTarget.HANDLE,
                    focus,
                ) { store.mutate { s -> s.copy(handlePreset = it) } }
                SettingsDivider()
                StudioSlider("Width", style.handleWidthDp, 8..24, "dp", EditTarget.HANDLE, focus) {
                    store.mutate { s -> s.copy(handleWidthDp = it) }
                }
                SettingsDivider()
                StudioSlider("Height", style.handleHeightDp, 20..80, "dp", EditTarget.HANDLE, focus) {
                    store.mutate { s -> s.copy(handleHeightDp = it) }
                }
                SettingsDivider()
                StudioSlider("Idle opacity", style.handleIdleAlpha, 10..100, "%", EditTarget.HANDLE, focus) {
                    store.mutate { s -> s.copy(handleIdleAlpha = it) }
                }
                SettingsDivider()
                StudioSlider("Vertical offset", style.handleOffsetY, -120..120, "dp", EditTarget.HANDLE, focus) {
                    store.mutate { s -> s.copy(handleOffsetY = it) }
                }
                SettingsDivider()
                VisualChipRow(
                    HandleFill.entries.map { f ->
                        ChipItem(f, f.label) { sel, ac, sc ->
                            val col = when (f) {
                                HandleFill.DARK_GLASS -> Color(0xCC1A1A1A)
                                HandleFill.FOLLOW_ACCENT -> ac.copy(0.85f)
                                HandleFill.CUSTOM -> Color(style.handleColor)
                                HandleFill.NONE -> sc.surfaceContainerHighest
                            }
                            Box(
                                Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(col)
                                    .border(0.5.dp, if (f == HandleFill.NONE) sc.outlineVariant else Color.White.copy(0.3f), RoundedCornerShape(4.dp)),
                            )
                        }
                    },
                    style.effectiveHandle().fill,
                    EditTarget.HANDLE,
                    focus,
                ) { fill ->
                    store.mutate { s -> s.copy(handleGeometry = s.effectiveHandle().copy(fill = fill)) }
                }
                if (style.effectiveHandle().fill == HandleFill.CUSTOM) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        APP_ACCENT_PRESETS.forEach { c ->
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(c)
                                    .border(1.dp, if (Color(style.handleColor) == c) scheme.onSurface else Color.Transparent, CircleShape)
                                    .clickable { store.mutate { s -> s.copy(handleColor = c.toArgb()) } },
                            )
                        }
                    }
                }
                SettingsDivider()
                StudioSwitch("Outline", style.effectiveHandle().outline, EditTarget.HANDLE, focus) {
                    store.mutate { s -> s.copy(handleGeometry = s.effectiveHandle().copy(outline = it)) }
                }
                SettingsDivider()
                StudioSwitch("Chevron glyph", style.effectiveHandle().showGlyph, EditTarget.HANDLE, focus) {
                    store.mutate { s -> s.copy(handleGeometry = s.effectiveHandle().copy(showGlyph = it)) }
                }
                SettingsDivider()
                StudioSlider("Idle opacity", style.handleIdleAlpha, 0..100, "%", EditTarget.HANDLE, focus) {
                    store.mutate { s -> s.copy(handleIdleAlpha = it) }
                }
                SettingsDivider()
                StudioSlider("Idle timeout", style.handleIdleTimeoutSec, 1..10, "s", EditTarget.HANDLE, focus) {
                    store.mutate { s -> s.copy(handleIdleTimeoutSec = it) }
                }
                SettingsDivider()
                StudioSwitch("Auto-hide on idle", style.handleAutoHide, EditTarget.HANDLE, focus) {
                    store.mutate { s -> s.copy(handleAutoHide = it) }
                }
            }

            SettingsSection("Edge (chrome)")
            SettingsCard {
                VisualChipRow(
                    EdgePreset.entries.map { p ->
                        ChipItem(p, p.label) { sel, ac, sc -> MiniEdge(p, sel, ac, sc) }
                    },
                    style.edgePreset,
                    EditTarget.EDGE,
                    focus,
                ) { store.mutate { s -> s.copy(edgePreset = it) } }
                SettingsDivider()
                StudioSlider("Intensity", style.edgeAlpha, 0..100, "%", EditTarget.EDGE, focus) {
                    store.mutate { s -> s.copy(edgeAlpha = it) }
                }
                if (style.edgePreset == EdgePreset.CUSTOM) {
                    SettingsDivider()
                    StudioSwitch("Top rail", style.effectiveEdge().topRail, EditTarget.EDGE, focus) {
                        store.mutate { s -> s.copy(edgeGeometry = s.effectiveEdge().copy(topRail = it)) }
                    }
                    SettingsDivider()
                    StudioSwitch("Corner blades", style.effectiveEdge().cornerBlades, EditTarget.EDGE, focus) {
                        store.mutate { s -> s.copy(edgeGeometry = s.effectiveEdge().copy(cornerBlades = it)) }
                    }
                }
            }

            SettingsSection("Labels")
            SettingsCard {
                StudioSegmented(
                    "Mode",
                    LabelMode.entries.map { it.key to it.label },
                    style.labelMode.key,
                    EditTarget.LABEL,
                    focus,
                ) { k ->
                    val mode = LabelMode.fromKey(k)
                    store.mutate { s -> s.copy(labelMode = mode) }
                    if (mode == LabelMode.INLINE) store.mutate { s -> s.copy(showLabels = true) }
                    else store.mutate { s -> s.copy(showLabels = false) }
                }
                SettingsDivider()
                StudioSlider("Label size", style.labelSizeSp, 7..13, "sp", EditTarget.LABEL, focus) {
                    store.mutate { s -> s.copy(labelSizeSp = it) }
                }
            }

            SettingsSection("Layout")
            SettingsCard {
                StudioSegmented(
                    "Orientation",
                    StripLayout.entries.map { it.key to it.label },
                    style.layout.key,
                    EditTarget.STRIP,
                    focus,
                ) { store.mutate { s -> s.copy(layout = StripLayout.fromKey(it)) } }
                SettingsDivider()
                StudioSegmented(
                    "Position",
                    StripPosition.entries.map { it.key to it.label },
                    style.position.key,
                    EditTarget.STRIP,
                    focus,
                ) { store.mutate { s -> s.copy(position = StripPosition.fromKey(it)) } }
                SettingsDivider()
                StudioSlider(
                    if (style.layout == StripLayout.VERTICAL) "Rail width" else "Bar height",
                    style.stripWidthDp,
                    44..88,
                    "dp",
                    EditTarget.STRIP,
                    focus,
                ) { store.mutate { s -> s.copy(stripWidthDp = it) } }
                SettingsDivider()
                StudioSlider("Item spacing", style.itemSpacingDp, 2..16, "dp", EditTarget.STRIP, focus) {
                    store.mutate { s -> s.copy(itemSpacingDp = it) }
                }
            }

            SettingsSection("Shape")
            SettingsCard {
                StudioSegmented(
                    "Container corner",
                    CornerStyle.entries.map { it.key to it.label },
                    style.cornerStyle.key,
                    EditTarget.CONTAINER,
                    focus,
                ) { store.mutate { s -> s.copy(cornerStyle = CornerStyle.fromKey(it)) } }
                SettingsDivider()
                StudioSlider("Corner size", style.cornerSizeDp, 0..28, "dp", EditTarget.CONTAINER, focus) {
                    store.mutate { s -> s.copy(cornerSizeDp = it) }
                }
                SettingsDivider()
                StudioSegmented(
                    "Icon shape",
                    IconShape.entries.map { it.key to it.label },
                    style.iconShape.key,
                    EditTarget.ICON,
                    focus,
                ) { store.mutate { s -> s.copy(iconShape = IconShape.fromKey(it)) } }
                SettingsDivider()
                StudioSlider("Icon corner", style.iconCornerDp, 0..24, "dp", EditTarget.ICON, focus) {
                    store.mutate { s -> s.copy(iconCornerDp = it) }
                }
                SettingsDivider()
                StudioSlider("Icon size", style.iconSizeDp, 28..56, "dp", EditTarget.ICON, focus) {
                    store.mutate { s -> s.copy(iconSizeDp = it) }
                }
            }

            SettingsSection("Appearance")
            SettingsCard {
                StudioSlider("Background tint", style.bgAlpha, 0..40, "%", EditTarget.CONTAINER, focus) {
                    store.mutate { s -> s.copy(bgAlpha = it) }
                }
                SettingsDivider()
                StudioSlider("Idle icon opacity", style.idleAlpha, 5..100, "%", EditTarget.ICON, focus) {
                    store.mutate { s -> s.copy(idleAlpha = it) }
                }
                SettingsDivider()
                StudioSegmented(
                    "Accent source",
                    StripAccentMode.entries.map { it.key to it.label },
                    style.accentMode.key,
                    EditTarget.STRIP,
                    focus,
                ) { store.mutate { s -> s.copy(accentMode = StripAccentMode.fromKey(it)) } }
                if (style.accentMode == StripAccentMode.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    AppAccentPicker(
                        mode = APP_COLOR_MODE_CUSTOM,
                        customArgb = style.accentColor,
                        onMode = {},
                        onCustomColor = { argb, _ -> store.mutate { s -> s.copy(accentColor = argb) } },
                    )
                }
            }

            SettingsSection("Strip buttons")
            SettingsCard {
                StripButtonSpec.ALL.forEachIndexed { i, spec ->
                    val on = spec.id in style.visibleButtons
                    StudioSwitch(spec.label, on, EditTarget.STRIP, focus) { enabled ->
                        store.mutate { s ->
                            val set = s.visibleButtons.toMutableSet()
                            if (enabled) set.add(spec.id) else set.remove(spec.id)
                            s.copy(visibleButtons = set)
                        }
                    }
                    if (i < StripButtonSpec.ALL.lastIndex) SettingsDivider()
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val imported = clip.primaryClip?.getItemAt(0)?.text?.toString()?.let { SidebarStyleTransfer.import(it) }
                    if (imported != null) {
                        store.update(imported)
                        scope.launch { snackbar.showSnackbar("Style imported") }
                    } else scope.launch { snackbar.showSnackbar("No valid style in clipboard") }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import from clipboard") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StudioSlider(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String = "",
    target: EditTarget,
    focus: (EditTarget) -> Unit,
    onCommit: (Int) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    var pos by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val cur = pos.toInt().coerceIn(range.first, range.last)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$cur$suffix", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it; focus(target) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { onCommit(pos.toInt().coerceIn(range.first, range.last)) },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent.copy(0.8f),
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
private fun StudioSwitch(
    label: String,
    checked: Boolean,
    target: EditTarget,
    focus: (EditTarget) -> Unit,
    onChange: (Boolean) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable { focus(target); onChange(!checked) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onChange(it); focus(target) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = accent,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
}

@Composable
private fun StudioSegmented(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    target: EditTarget,
    focus: (EditTarget) -> Unit,
    onSelect: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (k, lab) ->
                val sel = k == selected
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (sel) accent.copy(0.18f) else scheme.surfaceContainerHigh)
                        .border(if (sel) 1.dp else 0.5.dp, if (sel) accent else scheme.outlineVariant, RoundedCornerShape(8.dp))
                        .clickable { focus(target); onSelect(k) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        lab,
                        color = if (sel) accent else scheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}