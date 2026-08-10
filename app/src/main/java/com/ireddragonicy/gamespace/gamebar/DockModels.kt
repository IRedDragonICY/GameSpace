/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import com.ireddragonicy.gamespace.data.model.AppRef

/**
 * One slot in the app strip. [pinned] decides how the slot behaves:
 *  - true  → user's slot: kept forever, in the user's order.
 *  - false → auto slot: re-sorted on every refresh; dragging it somewhere
 *            pins it there ("you moved it, you own it").
 */
data class DockItem(
    val info: AppRef,
    val pinned: Boolean,
)