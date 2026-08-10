/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.data.banner

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crawl game landscape hero banner / featured graphic from Play Store web page.
 *
 * Strategy: fetch HTML `store/apps/details?id=<pkg>`, extract featured image
 * from dimension-tagged array `[500, 1024]` / `[720, 1280]`.
 * Fallback to meta tag `og:image`.
 */
@Singleton
class PlayStoreBannerProvider @Inject constructor() : GameBannerProvider {

    override val source = BannerSource.PLAY_STORE

    override suspend fun fetchRemoteUrl(packageName: String): String? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                // Omit &gl=US to support regional / non-US market games (e.g. SEA, EU, JP)
                val url = URL(
                    "https://play.google.com/store/apps/details?id=$packageName&hl=en"
                )
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    setRequestProperty("Range", "bytes=0-204800")
                }
                if (conn.responseCode != 200 && conn.responseCode != 206) {
                    Log.w(TAG, "Play Store HTTP ${conn.responseCode} for $packageName")
                    return@withContext null
                }
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                parseBannerUrl(html)
            } catch (e: Exception) {
                Log.w(TAG, "fetchRemoteUrl($packageName) failed", e)
                null
            } finally {
                conn?.disconnect()
            }
        }

    override suspend fun download(url: String, targetFile: File): Boolean =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", UA)
                }
                if (conn.responseCode != 200) return@withContext false
                targetFile.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                targetFile.length() > 0
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $url", e)
                targetFile.delete()
                false
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * Priority:
     * 1. `[500,1024]` / `[720,1280]` / `[360,640]` dimension-tagged feature banner array in Play Store JSON.
     * 2. `og:image` / `og:image:url` meta tags.
     */
    private fun parseBannerUrl(html: String): String? {
        FEATURE_GRAPHIC_DIM.find(html)?.let { return cleanUrl(it.groupValues[1]) }
        FEATURE_GRAPHIC_ANY_RECT.find(html)?.let { return cleanUrl(it.groupValues[1]) }
        OG_IMAGE.find(html)?.let { return cleanUrl(it.groupValues[1]) }
        OG_IMAGE_URL.find(html)?.let { return cleanUrl(it.groupValues[1]) }
        ITEMPROP_IMAGE.find(html)?.let { return cleanUrl(it.groupValues[1]) }
        return null
    }

    private fun cleanUrl(raw: String): String {
        val cleaned = raw.trim()
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val baseUrl = cleaned.substringBefore("=").substringBefore(" ")
        return "$baseUrl=w1200-h600-rw"
    }

    companion object {
        private const val TAG = "PlayStoreBanner"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        /** Match landscape feature banner images (500x1024, 720x1280, 1080x1920, etc). */
        private val FEATURE_GRAPHIC_DIM =
            Regex("""\[\s*(?:500|720|1080|1440|2160|360|480)\s*,\s*\d+\s*\]\s*,\s*\[\s*null\s*,\s*null\s*,\s*["'](https://play-lh\.googleusercontent\.com/[^"'\s]+)["']""")

        /** Match any non-512x512 rectangle image in Play Store payload. */
        private val FEATURE_GRAPHIC_ANY_RECT =
            Regex("""\[\s*(?!(?:512|272|48)\s*,\s*(?:512|272|48))\d+\s*,\s*\d+\s*\]\s*,\s*\[\s*null\s*,\s*null\s*,\s*["'](https://play-lh\.googleusercontent\.com/[^"'\s]+)["']""")

        private val OG_IMAGE =
            Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""")
        private val OG_IMAGE_URL =
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""")
        private val ITEMPROP_IMAGE =
            Regex("""<img[^>]+itemprop=["']image["'][^>]+src=["']([^"']+)["']""")
    }
}
