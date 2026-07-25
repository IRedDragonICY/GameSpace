/*
* Copyright (C) 2026 IRedDragonICY
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*/
package com.ireddragonicy.gamespace.display

import android.content.Context
import android.hardware.display.ColorDisplayManager
import com.ireddragonicy.gamespace.device.DeviceProfile
import com.ireddragonicy.gamespace.device.DeviceProfiles

/**
 * Single owner of display color mode application.
 *
 * This replaces the old split logic where:
 * - SessionService applied AOSP modes
 * - PanelProfiles applied Xiaomi vendor modes
 *
 * Now every caller goes through this class.
 */
class DisplayColorManager private constructor(
    context: Context,
    private val deviceProfile: DeviceProfile,
) {
    private val cdm: ColorDisplayManager? = runCatching {
        context.getSystemService(ColorDisplayManager::class.java)
    }.getOrNull()

    private var originalColorMode: Int? = null

    fun isSupported(): Boolean = cdm != null

    fun applyForGame(style: Int) {
        if (cdm == null) return
        if (originalColorMode == null) {
            originalColorMode = runCatching { cdm?.colorMode }.getOrNull()
        }
        applyStyle(style)
    }

    fun applyStyle(style: Int) {
        if (cdm == null) return
        val target = deviceProfile.colorModeMap[style] ?: return
        runCatching { cdm?.setColorMode(target) }
    }

    fun restore() {
        if (cdm == null) return
        originalColorMode?.let {
            runCatching { cdm?.setColorMode(it) }
        }
        originalColorMode = null
    }

    companion object {
        @Volatile
        private var INSTANCE: DisplayColorManager? = null

        fun get(context: Context): DisplayColorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DisplayColorManager(
                    context.applicationContext,
                    DeviceProfiles.get(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }
    }
}
