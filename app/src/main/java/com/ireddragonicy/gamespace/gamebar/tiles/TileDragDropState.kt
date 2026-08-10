/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar.tiles

import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

enum class DragZone { Quick, Tool, None }

data class ItemPosition(
    val id: String,
    val index: Int,
    val offset: IntOffset,
    val size: IntSize,
    val scrollValue: Int = 0,
)

data class DragTileData(
    val id: String,
    val label: String,
    val icon: Int,
    val drawable: android.graphics.drawable.Drawable? = null,
    val group: String = "",
    var sourceZone: DragZone = DragZone.None,
)

class UnifiedDragDropState(
    private val onDropAction: (sourceId: String, targetZone: DragZone, targetIndex: Int) -> Unit,
) {
    var draggedTile by mutableStateOf<DragTileData?>(null)
    var dragPosition by mutableStateOf(Offset.Zero)
    var grabOffset by mutableStateOf(Offset.Zero)

    val quickPositions = HashMap<String, ItemPosition>()
    val toolPositions = HashMap<String, ItemPosition>()
    val availablePositions = HashMap<String, ItemPosition>()

    var quickZoneRect by mutableStateOf(Rect.Zero)
    var toolZoneRect by mutableStateOf(Rect.Zero)
    var availableZoneRect by mutableStateOf(Rect.Zero)

    // Scroll value pada saat rect zona diukur (lewat onGloballyPositioned).
    // onGloballyPositioned TIDAK fire saat konten cuma ke-translate oleh scroll,
    // jadi rect di atas jadi stale; kita kompensasi dengan delta scroll ini.
    var quickZoneScroll: Int = 0
    var toolZoneScroll: Int = 0
    var availableZoneScroll: Int = 0

    var targetZone by mutableStateOf(DragZone.None)
    var targetIndex by mutableStateOf<Int?>(null)

    fun isMoving(id: String) = draggedTile?.id == id

    fun updateItemPosition(
        zone: DragZone, id: String, index: Int,
        offset: IntOffset, size: IntSize, scrollValue: Int = 0,
    ) {
        val pos = ItemPosition(id, index, offset, size, scrollValue)
        when (zone) {
            DragZone.Quick -> quickPositions[id] = pos
            DragZone.Tool -> toolPositions[id] = pos
            DragZone.None -> availablePositions[id] = pos
        }
    }

    fun removeItemPosition(zone: DragZone, id: String) {
        when (zone) {
            DragZone.Quick -> quickPositions.remove(id)
            DragZone.Tool -> toolPositions.remove(id)
            DragZone.None -> availablePositions.remove(id)
        }
    }

    fun onDragStart(tile: DragTileData, windowPosition: Offset, localOffset: Offset) {
        draggedTile = tile
        dragPosition = windowPosition
        grabOffset = localOffset
    }

    /**
     * [currentScrollValue] = scroll container pada saat ini. Semua koordinat
     * window yang kita simpan (rect zona + offset item) diukur pada scroll
     * tertentu; karena scroll menggeser konten ke ATAS sebesar delta, posisi
     * window "asli" sekarang = posisi_terukur - (currentScroll - scroll_terukur).
     */
    fun onDragTo(absoluteWindowPosition: Offset, currentScrollValue: Int = 0) {
        dragPosition = absoluteWindowPosition

        fun compY(rect: Rect, measuredScroll: Int): Rect {
            if (rect == Rect.Zero) return rect
            val d = (currentScrollValue - measuredScroll).toFloat()
            return Rect(rect.left, rect.top - d, rect.right, rect.bottom - d)
        }
        val qRect = compY(quickZoneRect, quickZoneScroll)
        val tRect = compY(toolZoneRect, toolZoneScroll)
        val aRect = compY(availableZoneRect, availableZoneScroll)

        val quickBottom = when {
            qRect.bottom > 0f -> qRect.bottom + 20f
            tRect.top > 0f -> tRect.top - 20f
            else -> 0f
        }
        val toolBottom = when {
            tRect.bottom > 0f -> tRect.bottom + 20f
            aRect.top > 0f -> aRect.top - 20f
            else -> 0f
        }
        targetZone = when {
            quickBottom > 0f && dragPosition.y <= quickBottom -> DragZone.Quick
            toolBottom > 0f && dragPosition.y <= toolBottom -> DragZone.Tool
            aRect.top > 0f && dragPosition.y >= aRect.top - 20f -> DragZone.None
            else -> DragZone.Tool
        }

        val activePositions = when (targetZone) {
            DragZone.Quick -> quickPositions
            DragZone.Tool -> toolPositions
            DragZone.None -> availablePositions
        }
        if (activePositions.isEmpty()) {
            targetIndex = 0
            return
        }

        var bestIndex = targetIndex ?: 0
        var currentTargetDistSq = Float.MAX_VALUE
        var minDistanceSq = Float.MAX_VALUE
        var maxIndexItem: ItemPosition? = null

        val currentTargetPos = activePositions.values.firstOrNull { it.index == targetIndex }
        if (currentTargetPos != null) {
            val adjustedY = currentTargetPos.offset.y - (currentScrollValue - currentTargetPos.scrollValue)
            val cx = currentTargetPos.offset.x + currentTargetPos.size.width / 2f
            val cy = adjustedY + currentTargetPos.size.height / 2f
            val dx = cx - dragPosition.x
            val dy = cy - dragPosition.y
            currentTargetDistSq = dx * dx + dy * dy
        }

        for (pos in activePositions.values) {
            val adjustedY = pos.offset.y - (currentScrollValue - pos.scrollValue)
            val cx = pos.offset.x + pos.size.width / 2f
            val cy = adjustedY + pos.size.height / 2f
            val dx = cx - dragPosition.x
            val dy = cy - dragPosition.y
            val distSq = dx * dx + dy * dy
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq
                if (currentTargetDistSq == Float.MAX_VALUE || distSq < currentTargetDistSq - 400f) {
                    bestIndex = pos.index
                }
            }
            if (maxIndexItem == null || pos.index > maxIndexItem.index) {
                maxIndexItem = pos.copy(offset = IntOffset(pos.offset.x, adjustedY))
            }
        }

        if (maxIndexItem != null) {
            val itemRight = maxIndexItem.offset.x + maxIndexItem.size.width
            val itemCenterY = maxIndexItem.offset.y + maxIndexItem.size.height / 2f
            if (dragPosition.x > itemRight && abs(dragPosition.y - itemCenterY) < maxIndexItem.size.height) {
                bestIndex = maxIndexItem.index + 1
            }
        }
        targetIndex = bestIndex
    }

    fun onDrag(delta: Offset) {
        onDragTo(dragPosition + delta)
    }

    fun onDrop() {
        val tile = draggedTile
        val tz = targetZone
        val ti = targetIndex
        // FIX: izinkan drop ke zona None (= available). Inilah mekanisme
        // "delete / lepas dari panel": drag tile ke area Available Tiles.
        // ti == null melindungi kasus angkat-jari tanpa pernah gerak
        // (targetIndex masih null awal), supaya tidak salah pindah.
        if (tile != null && ti != null) {
            onDropAction(tile.id, tz, ti)
        }
        draggedTile = null
        targetZone = DragZone.None
        targetIndex = null
    }

    fun onCancelled() {
        draggedTile = null
        targetZone = DragZone.None
        targetIndex = null
    }
}

private enum class Phase1 { Up, Cancel, LongPress }

internal suspend fun PointerInputScope.detectReorderGestures(
    interactionSource: MutableInteractionSource?,
    getWindowPosition: () -> Offset,
    onDragStart: (localDown: Offset) -> Unit,
    onDragTo: (absoluteWindowPosition: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val touchSlop = viewConfiguration.touchSlop
    forEachGesture {
        awaitPointerEventScope {
            val down = awaitFirstDown(requireUnconsumed = false)
            val id = down.id
            val downPos = down.position
            val press = PressInteraction.Press(downPos)
            interactionSource?.tryEmit(press)
            var pressResolved = false
            var dragStarted = false
            var dragEnded = false
            try {
                val phase1: Phase1 = withTimeoutOrNull(longPressTimeout) {
                    var result: Phase1? = null
                    while (result == null) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == id }
                        if (change == null || !change.pressed) {
                            result = Phase1.Up
                        } else if (change.isConsumed) {
                            result = Phase1.Cancel
                        } else if ((change.position - downPos).getDistance() > touchSlop) {
                            result = Phase1.Cancel
                        }
                    }
                    result
                } ?: Phase1.LongPress
                when (phase1) {
                    Phase1.Cancel -> {
                        pressResolved = true
                        interactionSource?.tryEmit(PressInteraction.Cancel(press))
                        onDragCancel()
                    }
                    Phase1.Up -> {
                        pressResolved = true
                        interactionSource?.tryEmit(PressInteraction.Release(press))
                        onClick?.invoke()
                    }
                    Phase1.LongPress -> {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == id } ?: break
                            if (!ch.pressed || (ch.isConsumed && !dragStarted)) break
                            if (!dragStarted) {
                                if ((ch.position - downPos).getDistance() > touchSlop) {
                                    dragStarted = true
                                    pressResolved = true
                                    interactionSource?.tryEmit(PressInteraction.Release(press))
                                    onDragStart(downPos)
                                }
                            }
                            if (dragStarted) {
                                val absoluteFingerPos = getWindowPosition() + ch.position
                                ch.consume()
                                onDragTo(absoluteFingerPos)
                            }
                        }
                        if (dragStarted) {
                            dragEnded = true
                            onDragEnd()
                        } else {
                            pressResolved = true
                            interactionSource?.tryEmit(PressInteraction.Release(press))
                            onLongClick?.invoke()
                        }
                    }
                }
            } finally {
                if (!pressResolved) {
                    interactionSource?.tryEmit(PressInteraction.Cancel(press))
                }
                if (dragStarted && !dragEnded) {
                    onDragCancel()
                }
            }
        }
    }
}

/**
 * [getScroll] mengembalikan scroll container vertikal SAAT INI. Ini kunci
 * perbaikan Settings: posisi window yang kita simpan lewat onGloballyPositioned
 * jadi stale begitu konten di-scroll, jadi kita kurangi delta scroll supaya
 * posisi "hidup" tile selalu benar → shadow nempel di jari & zona target stabil.
 *
 * Default { 0 } membuat perilaku IDENTIK dengan sebelumnya, sehingga panel utama
 * (yang tidak memanggil dengan getScroll) tidak terpengaruh sama sekali.
 */
fun Modifier.dragAndDropTile(
    tileId: String,
    label: String,
    icon: Int,
    zone: DragZone,
    dragDropState: UnifiedDragDropState,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    getScroll: () -> Int = { 0 },
    drawable: android.graphics.drawable.Drawable? = null,
    group: String = "",
): Modifier = composed {
    var windowPosition by remember { mutableStateOf(Offset.Zero) }
    var measuredScroll by remember { mutableIntStateOf(0) }

    val currentWindowPosition by rememberUpdatedState(windowPosition)
    val currentMeasuredScroll by rememberUpdatedState(measuredScroll)
    val currentGetScroll by rememberUpdatedState(getScroll)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentLabel by rememberUpdatedState(label)
    val currentIcon by rememberUpdatedState(icon)
    val currentDrawable by rememberUpdatedState(drawable)
    val currentGroup by rememberUpdatedState(group)

    // Posisi window tile yang sudah dikompensasi scroll (= posisi asli sekarang).
    val getLiveWindowPosition = {
        val delta = (currentGetScroll() - currentMeasuredScroll).toFloat()
        Offset(currentWindowPosition.x, currentWindowPosition.y - delta)
    }

    DisposableEffect(tileId, zone) {
        onDispose { dragDropState.removeItemPosition(zone, tileId) }
    }

    this
        .onGloballyPositioned {
            windowPosition = it.positionInWindow()
            measuredScroll = getScroll()
        }
        .pointerInput(tileId) {
            detectReorderGestures(
                interactionSource = interactionSource,
                getWindowPosition = getLiveWindowPosition,
                onDragStart = { localDown ->
                    val livePos = getLiveWindowPosition()
                    dragDropState.onDragStart(
                        DragTileData(tileId, currentLabel, currentIcon, currentDrawable, currentGroup, zone),
                        livePos + localDown,
                        localDown,
                    )
                },
                onDragTo = { absolutePosition ->
                    dragDropState.onDragTo(absolutePosition, currentGetScroll())
                },
                onDragEnd = { dragDropState.onDrop() },
                onDragCancel = { dragDropState.onCancelled() },
                onClick = { currentOnClick?.invoke() },
                onLongClick = { currentOnLongClick?.invoke() },
            )
        }
}
