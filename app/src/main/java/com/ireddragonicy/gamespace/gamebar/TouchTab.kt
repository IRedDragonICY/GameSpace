/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.touch.GameTouchModeManager
import com.ireddragonicy.gamespace.touch.KernelTouchFilterClient
import com.ireddragonicy.gamespace.touch.TouchSampleRateView

@Composable
fun TouchTab(tileRepository: TileRepository, accent: Color) {
    val manager = tileRepository.touchModeManager
    val bench = rememberBenchState()

    Column(modifier = Modifier.fillMaxWidth()) {
        // header: title + expand-to-full-window
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TunerSectionLabel(stringResource(R.string.touch_test_title), accent)
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background2(accent).clickable { tileRepository.touchTesterExpanded.value = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Rounded.OpenInFull, "Expand", tint = accent, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("FULL", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        AndroidView(
            factory = { ctx -> TouchSampleRateView(ctx, compact = true) },
            modifier = Modifier.fillMaxWidth().height(150.dp).clip(remember { chamferShape() }),
        )
        TunerHint("Multi-touch enabled · swipe here no longer scrolls the panel. Open FULL for the latency lab.")
        LastTapChip(bench)

        val pkg = tileRepository.currentGamePackage
        val hasReportRate = manager.supportsSuperReport
        val hasAccidentalFilter = manager.supportsAccidentalTouchFilter
        if (hasReportRate || hasAccidentalFilter) {
            TunerSectionLabel(stringResource(R.string.tuner_touch_section_per_app), accent)
            if (pkg == null) NoActiveGameNote() else {
                PerAppTouchTuning(manager, pkg, accent, hasReportRate, hasAccidentalFilter)
            }
        } else {
            TunerHint("No verified GameSpace touch-control backend is active.")
        }
    }
}

// tiny accent-tinted background helper (avoids extra import noise above)
private fun Modifier.background2(accent: Color): Modifier =
    this.then(Modifier.background(accent.copy(alpha = 0.12f)))

/**
 * Per-game touch controls.  The sampling-rate section goes through the vendor
 * HAL and is read back from the driver.  Edge/grip controls go through the OSS
 * input-side filter and are only rendered when that kernel ABI is present.
 */
@Composable
private fun PerAppTouchTuning(
    manager: GameTouchModeManager,
    pkg: String,
    accent: Color,
    hasReportRate: Boolean,
    hasAccidentalFilter: Boolean,
) {
    LaunchedEffect(pkg) {
        if (hasReportRate) {
            manager.superReportEnabled.value = manager.superReport(pkg)
            manager.refreshReportRate()
        }
        if (hasAccidentalFilter) manager.refreshFilterStats()
    }

    if (hasReportRate) {
        TunerToggleRow(
            stringResource(R.string.touch_test_super_report),
            manager.superReportEnabled.value,
            { enabled -> manager.setSuperReport(pkg, enabled) },
            accent,
        )
        TunerHint(
            "Panel scan rate: ${manager.reportRateHz.intValue} Hz, read back from the driver." +
                if (manager.superReportEnabled.value && !manager.superReportEngaged)
                    " Requested a boost but the panel is still at its base rate."
                else ""
        )
    }

    if (hasAccidentalFilter) {
        val levels = listOf(
            stringResource(R.string.tuner_choice_off) to KernelTouchFilterClient.LEVEL_OFF,
            "Low" to KernelTouchFilterClient.LEVEL_LOW,
            "Med" to KernelTouchFilterClient.LEVEL_MEDIUM,
            "High" to KernelTouchFilterClient.LEVEL_HIGH,
        )
        TunerSectionLabel("ACCIDENTAL TOUCH", accent)
        TunerSegmented(
            label = "Edge filter",
            options = levels,
            selected = manager.edgeFilterLevel.intValue,
            onSelect = { manager.setEdgeFilter(pkg, it) },
            accent = accent,
        )
        Spacer(Modifier.height(8.dp))
        TunerSegmented(
            label = "Grip suppression",
            options = levels,
            selected = manager.gripSuppressionLevel.intValue,
            onSelect = { manager.setGripSuppression(pkg, it) },
            accent = accent,
        )
        TunerHint("Kernel filter: only contacts starting in protected edges/corners are rejected.")
        manager.filterStats.value?.let { stats ->
            TunerHint(
                "Verified by kernel counters · edge ${stats.edgeRejected}, " +
                    "grip ${stats.gripRejected} (this boot)."
            )
        }
    }
}
