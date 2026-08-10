/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 *
 * SHARED render components — dipakai oleh strip asli DAN preview.
 * 1:1 by construction: satu fungsi, dua konteks.
 */
package com.ireddragonicy.gamespace.gamebar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.EdgeGeometry
import com.ireddragonicy.gamespace.data.HandleAspect
import com.ireddragonicy.gamespace.data.HandleFill
import com.ireddragonicy.gamespace.data.HandleGeometry
import com.ireddragonicy.gamespace.data.SidebarStyle
import com.ireddragonicy.gamespace.data.StripPosition

// ── Handle Shape Builder ──

fun handleShape(style: SidebarStyle, dockedOnLeft: Boolean): Shape =
    handleShape(style.effectiveHandle(), dockedOnLeft)

fun handleShape(g: HandleGeometry, dockedOnLeft: Boolean): Shape {
    return object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline {
            val w = size.width
            val h = size.height
            val tl = if (dockedOnLeft) g.cornerTR else g.cornerTL
            val tr = if (dockedOnLeft) g.cornerTL else g.cornerTR
            val bl = if (dockedOnLeft) g.cornerBR else g.cornerBL
            val br = if (dockedOnLeft) g.cornerBL else g.cornerBR

            // FIX: two corners sharing an edge may not sum to >100% of that
            // edge, otherwise their béziers cross → self-intersecting path →
            // sharp spikes on fill (Pill/Notch left edge = 200%, Circle/Dot =
            // 200% everywhere). Normalize like CSS border-radius: scale ALL
            // corners by the worst edge so every edge sum is ≤ 100%.
            val f = minOf(
                100f / maxOf(tl + tr, 1),  // top edge    (widths)
                100f / maxOf(bl + br, 1),  // bottom edge (widths)
                100f / maxOf(tl + bl, 1),  // left edge   (heights)
                100f / maxOf(tr + br, 1),  // right edge  (heights)
                1f,
            )

            // Perfect-ellipse fast path (Circle / Dot presets).
            if (minOf(tl, tr, bl, br) >= 100) {
                val r = CornerRadius(w / 2f, h / 2f)
                return Outline.Rounded(RoundRect(0f, 0f, w, h, r, r, r, r))
            }

            val ntl = tl * f; val ntr = tr * f
            val nbl = bl * f; val nbr = br * f
            fun pxW(p: Float) = (p / 100f) * w
            fun pxH(p: Float) = (p / 100f) * h

            val path = Path().apply {
                moveTo(pxW(ntl), 0f)
                lineTo(w - pxW(ntr), 0f)
                if (ntr > 0f) quadraticTo(w, 0f, w, pxH(ntr)) else lineTo(w, 0f)
                lineTo(w, h - pxH(nbr))
                if (nbr > 0f) quadraticTo(w, h, w - pxW(nbr), h) else lineTo(w, h)
                lineTo(pxW(nbl), h)
                if (nbl > 0f) quadraticTo(0f, h, 0f, h - pxH(nbl)) else lineTo(0f, h)
                lineTo(0f, pxH(ntl))
                if (ntl > 0f) quadraticTo(0f, 0f, pxW(ntl), 0f) else lineTo(0f, 0f)
                close()
            }
            return Outline.Generic(path)
        }
    }
}

// ── Handle Fill Color (shared by real strip & preview) ──

fun handleFillColor(style: SidebarStyle, stripAccent: Color): Color =
    when (style.effectiveHandle().fill) {
        HandleFill.DARK_GLASS -> Color(0xCC1A1A1A)
        HandleFill.FOLLOW_ACCENT -> stripAccent.copy(alpha = 0.85f)
        HandleFill.CUSTOM -> Color(style.handleColor)
        HandleFill.NONE -> Color.Transparent
    }

// ── StripHandle (SHARED — real strip & preview both call this) ──

@Composable
fun StripHandle(
    style: SidebarStyle,
    dockedOnLeft: Boolean,
    idleAlpha: Float,
    accent: Color,
    showFps: Boolean,
    fpsText: String,
    onTap: () -> Unit,
    pointerModifier: Modifier = Modifier,
) {
    val g = style.effectiveHandle()
    val shape = remember(style, dockedOnLeft) { handleShape(style, dockedOnLeft) }
    val fill = handleFillColor(style, accent)
    val visualW = if (g.aspect == HandleAspect.LINE) 4.dp else style.handleWidthDp.dp
    val visualH = style.handleHeightDp.dp
    val touchW = maxOf(visualW, 28.dp)
    val a = idleAlpha

    if (showFps) {
        Box(
            Modifier.size(28.dp).alpha(a).then(pointerModifier)
                .pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }
                .border(0.5.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(Color(0xCC1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            Text(fpsText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        Box(
            Modifier.width(touchW).height(visualH + style.handleOffsetY.dp).then(pointerModifier)
                .pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) },
            contentAlignment = if (dockedOnLeft) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Box(
                Modifier.width(visualW).height(visualH).alpha(a).clip(shape)
                    .then(if (g.fill != HandleFill.NONE) Modifier.background(fill) else Modifier)
                    .then(if (g.outline) Modifier.border(0.5.dp, Color.White.copy(alpha = 0.35f), shape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (g.showGlyph) {
                    Icon(
                        painter = painterResource(
                            if (dockedOnLeft) R.drawable.materialsymbols_ic_chevron_left_rounded_filled
                            else R.drawable.materialsymbols_ic_chevron_right_rounded_filled
                        ),
                        contentDescription = null,
                        tint = readableOn(fill).copy(alpha = 0.85f),
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
    }
}

// ── StripItemLabel (INLINE mode — scrim + readable) ──

@Composable
fun StripItemLabel(text: String, style: SidebarStyle) {
    val scrim = Color.Black.copy(alpha = 0.50f)
    Box(
        Modifier.widthIn(max = (style.stripWidthDp - 4).dp)
            .clip(RoundedCornerShape(40))
            .background(scrim)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = readableOn(scrim),
            fontSize = style.labelSizeSp.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

// ── StripItem (icon + optional label) ──

@Composable
fun StripItem(
    icon: @Composable () -> Unit,
    label: String?,
    style: SidebarStyle,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(style.stripWidthDp.dp),
    ) {
        icon()
        if (style.labelMode == com.ireddragonicy.gamespace.data.LabelMode.INLINE && label != null) {
            StripItemLabel(label, style)
        }
    }
}

// ── EdgeChrome (no blur shader — stacked strokes only) ──

@Composable
fun EdgeChrome(style: SidebarStyle, accent: Color, shape: Shape, modifier: Modifier = Modifier) {
    val g = style.effectiveEdge()
    val a = style.edgeAlpha / 100f
    if (g.borderWidthDp <= 0f && g.glowRings <= 0) return
    Canvas(modifier = modifier) {
        drawEdge(g, a, accent, shape, this)
    }
}

internal fun DrawScope.drawEdge(
    g: EdgeGeometry,
    a: Float,
    accent: Color,
    shape: Shape,
    scope: DrawScope,
) {
    val outline = shape.createOutline(size, LayoutDirection.Ltr, this)
    val path = when (outline) {
        is Outline.Generic -> outline.path
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    }
    for (i in g.glowRings downTo 1) {
        drawPath(
            path,
            accent.copy(alpha = a * 0.10f * i),
            style = Stroke(width = (g.borderWidthDp * (1 + i * 1.4f)).dp.toPx()),
        )
    }
    if (g.borderWidthDp > 0f) {
        drawPath(
            path,
            accent.copy(alpha = a * 0.65f),
            style = Stroke(width = g.borderWidthDp.dp.toPx()),
        )
    }
    if (g.topRail) {
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(accent.copy(alpha = 0f), accent.copy(alpha = a * 0.8f), accent.copy(alpha = 0f))
            ),
            start = Offset(size.width * 0.12f, 2.5f),
            end = Offset(size.width * 0.62f, 2.5f),
            strokeWidth = 2.5f,
        )
    }
    if (g.cornerBlades) {
        val big = 18.dp.toPx()
        drawLine(
            color = accent.copy(alpha = a * 0.85f),
            start = Offset(4f, size.height - big + 6f),
            end = Offset(big - 6f, size.height - 4f),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = accent.copy(alpha = a * 0.35f),
            start = Offset(12f, size.height - big + 12f),
            end = Offset(big, size.height - 12f),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
    }
}
