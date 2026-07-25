/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.touch.TouchSampleRateView

/**
 * Full-window "TOUCH LAB" — replaces the whole panel content via an animated
 * overlay (see GamePanelCardInner). Owns its own scroll so the rate pad / swipe
 * test never fight the panel scroll.
 */
@Composable
fun TouchTesterExpanded(tileRepository: TileRepository, onClose: () -> Unit) {
    val accent = LocalPanelAccent.current
    val state = rememberBenchState()
    val scroll = rememberScrollState()

    // solid base so the tuner behind is fully hidden; chrome is drawn by the parent
    Box(
        Modifier.fillMaxSize()
            .background(PanelTheme.BaseDeep, chamferShape())
            .clickable(remember { MutableInteractionSource() }, indication = null) { /* catch stray taps */ }
    ) {
        Column(Modifier.fillMaxSize()) {
            // header
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close", tint = PanelTheme.TextPrimary,
                    modifier = Modifier.size(24.dp).clickable(onClick = onClose))
                Spacer(Modifier.width(6.dp))
                Text("TOUCH LAB", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                Text("full window", color = PanelTheme.TextDim, fontSize = 9.sp)
            }

            Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 12.dp)) {
                TunerSectionLabel("Live sample rate · multi-touch", accent)
                AndroidView(
                    factory = { ctx -> TouchSampleRateView(ctx, compact = false, expanded = true) },
                    modifier = Modifier.fillMaxWidth().height(240.dp).clip(remember { chamferShape() })
                )
                TunerHint("Drag with one or more fingers. The View reads panel history, so this shows the true report rate (not the refresh rate).")

                Spacer(Modifier.height(12.dp))
                TunerSectionLabel("Tap latency benchmark", accent)
                TapLatencyTest(state, accent)
                TunerHint("in→cb = hardware timestamp to app callback · in→disp = +1 vsync estimate · react = your reaction. Lower is better.")

                Spacer(Modifier.height(12.dp))
                TunerSectionLabel("Swipe tracking test", accent)
                SwipeTrackingTest(state, accent)
                TunerHint("Follow the dashed line; deviation = tracking error. Compare with HTSR / Super Touch on and off.")

                Spacer(Modifier.height(12.dp))
                TunerSectionLabel("A / B compare", accent)
                TouchABCompare(state, tileRepository, accent)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
