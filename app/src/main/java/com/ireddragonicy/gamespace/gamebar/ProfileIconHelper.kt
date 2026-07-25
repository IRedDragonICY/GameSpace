package com.ireddragonicy.gamespace.gamebar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

fun getProfileIcon(index: Int, name: String): ImageVector {
    return when (index) {
        0 -> Icons.Rounded.AutoMode
        2 -> Icons.Rounded.FlashOn
        3 -> Icons.Rounded.Balance
        4 -> Icons.Rounded.SportsEsports
        5 -> Icons.Rounded.VideogameAsset
        6 -> Icons.Rounded.Train
        7 -> Icons.Rounded.HighQuality
        16 -> Icons.Rounded.BatterySaver
        else -> {
            val lowerName = name.lowercase()
            when {
                lowerName.contains("battery") || lowerName.contains("saver") -> Icons.Rounded.EnergySavingsLeaf
                lowerName.contains("genshin") || lowerName.contains("game") -> Icons.Rounded.Gamepad
                lowerName.contains("perf") || lowerName.contains("boost") -> Icons.Rounded.Speed
                lowerName.contains("cool") || lowerName.contains("ice") -> Icons.Rounded.AcUnit
                else -> Icons.Rounded.Settings
            }
        }
    }
}
