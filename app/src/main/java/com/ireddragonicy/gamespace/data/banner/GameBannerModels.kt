/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.data.banner

import java.io.File

/** Banner source selected by user in Settings. Key = persistence value. */
enum class BannerSource(val key: String, val label: String) {
    PLAY_STORE("playstore", "Play Store"),
    GAME_SPACE_ART("gamespace_art", "GameSpace Art"),
    ;

    companion object {
        val DEFAULT = PLAY_STORE
        fun fromKey(key: String?): BannerSource =
            values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Crawl result for a single game. `localPath` null = not fetched / failed. */
data class GameBanner(
    val packageName: String,
    val localPath: String?,        // file in internal cache
    val remoteUrl: String?,        // original URL
    val source: BannerSource,
    val fetchedAtMs: Long,
)

/** Contract for a single banner provider. */
interface GameBannerProvider {
    val source: BannerSource

    /**
     * Fetch remote banner URL for [packageName], or null if not found.
     * Called on IO dispatcher.
     */
    suspend fun fetchRemoteUrl(packageName: String): String?

    /**
     * Download [url] to [targetFile]. Returns true if successful.
     */
    suspend fun download(url: String, targetFile: File): Boolean
}
