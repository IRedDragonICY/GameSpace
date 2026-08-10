package com.ireddragonicy.gamespace.data.model

import android.graphics.drawable.Drawable

/**
 * The single "an app" value object for the whole codebase.
 *
 * Replaces five near-identical copies that each screen re-declared:
 *   AppInfo   (gamebar strip)        PickerApp (SidebarSettingsActivity)
 *   AppEntry  (AppSelectorFragment)  GameEntry (GameHubActivity)
 *   AppItem   (AppSelectorViewModel)
 */
data class AppRef(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean = false,
)