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
package com.ireddragonicy.gamespace.gamebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.DolbyProfileClient
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.gamebar.video.DolbyBridge

/**
 * SOUND tab in in-game tuner.
 *
 * Two sections:
 * 1. Global Dolby (on/off + profile) — via [DolbyController].
 * 2. Per-game Dolby profile — via [DolbyProfileClient] → LunarisDolby SSOT.
 */
@Composable
fun SoundTab(
    tileRepository: TileRepository,
    accent: Color,
) {
    val context = LocalContext.current
    val dolbyController = tileRepository.dolbyController
    val dolbyState = dolbyController.state
    val pkg = tileRepository.currentGamePackage

    if (!dolbyState.available) {
        TunerHint(stringResource(R.string.video_toolbox_no_app))
        return
    }

    // ── Section 1: Global Dolby ──
    val globalOptions = listOf(
        stringResource(R.string.video_toolbox_dolby_dynamic) to DolbyBridge.PROFILE_DYNAMIC,
        stringResource(R.string.video_toolbox_dolby_movie) to DolbyBridge.PROFILE_MOVIE,
        stringResource(R.string.video_toolbox_dolby_music) to DolbyBridge.PROFILE_MUSIC,
        stringResource(R.string.video_toolbox_dolby_game) to DolbyBridge.PROFILE_GAME,
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TunerToggleRow(
            label = stringResource(R.string.video_toolbox_dolby),
            checked = dolbyState.enabled,
            onCheckedChange = { dolbyController.setEnabled(it) },
            accent = accent,
        )
        TunerSegmented(
            label = stringResource(R.string.video_toolbox_dolby_profile),
            options = globalOptions,
            selected = dolbyState.profile,
            onSelect = { dolbyController.setProfile(it) },
            accent = accent,
        )

        // ── Section 2: Per-game Dolby profile (NEW) ──
        if (pkg != null) {
            TunerSectionLabel(
                text = "PER-GAME DOLBY PROFILE",
                accent = accent,
            )

            var perGameProfile by remember(pkg) {
                mutableIntStateOf(DolbyProfileClient.getProfile(context, pkg))
            }

            val profileOptions = remember {
                listOf("Follow Global" to DolbyProfileClient.PROFILE_DEFAULT) +
                    DolbyProfileClient.PROFILE_NAMES.mapIndexed { i, name ->
                        name to i
                    }
            }

            val currentValueText = if (perGameProfile >= 0)
                DolbyProfileClient.profileName(perGameProfile)
            else
                "Follow Global"

            var expanded by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = currentValueText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (perGameProfile >= 0) accent
                            else PanelTheme.TextDim,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            tint = PanelTheme.TextDim,
                        )
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        profileOptions.forEach { (name, value) ->
                            val isSelected = value == perGameProfile
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = name,
                                        color = if (isSelected) accent
                                        else Color.Unspecified,
                                        fontWeight = if (isSelected)
                                            FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    perGameProfile = value
                                    DolbyProfileClient.setProfile(context, pkg, value)
                                    if (value >= 0) {
                                        DolbyBridge.setEnabled(context, true)
                                        DolbyBridge.setProfile(context, value)
                                    }
                                    expanded = false
                                },
                                trailingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = accent,
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }

                TunerHint(
                    text = "Saved in LunarisDolby (SSOT). Auto-applied on game launch."
                )
            }
        }
    }
}
