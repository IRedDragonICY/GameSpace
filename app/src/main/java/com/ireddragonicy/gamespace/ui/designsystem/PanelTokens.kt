package com.ireddragonicy.gamespace.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Design System tokens for GameSpace panel UI. */
object PanelTokens {
    val Background = Color(0xEC09090C)
    val GlassTint = Color(0x6009090C)
    val SurfaceDark = Color(0xFF121216)
    val BorderDark = Color(0xFF26262E)
    val Subtitle = Color(0xFF8E8E9A)

    val AccentPresets = listOf(
        Color(0xFF80D8FF), Color(0xFF64FFDA), Color(0xFFB9F6CA), Color(0xFFFFD180),
        Color(0xFFFF8A80), Color(0xFFFF80AB), Color(0xFFEA80FC), Color(0xFF8C9EFF),
        Color(0xFFB388FF), Color(0xFFFF1744), Color(0xFFFFD600), Color(0xFFE0E0E0),
    )
    val ColorModeCustom: Int get() = AccentPresets.size + 1

    val ChamferBig: Dp = 18.dp
    val ChamferSmall: Dp = 7.dp
}
