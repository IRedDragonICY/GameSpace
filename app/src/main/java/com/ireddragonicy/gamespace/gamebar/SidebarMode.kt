/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

/**
 * Modes for the GameSpace floating sidebar.
 */
object SidebarMode {

    /** No sidebar at all — launcher, keyguard, excluded apps, or disabled. */
    const val MODE_NONE = 0

    /** Full game panel: tiles, thermal, FPS, tuner. */
    const val MODE_GAME = 1

    /** Video toolbox: frame gen, upscale, colour, Dolby. */
    const val MODE_VIDEO = 2

    /** Plain app strip. */
    const val MODE_PLAIN = 3

    fun toString(mode: Int): String {
        return when (mode) {
            MODE_GAME -> "GAME"
            MODE_VIDEO -> "VIDEO"
            MODE_PLAIN -> "PLAIN"
            else -> "NONE"
        }
    }
}
