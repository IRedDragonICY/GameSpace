/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.data.banner

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage and manager for game hero banners.
 *
 *  - Image files stored in `files/banners/<pkg>.<ext>`
 *  - Index metadata stored in SharedPreferences "banner_index"
 */
@Singleton
class GameBannerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playStoreProvider: PlayStoreBannerProvider,
) {
    private val gson = Gson()

    /** Provider registry. */
    private val providers: Map<BannerSource, GameBannerProvider> = mapOf(
        BannerSource.PLAY_STORE to playStoreProvider,
    )

    private val storageDir: File
        get() = File(context.filesDir, "banners").also { it.mkdirs() }

    private val indexPrefs by lazy {
        context.getSharedPreferences("banner_index", Context.MODE_PRIVATE)
    }

    init {
        // Clear legacy cached icon images when schema updates to landscape hero banners (version 2)
        val version = indexPrefs.getInt("schema_version", 0)
        if (version < 2) {
            invalidateAll()
            indexPrefs.edit().putInt("schema_version", 2).apply()
        }
    }

    /** Retrieve cached banner if available. */
    fun getCached(packageName: String): GameBanner? {
        val json = indexPrefs.getString(packageName, null) ?: return null
        return runCatching {
            gson.fromJson(json, GameBanner::class.java)
        }.getOrNull()?.takeIf { it.localPath?.let { p -> File(p).exists() } == true }
    }

    /**
     * Fetch or retrieve banner for [packageName] using [source].
     */
    suspend fun getOrFetch(
        packageName: String,
        source: BannerSource,
        forceRefresh: Boolean = false,
    ): GameBanner? {
        val cached = getCached(packageName)
        if (!forceRefresh && cached != null && cached.source == source &&
            System.currentTimeMillis() - cached.fetchedAtMs < MAX_AGE_MS
        ) {
            return cached
        }

        val provider = providers[source] ?: return cached
        return withContext(Dispatchers.IO) {
            val url = provider.fetchRemoteUrl(packageName) ?: return@withContext cached
            val ext = if (url.contains(".png")) "png" else "jpg"
            val target = File(storageDir, "$packageName.$ext")

            storageDir.listFiles()
                ?.filter { it.name.startsWith("$packageName.") && it != target }
                ?.forEach { it.delete() }

            if (!provider.download(url, target)) return@withContext cached

            val banner = GameBanner(
                packageName = packageName,
                localPath = target.absolutePath,
                remoteUrl = url,
                source = source,
                fetchedAtMs = System.currentTimeMillis(),
            )
            indexPrefs.edit().putString(packageName, gson.toJson(banner)).apply()
            Log.d(TAG, "Banner cached for $packageName from ${source.key}")
            banner
        }
    }

    /** Remove cached banner for a game. */
    fun invalidate(packageName: String) {
        storageDir.listFiles()?.filter { it.name.startsWith("$packageName.") }
            ?.forEach { it.delete() }
        indexPrefs.edit().remove(packageName).apply()
    }

    fun invalidateAll() {
        storageDir.listFiles()?.forEach { it.delete() }
        indexPrefs.edit().clear().apply()
    }

    fun getStorageUsed(): Long =
        storageDir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        private const val TAG = "GameBannerRepo"
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }
}
