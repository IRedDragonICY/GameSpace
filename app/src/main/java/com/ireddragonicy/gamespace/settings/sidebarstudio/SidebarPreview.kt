/* --- main/java/com/ireddragonicy/gamespace/settings/sidebarstudio/SidebarPreview.kt --- */
package com.ireddragonicy.gamespace.settings.sidebarstudio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.HandleFill
import com.ireddragonicy.gamespace.data.LabelMode
import com.ireddragonicy.gamespace.data.SidebarStyle
import com.ireddragonicy.gamespace.data.StripButtonSpec
import com.ireddragonicy.gamespace.data.StripLayout
import com.ireddragonicy.gamespace.data.StripPosition
import com.ireddragonicy.gamespace.data.model.AppRef
import com.ireddragonicy.gamespace.gamebar.chamferShape
import com.ireddragonicy.gamespace.gamebar.EdgeChrome
import com.ireddragonicy.gamespace.gamebar.LocalPanelAccent
import com.ireddragonicy.gamespace.gamebar.glassBrush
import com.ireddragonicy.gamespace.gamebar.handleFillColor
import com.ireddragonicy.gamespace.gamebar.handleShape
import com.ireddragonicy.gamespace.gamebar.readableOn
import com.ireddragonicy.gamespace.gamebar.rememberPanelAccent
import com.ireddragonicy.gamespace.utils.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Which part of the preview a studio control is currently "targeting". */
enum class EditTarget { HANDLE, EDGE, CONTAINER, ICON, LABEL, STRIP }

private data class DpRect(val left: Dp, val top: Dp, val width: Dp, val height: Dp) {
    val right get() = left + width; val bottom get() = top + height
    val cx get() = left + width / 2f; val cy get() = top + height / 2f
    companion object { val Zero = DpRect(0.dp, 0.dp, 0.dp, 0.dp) }
}

private enum class LeadKind { TIMER, PING, RECORD, BUBBLE }
private data class LeadItem(val kind: LeadKind, val rect: DpRect)
private data class PreviewLayout(
    val vertical: Boolean, val rail: DpRect, val leads: List<LeadItem>,
    val icons: List<DpRect>, val labels: List<DpRect>, val add: DpRect,
    val handle: DpRect, val targets: Map<EditTarget, DpRect>,
)

// Fixed metrics (dp) → deterministic layout so focus brackets line up exactly.
private val PAD_H = 22.dp; private val PAD_V = 12.dp
private val TEXT_ROW = 20.dp; private val CAPSULE = 44.dp; private val ADD_BTN = 36.dp
private val LABEL_GAP = 2.dp; private val LABEL_ROW = 12.dp

private fun pingColor(ms: Int) = when {
    ms < 50 -> Color(0xFF00E676); ms < 100 -> Color(0xFFFFEB3B)
    ms < 200 -> Color(0xFFFF9800); else -> Color(0xFFFF1744)
}

/**
 * Layout that mirrors the REAL VerticalAppSidebar / HorizontalAppStrip:
 *  - leads pinned at the start, add-button pinned at the end,
 *  - app icons fill ONLY the gap between them, clamped so they can never
 *    overlap the add-button or spill past the rail (the old bug: icon #4
 *    landed on top of the "+" and got clipped).
 * This is the at-rest visible viewport of the real (scrollable) strip, so it
 * reads 1:1 without ever overflowing.
 */
private fun computeLayout(style: SidebarStyle, w: Dp, h: Dp, appCount: Int): PreviewLayout {
    val vis = StripButtonSpec.visible(style)
    val iconSize = style.iconSizeDp.dp
    val g = style.effectiveHandle()
    val visualW = if (g.aspect == com.ireddragonicy.gamespace.data.HandleAspect.LINE) 4.dp else style.handleWidthDp.dp
    val visualH = style.handleHeightDp.dp
    val targets = mutableMapOf<EditTarget, DpRect>()
    val sp = style.itemSpacingDp.dp
    val inline = style.labelMode == LabelMode.INLINE || (style.labelMode == LabelMode.OFF && style.showLabels)
    val labelBlock = if (inline) LABEL_GAP + LABEL_ROW else 0.dp
    val slot = iconSize + labelBlock
    val addVisible = vis.any { it.id == StripButtonSpec.ID_ADD }

    if (style.layout == StripLayout.VERTICAL) {
        val railW = style.stripWidthDp.dp
        val railH = h - PAD_V * 2f
        val railLeft = if (style.position == StripPosition.END) w - PAD_H - railW else PAD_H
        val railTop = PAD_V
        val innerTop = railTop + style.verticalPaddingDp.dp
        val innerBottom = railTop + railH - style.verticalPaddingDp.dp
        val rail = DpRect(railLeft, railTop, railW, railH)

        val leads = mutableListOf<LeadItem>()
        var cur = innerTop
        fun textLead(k: LeadKind) {
            if (cur + TEXT_ROW <= innerBottom) {
                leads.add(LeadItem(k, DpRect(railLeft, cur, railW, TEXT_ROW))); cur += TEXT_ROW + sp
            }
        }
        fun capsuleLead(k: LeadKind) {
            if (cur + CAPSULE <= innerBottom) {
                leads.add(LeadItem(k, DpRect(railLeft + (railW - CAPSULE) / 2f, cur, CAPSULE, CAPSULE)))
                cur += CAPSULE + sp
            }
        }
        if (vis.any { it.id == StripButtonSpec.ID_TIMER }) textLead(LeadKind.TIMER)
        if (vis.any { it.id == StripButtonSpec.ID_PING }) textLead(LeadKind.PING)
        if (vis.any { it.id == StripButtonSpec.ID_RECORD }) capsuleLead(LeadKind.RECORD)
        if (vis.any { it.id == StripButtonSpec.ID_BUBBLE }) capsuleLead(LeadKind.BUBBLE)

        // Reserve the add-button at the bottom first…
        val addTop = innerBottom - ADD_BTN
        val add = if (addVisible && addTop >= cur)
            DpRect(railLeft + (railW - ADD_BTN) / 2f, addTop, ADD_BTN, ADD_BTN) else DpRect.Zero
        val middleBottom = if (addVisible && addTop >= cur) addTop - sp else innerBottom

        // …then fit as many apps as the remaining gap actually holds.
        val avail = (middleBottom - cur).coerceAtLeast(0.dp)
        val maxFit = if (slot > 0.dp) ((avail + sp) / (slot + sp)).toInt().coerceAtLeast(0) else 0
        val shown = minOf(appCount, maxFit)
        val icons = mutableListOf<DpRect>(); val labels = mutableListOf<DpRect>()
        var iy = cur
        for (i in 0 until shown) {
            if (iy + iconSize > middleBottom) break
            icons.add(DpRect(railLeft + (railW - iconSize) / 2f, iy, iconSize, iconSize))
            if (inline) labels.add(DpRect(railLeft + 2.dp, iy + iconSize + LABEL_GAP, railW - 4.dp, LABEL_ROW))
            iy += slot + sp
        }

        val handleTop = (railTop + (railH - visualH) / 2f + style.handleOffsetY.dp)
            .coerceIn(railTop, (railTop + railH - visualH).coerceAtLeast(railTop))
        val handleLeft = if (style.position == StripPosition.END)
            (railLeft + railW).coerceAtMost(w - visualW - 2.dp) else (railLeft - visualW).coerceAtLeast(2.dp)
        val handle = DpRect(handleLeft, handleTop, visualW, visualH)

        targets[EditTarget.STRIP] = rail; targets[EditTarget.CONTAINER] = rail; targets[EditTarget.EDGE] = rail
        targets[EditTarget.ICON] = icons.firstOrNull() ?: DpRect.Zero
        targets[EditTarget.LABEL] = labels.firstOrNull() ?: DpRect.Zero
        targets[EditTarget.HANDLE] = handle
        return PreviewLayout(true, rail, leads, icons, labels, add, handle, targets)
    } else {
        val barH = style.stripWidthDp.dp
        val barTop = h - PAD_V - barH
        val barLeft = PAD_H
        val barW = w - PAD_H * 2f
        val barRect = DpRect(barLeft, barTop, barW, barH)
        val innerLeft = barLeft + style.verticalPaddingDp.dp
        val innerRight = barLeft + barW - style.verticalPaddingDp.dp

        val leads = mutableListOf<LeadItem>()
        var cx = innerLeft
        fun textLeadH(k: LeadKind, wd: Dp) {
            if (cx + wd <= innerRight) { leads.add(LeadItem(k, DpRect(cx, barTop, wd, barH))); cx += wd + sp }
        }
        fun capsuleLeadH(k: LeadKind) {
            if (cx + CAPSULE <= innerRight) {
                leads.add(LeadItem(k, DpRect(cx, barTop + (barH - CAPSULE) / 2f, CAPSULE, CAPSULE))); cx += CAPSULE + sp
            }
        }
        if (vis.any { it.id == StripButtonSpec.ID_TIMER }) textLeadH(LeadKind.TIMER, 34.dp)
        if (vis.any { it.id == StripButtonSpec.ID_PING }) textLeadH(LeadKind.PING, 30.dp)
        if (vis.any { it.id == StripButtonSpec.ID_RECORD }) capsuleLeadH(LeadKind.RECORD)
        if (vis.any { it.id == StripButtonSpec.ID_BUBBLE }) capsuleLeadH(LeadKind.BUBBLE)

        val addLeft = innerRight - ADD_BTN
        val add = if (addVisible && addLeft >= cx)
            DpRect(addLeft, barTop + (barH - ADD_BTN) / 2f, ADD_BTN, ADD_BTN) else DpRect.Zero
        val middleRight = if (addVisible && addLeft >= cx) addLeft - sp else innerRight
        val availW = (middleRight - cx).coerceAtLeast(0.dp)
        val maxFit = if (slot > 0.dp) ((availW + sp) / (iconSize + sp)).toInt().coerceAtLeast(0) else 0
        val shown = minOf(appCount, maxFit)
        val icons = mutableListOf<DpRect>()
        var ix = cx
        for (i in 0 until shown) {
            if (ix + iconSize > middleRight) break
            icons.add(DpRect(ix, barTop + (barH - iconSize) / 2f, iconSize, iconSize)); ix += iconSize + sp
        }
        val handleLeft2 = barLeft + (barW - visualW) / 2f
        val handle = DpRect(handleLeft2, (barTop - visualH).coerceAtLeast(2.dp), visualW, visualH)

        targets[EditTarget.STRIP] = barRect; targets[EditTarget.CONTAINER] = barRect; targets[EditTarget.EDGE] = barRect
        targets[EditTarget.ICON] = icons.firstOrNull() ?: DpRect.Zero
        targets[EditTarget.HANDLE] = handle
        return PreviewLayout(false, barRect, leads, icons, emptyList(), add, handle, targets)
    }
}

@Composable
fun SidebarPreview(
    style: SidebarStyle,
    activeTarget: EditTarget? = null,
    onClearHighlight: () -> Unit = {},
    modifier: Modifier = Modifier.height(360.dp),
) {
    val context = LocalContext.current
    val appSettings = remember { com.ireddragonicy.gamespace.data.AppSettings(context.applicationContext) }
    val panelAccent = rememberPanelAccent(appSettings.panelColorMode, appSettings.panelCustomColor)

    // ── live demo state (makes the preview behave like a real strip) ──
    var apps by remember { mutableStateOf<List<AppRef>>(emptyList()) }
    var sessionSec by remember { mutableIntStateOf(207) }      // 03:27
    var ping by remember { mutableIntStateOf(24) }
    var recording by remember { mutableStateOf(false) }
    var bubble by remember { mutableStateOf(false) }
    var pressedApp by remember { mutableIntStateOf(-1) }
    var addPulse by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            runCatching {
                pm.queryIntentActivities(
                    android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    }, 0
                ).mapNotNull { ri ->
                    runCatching {
                        val ai = pm.getApplicationInfo(ri.activityInfo.packageName, 0)
                        AppRef(packageName = ai.packageName, label = pm.getApplicationLabel(ai).toString(), icon = pm.getApplicationIcon(ai))
                    }.getOrNull()
                }.distinctBy { it.packageName }.filter { it.packageName != context.packageName }.take(4)
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); sessionSec++ } }
    LaunchedEffect(Unit) { while (true) { delay(1500); ping = Random.nextInt(18, 43) } }
    LaunchedEffect(addPulse) { if (addPulse) { delay(170); addPulse = false } }
    val timerText = remember(sessionSec) { "%02d:%02d".format(sessionSec / 60, sessionSec % 60) }

    // Focus accent follows the SETTINGS theme (Material You primary) — never hardcoded.
    val focusAccent = MaterialTheme.colorScheme.primary

    CompositionLocalProvider(LocalPanelAccent provides panelAccent) {
        val stripAccent = resolveSidebarAccent(style)
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)))
                )
                // Tap empty preview area = clear the highlight (explicit, not a reset).
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClearHighlight,
                )
        ) {
            val w = maxWidth; val h = maxHeight
            val L = computeLayout(style, w, h, apps.size)

            Text(
                "GAME PREVIEW", color = Color.White.copy(0.25f), fontSize = 22.sp,
                fontWeight = FontWeight.Black, letterSpacing = 4.sp,
                modifier = Modifier.align(Alignment.Center),
            )

            Box(Modifier.fillMaxSize()) {
                // 1. rail background = glass base + accent tint (same stack as the real strip)
                Box(
                    Modifier.offset(L.rail.left, L.rail.top)
                        .width(L.rail.width).height(L.rail.height)
                        .clip(style.containerShape())
                        .background(glassBrush(stripAccent))
                ) {
                    Box(
                        Modifier.matchParentSize()
                            .background(stripAccent.copy(style.bgAlpha / 100f))
                    )
                }
                // 2. edge chrome — identical call to the real strip → 1:1
                EdgeChrome(
                    style, stripAccent, style.containerShape(),
                    Modifier.offset(L.rail.left, L.rail.top).width(L.rail.width).height(L.rail.height),
                )
                // 3. leads (timer/ping tick live; record/bubble toggle on tap)
                L.leads.forEach { ld ->
                    when (ld.kind) {
                        LeadKind.TIMER -> PreviewText(ld.rect, timerText, stripAccent.copy(0.9f), 11.sp)
                        LeadKind.PING -> PreviewText(ld.rect, "${ping}ms", pingColor(ping), 10.sp)
                        LeadKind.RECORD -> PreviewCapsule(
                            ld.rect, recording,
                            R.drawable.materialsymbols_ic_videocam_rounded_filled,
                            tint = Color.White,
                            bg = if (recording) Color(0xFFFF1744) else Color.White.copy(0.10f),
                        ) { recording = !recording }
                        LeadKind.BUBBLE -> PreviewCapsule(
                            ld.rect, bubble,
                            if (bubble) R.drawable.materialsymbols_ic_bubble_rounded_filled
                            else R.drawable.materialsymbols_ic_select_window_rounded_filled,
                            tint = if (bubble) Color(0xFF00E676) else Color.White,
                            bg = if (bubble) Color(0xFF00E676).copy(0.2f) else Color.White.copy(0.10f),
                        ) { bubble = !bubble }
                    }
                }
                // 4. app icons (press-scale feedback)
                L.icons.forEachIndexed { i, r ->
                    apps.getOrNull(i)?.let { app ->
                        PreviewAppIcon(r, app, style, pressed = pressedApp == i) {
                            pressedApp = if (pressedApp == i) -1 else i
                        }
                    }
                }
                // 5. add button (pop feedback)
                if (L.add.width > 0.dp) {
                    val addScale by animateFloatAsState(if (addPulse) 1.22f else 1f, tween(150), label = "add")
                    Box(
                        Modifier.offset(L.add.left, L.add.top)
                            .width(L.add.width).height(L.add.height)
                            .graphicsLayer { scaleX = addScale; scaleY = addScale }
                            .clip(CircleShape)
                            .background(stripAccent.copy(0.2f))
                            .clickable { addPulse = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = stripAccent, modifier = Modifier.size(18.dp))
                    }
                }
                // 6. handle — smooth shape (math fixed), tappable like the real one
                val dockedOnLeft = style.position == StripPosition.START
                val hShape = handleShape(style, dockedOnLeft)
                val hFill = handleFillColor(style, stripAccent)
                val hG = style.effectiveHandle()
                Box(
                    Modifier.offset(L.handle.left, L.handle.top)
                        .width(L.handle.width).height(L.handle.height)
                        .alpha(if (style.handleAutoHide) 0.3f else (style.handleIdleAlpha / 100f).coerceIn(0.05f, 1f))
                        .clip(hShape)
                        .then(if (hG.fill != HandleFill.NONE) Modifier.background(hFill) else Modifier)
                        .then(if (hG.outline) Modifier.border(0.5.dp, Color.White.copy(0.35f), hShape) else Modifier)
                        .clickable { /* demo: real handle opens the panel */ },
                    contentAlignment = Alignment.Center,
                ) {
                    if (hG.showGlyph) Icon(
                        painterResource(
                            if (dockedOnLeft) R.drawable.materialsymbols_ic_chevron_left_rounded_filled
                            else R.drawable.materialsymbols_ic_chevron_right_rounded_filled
                        ),
                        null, tint = readableOn(hFill).copy(0.85f), modifier = Modifier.size(10.dp),
                    )
                }
                // 7. flyout demo (only while editing labels in flyout mode)
                if (activeTarget == EditTarget.LABEL && style.labelMode == LabelMode.FLYOUT) {
                    L.icons.firstOrNull()?.let { r ->
                        val name = apps.firstOrNull()?.label ?: "App"
                        val bx = if (dockedOnLeft) r.right + 6.dp else r.left - 6.dp
                        Box(
                            Modifier.offset(if (dockedOnLeft) bx else bx - 92.dp, r.top)
                                .width(92.dp).height(22.dp)
                                .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.7f))
                                .border(0.5.dp, focusAccent.copy(0.6f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // 8. editor spotlight (drawn last; Canvas is non-clickable so demo taps pass through)
                val region = activeTarget?.let { L.targets[it] }
                if (activeTarget != null && region != null && region.width > 0.dp && region.height > 0.dp) {
                    EditFocusOverlay(activeTarget, region, focusAccent, style, w, h)
                }
            }
        }
    }
}

// ── interactive demo building blocks ────────────────────────────────────────
@Composable
private fun PreviewText(rect: DpRect, text: String, color: Color, size: TextUnit) {
    Box(
        Modifier.offset(rect.left, rect.top).width(rect.width).height(rect.height),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PreviewCapsule(
    rect: DpRect, active: Boolean, icon: Int, tint: Color, bg: Color, onToggle: () -> Unit,
) {
    val pulse by rememberInfiniteTransition("cap_$icon").animateFloat(
        0.85f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), "p"
    )
    Box(
        Modifier.offset(rect.left, rect.top).width(rect.width).height(rect.height)
            .graphicsLayer { if (active) alpha = pulse }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun PreviewAppIcon(
    r: DpRect, app: AppRef, style: SidebarStyle, pressed: Boolean, onPress: () -> Unit,
) {
    val scale by animateFloatAsState(if (pressed) 0.84f else 1f, tween(120), label = "app")
    val inline = style.labelMode == LabelMode.INLINE || (style.labelMode == LabelMode.OFF && style.showLabels)
    Column(
        Modifier.offset(r.left, r.top).width(r.width)
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0.5f, 0.5f) }
            .clickable(onClick = onPress),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            rememberDrawablePainter(app.icon), app.label,
            Modifier.size(r.width).alpha(style.idleAlpha / 100f).clip(style.iconShapeShape()),
        )
        if (inline) {
            Spacer(Modifier.height(LABEL_GAP))
            Text(
                app.label, color = Color.White.copy(0.85f), fontSize = style.labelSizeSp.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.width(r.width),
            )
        }
    }
}

// ── editor spotlight overlay (unchanged logic, neutral accent now) ──────────
private fun SidebarStyle.targetValue(t: EditTarget): String = when (t) {
    EditTarget.HANDLE -> "${handleWidthDp}×${handleHeightDp}"
    EditTarget.EDGE -> "$edgeAlpha%"
    EditTarget.ICON -> "${iconSizeDp}dp"
    EditTarget.LABEL -> "${labelSizeSp}sp"
    EditTarget.CONTAINER -> "${cornerSizeDp}dp"
    EditTarget.STRIP -> "${stripWidthDp}dp"
}

@Composable
private fun EditFocusOverlay(target: EditTarget, rect: DpRect, accent: Color, style: SidebarStyle, boundsW: Dp, boundsH: Dp) {
    val density = LocalDensity.current
    val pulse by rememberInfiniteTransition(label = "fp").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val scan = remember { Animatable(0f) }
    LaunchedEffect(target) { scan.snapTo(0f); scan.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val text = "$target  ${style.targetValue(target)}"
    val calloutW = (text.length * 6.5f).dp.coerceIn(58.dp, boundsW - 8.dp)
    val calloutH = 20.dp; val gap = 8.dp
    val above = rect.top - gap - calloutH >= 4.dp
    val calloutTop = if (above) rect.top - gap - calloutH else rect.bottom + gap
    val calloutLeft = (rect.cx - calloutW / 2f).coerceIn(4.dp, boundsW - 4.dp - calloutW)
    val rPx = with(density) { Rect(rect.left.toPx(), rect.top.toPx(), rect.right.toPx(), rect.bottom.toPx()) }
    val cPx = with(density) { Rect(calloutLeft.toPx(), calloutTop.toPx(), (calloutLeft + calloutW).toPx(), (calloutTop + calloutH).toPx()) }
    Canvas(Modifier.fillMaxSize()) {
        val W = size.width; val H = size.height
        val scrim = Color.Black.copy(0.55f)
        drawRect(scrim, Offset(0f, 0f), Size(W, rPx.top))
        drawRect(scrim, Offset(0f, rPx.bottom), Size(W, H - rPx.bottom))
        drawRect(scrim, Offset(0f, rPx.top), Size(rPx.left, rPx.height))
        drawRect(scrim, Offset(rPx.right, rPx.top), Size(W - rPx.right, rPx.height))
        val cr = CornerRadius(10.dp.toPx())
        drawRoundRect(accent.copy(0.18f * pulse), rPx.topLeft, rPx.size, cr, style = Stroke(7.dp.toPx()))
        drawRoundRect(accent.copy(0.9f * pulse), rPx.topLeft, rPx.size, cr, style = Stroke(1.6.dp.toPx()))
        drawTargetTicks(rPx, 8.dp.toPx(), 2.4.dp.toPx(), accent)
        val cxp = rPx.center.x; val cyp = rPx.center.y; val rc = 5.dp.toPx()
        drawLine(accent.copy(0.8f * pulse), Offset(cxp - rc, cyp), Offset(cxp + rc, cyp), 1.4.dp.toPx())
        drawLine(accent.copy(0.8f * pulse), Offset(cxp, cyp - rc), Offset(cxp, cyp + rc), 1.4.dp.toPx())
        if (scan.value < 1f) {
            val sy = rPx.top + (rPx.bottom - rPx.top) * scan.value; val sa = (1f - scan.value) * 0.9f
            drawLine(accent.copy(sa), Offset(rPx.left - 4.dp.toPx(), sy), Offset(rPx.right + 4.dp.toPx(), sy), 2.dp.toPx())
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(sa * 0.4f))),
                Offset(rPx.left, sy - 10.dp.toPx()), Size(rPx.width, 10.dp.toPx()))
        }
        val fromY = if (above) cPx.bottom else cPx.top; val toY = if (above) rPx.top else rPx.bottom
        drawLine(accent.copy(0.7f), Offset(cPx.center.x, fromY), Offset(rPx.center.x, toY), 1.2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()), 0f))
    }
    Box(
        Modifier.offset(calloutLeft, calloutTop).width(calloutW).height(calloutH)
            .clip(chamferShape(6.dp, 2.dp)).background(accent), contentAlignment = Alignment.Center,
    ) {
        Text(text, color = readableOn(accent), fontSize = 9.sp, fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp, maxLines = 1)
    }
}

private fun DrawScope.drawTargetTicks(r: Rect, t: Float, tw: Float, accent: Color) {
    val a = accent.copy(0.95f)
    drawLine(a, Offset(r.left, r.top), Offset(r.left + t, r.top), tw)
    drawLine(a, Offset(r.left, r.top), Offset(r.left, r.top + t), tw)
    drawLine(a, Offset(r.right, r.top), Offset(r.right - t, r.top), tw)
    drawLine(a, Offset(r.right, r.top), Offset(r.right, r.top + t), tw)
    drawLine(a, Offset(r.left, r.bottom), Offset(r.left + t, r.bottom), tw)
    drawLine(a, Offset(r.left, r.bottom), Offset(r.left, r.bottom - t), tw)
    drawLine(a, Offset(r.right, r.bottom), Offset(r.right - t, r.bottom), tw)
    drawLine(a, Offset(r.right, r.bottom), Offset(r.right, r.bottom - t), tw)
}
