/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import android.content.pm.ApplicationInfo

/**
 * The ONLY app-flavour detector in the app.
 *
 * Both the session-mode resolver (GameSpaceService) and the dock builder
 * (QuickStartProvider) go through here, so an app can never be "a game"
 * for the sidebar yet "not a game" for the strip next to it.
 */
object AppClassifier {

    @Suppress("DEPRECATION")
    fun isGame(info: ApplicationInfo): Boolean =
        info.category == ApplicationInfo.CATEGORY_GAME ||
            (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    fun isVideo(info: ApplicationInfo, packageName: String): Boolean {
        val cat = info.category
        if (cat == ApplicationInfo.CATEGORY_VIDEO || cat == ApplicationInfo.CATEGORY_AUDIO) return true
        val lower = packageName.lowercase()
        return VIDEO_PKG_HINTS.any { lower.contains(it) }
    }

    /** Moved out of GameSpaceService.isVideoPackageName — same list, one home. */
    private val VIDEO_PKG_HINTS = listOf(
        "youtube", "netflix", "vlc", "mxplayer", "primevideo", "disney",
        "bilibili", "tiktok", "twitch", "iqiyi", "videoplayer", "hotstar",
        "hulu", "hbomax",
    )
}
