package com.ireddragonicy.gamespace.gamebar

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R

enum class SettingsTab(val label: String, val iconRes: Int) {
    GENERAL("GENERAL", R.drawable.materialsymbols_ic_tune_rounded_filled),
    MONITORS("MONITORS", R.drawable.ic_memory),
}

@Composable
fun SettingsTabRow(
    selected: SettingsTab,
    accent: Color,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val shape = remember { chamferShape(bigCut = 7.dp, smallCut = 3.dp) }
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SettingsTab.values().forEach { tab ->
            val isSelected = tab == selected
            Row(
                modifier = Modifier
                    .height(24.dp)
                    .clip(shape)
                    .background(if (isSelected) accent else Color.White.copy(alpha = 0.05f))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(tab.iconRes),
                    contentDescription = null,
                    tint = if (isSelected) Color.Black else accent,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = tab.label,
                    color = if (isSelected) Color.Black else PanelTheme.TextDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}
