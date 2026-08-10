package com.ireddragonicy.gamespace.utils

import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.graphics.drawable.toBitmap

private val iconCache = LruCache<Drawable, BitmapPainter>(96)

/**
 * Single source of truth for Drawable → Painter conversion.
 * Cached by Drawable identity (stable across recompositions).
 */
@Composable
fun rememberDrawablePainter(drawable: Drawable?): Painter {
    return remember(drawable) {
        if (drawable == null) return@remember ColorPainter(Color.Transparent)
        iconCache.get(drawable) ?: run {
            val painter = BitmapPainter(
                drawable.toBitmap(width = 96, height = 96).asImageBitmap()
            )
            iconCache.put(drawable, painter)
            painter
        }
    }
}
