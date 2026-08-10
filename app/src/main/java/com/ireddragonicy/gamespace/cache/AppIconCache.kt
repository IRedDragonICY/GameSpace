package com.ireddragonicy.gamespace.cache

import android.content.Context
import android.content.pm.PackageManager
import androidx.collection.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cuts the per-second icon resolution in ProcessesMonitorWidget and the
 * repeated icon loads in pickers/docks. O(1) after first hit.
 */
@Singleton
class AppIconCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private data class Entry(val label: String, val icon: ImageBitmap?)

    private val cache = LruCache<String, Entry>(192)
    private val pm: PackageManager get() = context.packageManager

    fun label(packageName: String): String = entry(packageName).label

    fun icon(packageName: String, sizePx: Int = 96): ImageBitmap? = entry(packageName, sizePx).icon

    fun isApp(packageName: String): Boolean {
        return try { pm.getApplicationInfo(packageName, 0); true }
        catch (_: Exception) { false }
    }

    private fun entry(packageName: String, sizePx: Int = 96): Entry {
        cache.get(packageName)?.let { return it }
        val built = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val bmp = pm.getApplicationIcon(appInfo).toBitmap(sizePx, sizePx).asImageBitmap()
            Entry(label, bmp)
        } catch (_: Exception) {
            Entry(packageName, null)
        }
        cache.put(packageName, built)
        return built
    }

    fun invalidate(packageName: String) { cache.remove(packageName) }
    fun clear() { cache.evictAll() }
}
