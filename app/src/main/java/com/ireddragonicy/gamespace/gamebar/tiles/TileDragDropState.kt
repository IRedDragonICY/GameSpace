/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ireddragonicy.gamespace.gamebar.tiles

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

enum class DragZone { Quick, Tool, None }

data class ItemPosition(
    val id: String,
    val index: Int,
    val offset: IntOffset,
    val size: IntSize,
)

data class DragTileData(
    val id: String,
    val label: String,
    val icon: Int,
    var sourceZone: DragZone = DragZone.None
)

class UnifiedDragDropState(
    private val onDropAction: (sourceId: String, targetZone: DragZone, targetIndex: Int) -> Unit
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

    var targetZone by mutableStateOf(DragZone.None)
    var targetIndex by mutableStateOf<Int?>(null)

    fun isMoving(id: String) = draggedTile?.id == id

    fun updateItemPosition(zone: DragZone, id: String, index: Int, offset: IntOffset, size: IntSize) {
        val pos = ItemPosition(id, index, offset, size)
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

    fun onDrag(delta: Offset) {
        dragPosition += delta

        val thresholdTool = if (toolZoneRect.top > 0f) toolZoneRect.top - 20f else Float.MAX_VALUE
        val thresholdAvail = if (availableZoneRect.top > 0f) availableZoneRect.top - 20f else Float.MAX_VALUE

        targetZone = when {
            dragPosition.y < thresholdTool -> DragZone.Quick
            dragPosition.y < thresholdAvail -> DragZone.Tool
            else -> DragZone.None
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

        var bestIndex = 0
        var minDistanceSq = Float.MAX_VALUE
        var maxIndexItem: ItemPosition? = null

        for (pos in activePositions.values) {
            val cx = pos.offset.x + pos.size.width / 2f
            val cy = pos.offset.y + pos.size.height / 2f
            val dx = cx - dragPosition.x
            val dy = cy - dragPosition.y
            val distSq = dx * dx + dy * dy

            if (distSq < minDistanceSq) {
                minDistanceSq = distSq
                bestIndex = pos.index
            }

            if (maxIndexItem == null || pos.index > maxIndexItem.index) {
                maxIndexItem = pos
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

    fun onDrop() {
        val tile = draggedTile
        val tz = targetZone
        val ti = targetIndex

        if (tile != null && tz != DragZone.None && ti != null) {
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

fun Modifier.dragAndDropTile(
    tileId: String,
    label: String,
    icon: Int,
    zone: DragZone,
    dragDropState: UnifiedDragDropState
): Modifier = composed {
    var windowPosition by remember { mutableStateOf(Offset.Zero) }
    
    // DEEP FIX 2: Gunakan rememberUpdatedState agar koordinat selalu akurat
    // meskipun tile berpindah tempat saat pager di-scroll
    val currentWindowPosition by rememberUpdatedState(windowPosition)

    DisposableEffect(tileId, zone) {
        onDispose { dragDropState.removeItemPosition(zone, tileId) }
    }

    this
        .onGloballyPositioned { windowPosition = it.positionInWindow() }
        .pointerInput(tileId) {
            detectDragGesturesInitial(
                onDragStart = { localOffset ->
                    val data = DragTileData(tileId, label, icon, zone)
                    dragDropState.onDragStart(data, currentWindowPosition + localOffset, localOffset)
                },
                onDrag = { dragAmount ->
                    dragDropState.onDrag(dragAmount)
                },
                onDragEnd = {
                    dragDropState.onDrop()
                },
                onDragCancel = {
                    dragDropState.onCancelled()
                }
            )
        }
}

// Fallbacks
fun Modifier.dragAndDropActiveGrid(contentOffset: () -> Offset, dragDropState: Any, onDrop: (List<String>) -> Unit): Modifier = this
fun Modifier.dragAndDropTileSource(tileData: Any, dragDropState: Any, dragType: Any = Unit): Modifier = this
