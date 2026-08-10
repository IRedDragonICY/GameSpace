/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.data

/**
 * How the strip's free (non-pinned) slots are ordered. Persisted as [key];
 * labels + summaries live here so every settings surface speaks the same names.
 */
enum class DockSort(val key: String, val label: String, val summary: String) {
    RECENT("recent", "Recently used", "Apps you opened last come first"),
    USAGE("usage", "Most used", "Foreground time over the last 2 weeks — recent activity weighs more"),
    ALPHA("alpha", "Alphabetical", "Sorted by name, A to Z");

    companion object {
        fun fromKey(key: String?): DockSort =
            values().firstOrNull { it.key == key } ?: USAGE
    }
}
