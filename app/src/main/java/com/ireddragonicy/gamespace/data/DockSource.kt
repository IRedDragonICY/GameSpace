/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.data

/**
 * Which apps are eligible for the strip's auto slots — decoupled from
 * [DockSort]: source decides the candidate SET, sort decides the ORDER.
 *
 * This is the knob that stops "game mode only ever shows games": pick
 * ALL_APPS and the usage/recent sort spans every launchable app.
 */
enum class DockSource(val key: String, val label: String, val summary: String) {
    BY_MODE("mode", "Match the session", "Games in games, video apps in players, all apps elsewhere"),
    ALL_APPS("all", "All apps", "Most/recently-used apps across everything, in any session");

    companion object {
        fun fromKey(key: String?): DockSource =
            values().firstOrNull { it.key == key } ?: ALL_APPS
    }
}
