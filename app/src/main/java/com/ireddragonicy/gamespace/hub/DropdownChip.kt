package com.ireddragonicy.gamespace.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ChipOption<T>(val value: T, val label: String)

/**
 * Interactive per-game feature chip: coloured pill that opens a dropdown of
 * [ChipOption]s. Inactive renders grey with a dim pill, active renders with
 * [activeColor] and the selected entry gets a check mark.
 */
@Composable
fun <T> DropdownChip(
    iconRes: Int,
    label: String,
    activeColor: Color,
    isActive: Boolean,
    options: List<ChipOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var expanded by remember { mutableStateOf(false) }
    val color = if (isActive) activeColor else inactiveColor

    Box {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = color.copy(alpha = if (isActive) 0.16f else 0.10f),
            modifier = Modifier.height(22.dp).clickable { expanded = true },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 7.dp)
            ) {
                Icon(painterResource(iconRes), null, tint = color, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label) },
                    trailingIcon = if (opt.value == selected) {
                        { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        onSelect(opt.value)
                        expanded = false
                    },
                )
            }
        }
    }
}
