package com.ireddragonicy.gamespace.gamebar.tiles

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

suspend fun PointerInputScope.detectDragGesturesInitial(
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    detectDragGesturesAfterLongPress(
        onDragStart = onDragStart,
        onDrag = { change, dragAmount -> 
            change.consume()
            onDrag(dragAmount)
        },
        onDragEnd = onDragEnd,
        onDragCancel = onDragCancel
    )
}
