/* --- main/java/com/ireddragonicy/gamespace/settings/sidebarstudio/SidebarShapes.kt --- */
package com.ireddragonicy.gamespace.settings.sidebarstudio

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.ireddragonicy.gamespace.data.CornerStyle
import com.ireddragonicy.gamespace.data.IconShape
import com.ireddragonicy.gamespace.data.SidebarStyle
import com.ireddragonicy.gamespace.data.StripAccentMode
import com.ireddragonicy.gamespace.gamebar.LocalPanelAccent
import com.ireddragonicy.gamespace.gamebar.chamferShape

@Composable
fun SidebarStyle.containerShape(): Shape = when (cornerStyle) {
    CornerStyle.CHAMFER -> chamferShape(bigCut = cornerSizeDp.dp, smallCut = (cornerSizeDp / 3).dp)
    CornerStyle.ROUNDED -> RoundedCornerShape(cornerSizeDp.dp)
    CornerStyle.SQUARE -> RectangleShape
}
@Composable
fun SidebarStyle.iconShapeShape(): Shape = when (iconShape) {
    IconShape.CIRCLE -> CircleShape
    IconShape.ROUNDED -> RoundedCornerShape(iconCornerDp.dp)
    IconShape.SQUARE -> RectangleShape
}
@Composable
fun resolveSidebarAccent(style: SidebarStyle): Color = when (style.accentMode) {
    StripAccentMode.FOLLOW_PANEL -> LocalPanelAccent.current
    StripAccentMode.CUSTOM -> Color(style.accentColor)
}
