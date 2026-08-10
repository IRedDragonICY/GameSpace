package com.ireddragonicy.gamespace.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decoded-banner cache for GameSpace. GameHubActivity currently calls
 * BitmapFactory.decodeFile() inside derivedStateOf on every composition.
 *
 * Evicted bitmaps are deliberately NOT recycled: Compose still holds an
 * asImageBitmap() reference to entries that are evicted between frames
 * (e.g. landscape lays out more cards than the cache holds), and drawing an
 * evicted/recycled bitmap crashes with "Canvas: trying to use a recycled
 * bitmap". Let GC reclaim them instead.
 */
@Singleton
class BannerBitmapCache @Inject constructor() {

    private val cache = LruCache<String, Bitmap>(24)

    fun get(path: String): Bitmap? {
        val f = File(path)
        if (!f.exists()) return null
        val key = key(f)
        cache.get(key)?.let { return it }
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun invalidate(path: String) { cache.remove(key(File(path))) }
    private fun key(f: File) = "${f.absolutePath}:${f.lastModified()}"
}
