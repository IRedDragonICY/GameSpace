package com.ireddragonicy.gamespace.gamebar.tiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp

@Composable
fun DraggableGrid(
    columns: Int = 4,
    horizontalSpacing: Int = 6, // px roughly, or we can use Density
    verticalSpacing: Int = 6,
    pageBreakRows: Int = 0, // Draw a line every N rows. 0 means disabled
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dividerColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    Layout(content, modifier.drawWithContent {
        drawContent()
        if (pageBreakRows > 0) {
            var yOffset = 0f
            // We can calculate row heights based on bounds if needed, but since it's uniform:
            // Actually, we need itemHeight. We can't access it here directly without state.
            // Let's pass the layout info from Layout back to a mutable state, OR draw it in Layout?
            // Actually, Layout doesn't let you draw directly. 
            // So we can use a modifier that we build inside the composable if we use SubcomposeLayout, or just State.
        }
    }) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(constraints.maxWidth, 0) {}
        }
        val totalSpacing = (columns - 1) * horizontalSpacing
        val itemWidth = (constraints.maxWidth - totalSpacing) / columns
        
        val placeables = measurables.map { 
            it.measure(constraints.copy(minWidth = itemWidth, maxWidth = itemWidth)) 
        }
        
        val rowCount = (placeables.size + columns - 1) / columns
        val itemHeight = placeables.maxOf { it.height }
        
        // Add extra space for the page break dividers
        val numDividers = if (pageBreakRows > 0) (rowCount - 1) / pageBreakRows else 0
        val dividerHeight = if (numDividers > 0) 24 else 0 // 24px extra space for each divider
        
        val totalHeight = (rowCount * itemHeight) + ((rowCount - 1) * verticalSpacing).coerceAtLeast(0) + (numDividers * dividerHeight)
        
        layout(constraints.maxWidth, totalHeight) {
            var x = 0
            var y = 0
            var currentRow = 0
            
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(x, y)
                x += itemWidth + horizontalSpacing
                if ((index + 1) % columns == 0) {
                    x = 0
                    currentRow++
                    y += itemHeight + verticalSpacing
                    if (pageBreakRows > 0 && currentRow % pageBreakRows == 0 && index + 1 < placeables.size) {
                        y += dividerHeight
                    }
                }
            }
        }
    }
}
