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
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.ireddragonicy.gamespace.gamebar

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.VideoGrade
import com.ireddragonicy.gamespace.data.VideoSettingStore
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.gamebar.video.DolbyBridge
import com.ireddragonicy.gamespace.settings.PerAppSettingsActivity
import com.ireddragonicy.gamespace.utils.rememberVideoStore

/** Which inline detail sheet is open below the tile grid. */
private enum class ToolboxDetail { NONE, FRAME, PICTURE, STYLE, SOUND }

/**
 * The video counterpart to [GamePanelCard] — MIUI's "Video toolbox", rebuilt on the
 * panel's own design language.
 *
 * Three groups, matching how the features actually divide:
 *  - capture (record / screenshot / cast) — system features
 *  - picture (frame gen / upscale / style / colour) — Qualcomm VPP, see [VideoSettingStore]
 *  - sound (Dolby on-off / profile) — delegated to LunarisDolby via [DolbyBridge]
 */
@Composable
fun VideoToolboxCard(
    tileRepository: TileRepository,
    maxHeight: Dp = Dp.Unspecified,
    onRecordClick: () -> Unit = {},
) {
    val panelAccent = rememberPanelAccent(
        tileRepository.panelColorMode.intValue,
        tileRepository.panelCustomColor.intValue
    )
    CompositionLocalProvider(LocalPanelAccent provides panelAccent) {
        VideoToolboxCardInner(tileRepository, maxHeight, onRecordClick)
    }
}

@Composable
private fun VideoToolboxCardInner(
    tileRepository: TileRepository,
    maxHeight: Dp,
    onRecordClick: () -> Unit,
) {
    val context = LocalContext.current
    val accent = LocalPanelAccent.current
    val pkg = tileRepository.currentGamePackage
    val store = rememberVideoStore()

    var detail by remember { mutableStateOf(ToolboxDetail.NONE) }

    // Mirror of the stored state. Reading once and writing through keeps the tiles
    // responsive without a round trip to Settings on every recomposition.
    var frcTarget by remember(pkg) {
        mutableIntStateOf(pkg?.let { store.frcTarget(it) } ?: 0)
    }
    var frcLevel by remember(pkg) {
        mutableIntStateOf(pkg?.let { store.frcLevel(it) } ?: VideoSettingStore.FrcLevel.MEDIUM)
    }
    var upscale by remember(pkg) { mutableStateOf(pkg?.let { store.upscale(it) } ?: false) }
    var enhance by remember(pkg) { mutableStateOf(pkg?.let { store.enhance(it) } ?: false) }
    var grade by remember(pkg) {
        mutableStateOf(VideoGrade.parse(pkg?.let { store.enhanceGrade(it) } ?: ""))
    }

    val dolbyController = tileRepository.dolbyController
    val dolbyState = dolbyController.state

    // Any edit has to be re-published, because the media hook samples the properties when
    // the player configures a decoder — not when we write Settings.
    fun publish() { pkg?.let { store.applyForSession(it) } }

    Box(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .width(320.dp)
            .wrapContentHeight()
            .background(accent.copy(alpha = 0.05f), chamferShape())
    ) {
        val resolvedMaxHeight =
            if (maxHeight != Dp.Unspecified) maxHeight else 520.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = resolvedMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ── SCREEN RECORD TARGET CHOOSER (inline page) ─────────────
            // Same constraints as the game panel: window dialogs are impossible
            // in the overlay ComposeView (BadTokenException), and this card does
            // not render GamePanelContent, so the chooser lives here as a page.
            if (tileRepository.showScreenRecordChooser.value) {
                ScreenRecordChooserPanel(
                    onClose = { tileRepository.showScreenRecordChooser.value = false },
                    onCurrentGame = {
                        tileRepository.showScreenRecordChooser.value = false
                        tileRepository.toggleScreenRecord(targetGameOnly = true)
                    },
                    onFullScreen = {
                        tileRepository.showScreenRecordChooser.value = false
                        tileRepository.toggleScreenRecord(targetGameOnly = false)
                    },
                )
                return@Column
            }
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.video_toolbox_title),
                    color = PanelTheme.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.video_toolbox_settings),
                    tint = PanelTheme.TextDim,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(enabled = pkg != null) {
                            context.startActivity(
                                Intent(context, PerAppSettingsActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    putExtra(PerAppSettingsActivity.EXTRA_PACKAGE, pkg)
                                }
                            )
                        },
                )
            }

            // ── Capture ─────────────────────────────────────────────────────
            ToolboxRow {
                ToolboxTile(
                    icon = Icons.Rounded.Videocam,
                    label = stringResource(R.string.video_toolbox_record),
                    active = tileRepository.screenRecordingActive.value,
                    onClick = onRecordClick,
                    onLongClick = { tileRepository.showScreenRecordChooser.value = true },
                    modifier = Modifier.weight(1f),
                )
                ToolboxTile(
                    icon = Icons.Rounded.PhotoCamera,
                    label = stringResource(R.string.video_toolbox_screenshot),
                    active = false,
                    onClick = {
                        context.sendBroadcast(Intent("android.intent.action.SCREENSHOT"))
                    },
                    modifier = Modifier.weight(1f),
                )
                ToolboxTile(
                    icon = Icons.Rounded.Cast,
                    label = stringResource(R.string.video_toolbox_cast),
                    active = false,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_CAST_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Picture ─────────────────────────────────────────────────────
            TunerSectionLabel(
                text = stringResource(R.string.video_toolbox_section_picture),
                accent = accent,
            )
            ToolboxRow {
                ToolboxTile(
                    icon = Icons.Rounded.Speed,
                    label = stringResource(R.string.video_toolbox_frame_gen),
                    active = frcTarget != 0,
                    badge = if (frcTarget != 0) "$frcTarget" else null,
                    onClick = {
                        detail = if (detail == ToolboxDetail.FRAME) ToolboxDetail.NONE
                        else ToolboxDetail.FRAME
                    },
                    modifier = Modifier.weight(1f),
                )
                ToolboxTile(
                    icon = Icons.Rounded.HighQuality,
                    label = stringResource(R.string.video_toolbox_upscale),
                    active = upscale,
                    // Not merely styling: the VPP block bypasses AIS whenever FRC runs, so
                    // an enabled-looking Upscale tile during frame gen would be a lie.
                    enabled = frcTarget == 0,
                    onClick = {
                        if (pkg != null) {
                            upscale = !upscale
                            store.setUpscale(pkg, upscale)
                            publish()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ToolboxTile(
                    icon = Icons.Rounded.AutoAwesome,
                    label = stringResource(R.string.video_toolbox_style),
                    active = enhance,
                    onClick = {
                        detail = if (detail == ToolboxDetail.STYLE) ToolboxDetail.NONE
                        else ToolboxDetail.STYLE
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            ToolboxRow {
                ToolboxTile(
                    icon = Icons.Rounded.Tune,
                    label = stringResource(R.string.video_toolbox_colour),
                    active = enhance && !grade.isIdentity(),
                    onClick = {
                        detail = if (detail == ToolboxDetail.PICTURE) ToolboxDetail.NONE
                        else ToolboxDetail.PICTURE
                    },
                    modifier = Modifier.weight(1f),
                )
                if (dolbyState.available) {
                    ToolboxTile(
                        icon = Icons.Rounded.SurroundSound,
                        label = stringResource(R.string.video_toolbox_dolby),
                        active = dolbyState.enabled,
                        onClick = {
                            dolbyController.setEnabled(!dolbyState.enabled)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ToolboxTile(
                        icon = Icons.Rounded.GraphicEq,
                        label = stringResource(R.string.video_toolbox_sound),
                        active = detail == ToolboxDetail.SOUND,
                        onClick = {
                            detail = if (detail == ToolboxDetail.SOUND) ToolboxDetail.NONE
                            else ToolboxDetail.SOUND
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Keep the grid on a 3-column rhythm when Dolby is absent.
                    Spacer(Modifier.weight(2f))
                }
            }

            // ── Detail sheets ───────────────────────────────────────────────
            ToolboxDetailSheet(visible = detail == ToolboxDetail.FRAME) {
                if (pkg == null) {
                    TunerHint(stringResource(R.string.video_toolbox_no_app))
                } else {
                    FrameGenDetail(
                        target = frcTarget,
                        level = frcLevel,
                        accent = accent,
                        onTarget = {
                            frcTarget = it
                            store.setFrcTarget(pkg, it)
                            // setFrcTarget may have cleared Upscale for us.
                            upscale = store.upscale(pkg)
                            publish()
                        },
                        onLevel = {
                            frcLevel = it
                            store.setFrcLevel(pkg, it)
                            publish()
                        },
                    )
                }
            }

            ToolboxDetailSheet(visible = detail == ToolboxDetail.STYLE) {
                if (pkg == null) {
                    TunerHint(stringResource(R.string.video_toolbox_no_app))
                } else {
                    StyleDetail(
                        enabled = enhance,
                        grade = grade,
                        accent = accent,
                        onEnabled = {
                            enhance = it
                            store.setEnhance(pkg, it)
                            publish()
                        },
                        onPreset = {
                            grade = it
                            enhance = true
                            store.setEnhance(pkg, true)
                            store.setEnhanceGrade(pkg, it.serialize())
                            publish()
                        },
                    )
                }
            }

            ToolboxDetailSheet(visible = detail == ToolboxDetail.PICTURE) {
                if (pkg == null) {
                    TunerHint(stringResource(R.string.video_toolbox_no_app))
                } else {
                    ColourDetail(
                        grade = grade,
                        accent = accent,
                        onGrade = {
                            grade = it
                            enhance = true
                            store.setEnhance(pkg, true)
                            store.setEnhanceGrade(pkg, it.serialize())
                            publish()
                        },
                    )
                }
            }

            ToolboxDetailSheet(visible = detail == ToolboxDetail.SOUND && dolbyState.available) {
                DolbyDetail(
                    profile = dolbyState.profile,
                    accent = accent,
                    onProfile = {
                        dolbyController.setProfile(it)
                    },
                )
            }
        }
    }

    // Publish once when the toolbox first appears so a player that starts decoding while
    // the panel is open picks the settings up without the user touching anything.
    DisposableEffect(pkg) {
        publish()
        onDispose { }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Detail sheets
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun FrameGenDetail(
    target: Int,
    level: Int,
    accent: Color,
    onTarget: (Int) -> Unit,
    onLevel: (Int) -> Unit,
) {
    val offLabel = stringResource(R.string.tuner_choice_off)
    val targetOptions = remember(offLabel) {
        VideoSettingStore.FrcTarget.CHOICES.map { fps ->
            (if (fps == 0) offLabel else "$fps") to fps
        }
    }
    val levelOptions = listOf(
        stringResource(R.string.video_toolbox_frc_level_low) to VideoSettingStore.FrcLevel.LOW,
        stringResource(R.string.video_toolbox_frc_level_medium) to VideoSettingStore.FrcLevel.MEDIUM,
        stringResource(R.string.video_toolbox_frc_level_high) to VideoSettingStore.FrcLevel.HIGH,
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        TunerSegmented(
            label = stringResource(R.string.video_toolbox_frc_target),
            options = targetOptions,
            selected = target,
            onSelect = onTarget,
            accent = accent,
        )
        Column(modifier = Modifier.padding(top = 8.dp)) {
            TunerSegmented(
                label = stringResource(R.string.video_toolbox_frc_level),
                options = levelOptions,
                selected = level,
                onSelect = onLevel,
                accent = accent,
                enabled = target != 0,
            )
        }
        TunerHint(stringResource(R.string.video_toolbox_frc_hint))
    }
}

@Composable
private fun StyleDetail(
    enabled: Boolean,
    grade: VideoGrade,
    accent: Color,
    onEnabled: (Boolean) -> Unit,
    onPreset: (VideoGrade) -> Unit,
) {
    val presets = listOf(
        stringResource(R.string.video_toolbox_style_off) to VideoGrade.DEFAULT,
        stringResource(R.string.video_toolbox_style_vivid) to VideoGrade.VIVID,
        stringResource(R.string.video_toolbox_style_crisp) to VideoGrade.CRISP,
        stringResource(R.string.video_toolbox_style_cinema) to VideoGrade.CINEMA,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        TunerToggleRow(
            label = stringResource(R.string.video_toolbox_style_enable),
            checked = enabled,
            onCheckedChange = onEnabled,
            accent = accent,
        )
        Column(modifier = Modifier.padding(top = 8.dp)) {
            TunerSegmented(
                label = stringResource(R.string.video_toolbox_style_preset),
                options = presets,
                selected = presets.firstOrNull { it.second == grade }?.second
                    ?: VideoGrade.DEFAULT,
                onSelect = onPreset,
                accent = accent,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun ColourDetail(
    grade: VideoGrade,
    accent: Color,
    onGrade: (VideoGrade) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GradeSlider(stringResource(R.string.video_toolbox_grade_cade), grade.cade, accent) {
            onGrade(grade.copy(cade = it))
        }
        GradeSlider(stringResource(R.string.video_toolbox_grade_ltm), grade.ltm, accent) {
            onGrade(grade.copy(ltm = it))
        }
        GradeSlider(stringResource(R.string.video_toolbox_grade_ace), grade.aceStrength, accent) {
            onGrade(grade.copy(aceStrength = it))
        }
        GradeSlider(stringResource(R.string.video_toolbox_grade_sat), grade.satGain, accent) {
            onGrade(grade.copy(satGain = it))
        }
        GradeSlider(
            stringResource(R.string.video_toolbox_grade_shadow), grade.brightnessLow, accent
        ) {
            onGrade(grade.copy(brightnessLow = it))
        }
    }
}

@Composable
private fun DolbyDetail(
    profile: Int,
    accent: Color,
    onProfile: (Int) -> Unit,
) {
    val options = listOf(
        stringResource(R.string.video_toolbox_dolby_dynamic) to DolbyBridge.PROFILE_DYNAMIC,
        stringResource(R.string.video_toolbox_dolby_movie) to DolbyBridge.PROFILE_MOVIE,
        stringResource(R.string.video_toolbox_dolby_music) to DolbyBridge.PROFILE_MUSIC,
        stringResource(R.string.video_toolbox_dolby_game) to DolbyBridge.PROFILE_GAME,
    )
    TunerSegmented(
        label = stringResource(R.string.video_toolbox_dolby_profile),
        options = options,
        selected = profile,
        onSelect = onProfile,
        accent = accent,
    )
}

// ──────────────────────────────────────────────────────────────────────────
// Building blocks
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolboxRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun ToolboxDetailSheet(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(140)) + fadeOut(tween(140)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(chamferShape(bigCut = 10.dp, smallCut = 4.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ToolboxTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: String? = null,
) {
    val accent = LocalPanelAccent.current
    val tint = when {
        !enabled -> PanelTheme.TextDim.copy(alpha = 0.35f)
        active -> accent
        else -> PanelTheme.TextPrimary
    }
    Column(
        modifier = modifier
            .clip(chamferShape(bigCut = 10.dp, smallCut = 4.dp))
            .background(
                if (active && enabled) accent.copy(alpha = 0.16f)
                else Color.White.copy(alpha = 0.05f)
            )
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = badge ?: label,
            color = tint,
            fontSize = 9.sp,
            fontWeight = if (badge != null) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun GradeSlider(
    label: String,
    value: Int,
    accent: Color,
    onValue: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = PanelTheme.TextPrimary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(text = "$value", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toInt()) },
            valueRange = 0f..VideoGrade.RANGE_MAX.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )
    }
}
