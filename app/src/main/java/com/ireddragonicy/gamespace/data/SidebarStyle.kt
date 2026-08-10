/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.data

import org.json.JSONArray
import org.json.JSONObject

enum class CornerStyle(val key: String, val label: String) {
    CHAMFER("chamfer", "Chamfer (ROG)"),
    ROUNDED("rounded", "Rounded"),
    SQUARE("square", "Square");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: CHAMFER }
}

enum class IconShape(val key: String, val label: String) {
    CIRCLE("circle", "Circle"),
    ROUNDED("rounded", "Rounded"),
    SQUARE("square", "Square");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: ROUNDED }
}

enum class StripLayout(val key: String, val label: String) {
    VERTICAL("vertical", "Vertical rail"),
    BOTTOM("bottom", "Bottom bar");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: VERTICAL }
}

enum class StripPosition(val key: String, val label: String) {
    START("start", "Left"),
    END("end", "Right");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: END }
}

enum class StripAccentMode(val key: String, val label: String) {
    FOLLOW_PANEL("panel", "Follow panel"),
    CUSTOM("custom", "Custom");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: FOLLOW_PANEL }
}

enum class LabelMode(val key: String, val label: String) {
    OFF("off", "Off"),
    INLINE("inline", "Under icon"),
    FLYOUT("flyout", "Flyout on press");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: OFF }
}

enum class HandleFill(val key: String, val label: String) {
    DARK_GLASS("dark", "Dark glass"),
    FOLLOW_ACCENT("accent", "Follow accent"),
    CUSTOM("custom", "Custom color"),
    NONE("none", "No fill");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: DARK_GLASS }
}

enum class HandleAspect(val key: String, val label: String) {
    DOT("dot", "Dot"),
    TALL("tall", "Tall"),
    WIDE("wide", "Wide"),
    LINE("line", "Line"),
    TAB("tab", "Tab");
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: TALL }
}

/**
 * Raw geometry tokens. Corners are percentages per corner, defined for a
 * RIGHT-docked handle (flat side on the right). [handleShape] mirrors them
 * horizontally when the strip is on the left.
 */
data class HandleGeometry(
    val fill: HandleFill = HandleFill.DARK_GLASS,
    val outline: Boolean = true,
    val cornerTL: Int = 100,
    val cornerTR: Int = 0,
    val cornerBL: Int = 100,
    val cornerBR: Int = 0,
    val showGlyph: Boolean = true,
    val aspect: HandleAspect = HandleAspect.TALL,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fill", fill.key)
        put("outline", outline)
        put("cTL", cornerTL)
        put("cTR", cornerTR)
        put("cBL", cornerBL)
        put("cBR", cornerBR)
        put("glyph", showGlyph)
        put("aspect", aspect.key)
    }

    companion object {
        val DEFAULT = HandleGeometry()
        fun fromJson(o: JSONObject?): HandleGeometry {
            if (o == null) return DEFAULT
            return HandleGeometry(
                fill = HandleFill.fromKey(o.optString("fill")),
                outline = o.optBoolean("outline", true),
                cornerTL = o.optInt("cTL", 100),
                cornerTR = o.optInt("cTR", 0),
                cornerBL = o.optInt("cBL", 100),
                cornerBR = o.optInt("cBR", 0),
                showGlyph = o.optBoolean("glyph", true),
                aspect = HandleAspect.fromKey(o.optString("aspect")),
            )
        }
    }
}

enum class HandlePreset(val key: String, val label: String, val geometry: HandleGeometry) {
    PILL("pill", "Pill", HandleGeometry()),
    TAB("tab", "Tab", HandleGeometry(cornerTL = 40, cornerBL = 40, aspect = HandleAspect.TAB)),
    NOTCH("notch", "Notch", HandleGeometry(fill = HandleFill.FOLLOW_ACCENT)),
    CIRCLE("circle", "Circle", HandleGeometry(cornerTL = 100, cornerTR = 100, cornerBL = 100, cornerBR = 100, aspect = HandleAspect.DOT)),
    DOT("dot", "Dot", HandleGeometry(fill = HandleFill.FOLLOW_ACCENT, cornerTL = 100, cornerTR = 100, cornerBL = 100, cornerBR = 100, showGlyph = false, aspect = HandleAspect.DOT)),
    LINE("line", "Line", HandleGeometry(fill = HandleFill.FOLLOW_ACCENT, cornerTL = 50, cornerTR = 50, cornerBL = 50, cornerBR = 50, showGlyph = false, aspect = HandleAspect.LINE)),
    CHEVRON("chevron", "Chevron", HandleGeometry(fill = HandleFill.NONE, outline = false, cornerTL = 0, cornerTR = 0, cornerBL = 0, cornerBR = 0)),
    BLADE("blade", "Blade", HandleGeometry(cornerTL = 60, cornerBL = 20)),
    CUSTOM("custom", "Custom", HandleGeometry());
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: PILL }
}

data class EdgeGeometry(
    val borderWidthDp: Float = 1.6f,
    val glowRings: Int = 2,
    val topRail: Boolean = true,
    val cornerBlades: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bw", borderWidthDp.toDouble())
        put("rings", glowRings)
        put("rail", topRail)
        put("blades", cornerBlades)
    }

    companion object {
        val DEFAULT = EdgeGeometry()
        fun fromJson(o: JSONObject?): EdgeGeometry {
            if (o == null) return DEFAULT
            return EdgeGeometry(
                borderWidthDp = o.optDouble("bw", 1.6).toFloat(),
                glowRings = o.optInt("rings", 2),
                topRail = o.optBoolean("rail", true),
                cornerBlades = o.optBoolean("blades", true),
            )
        }
    }
}

enum class EdgePreset(val key: String, val label: String, val geometry: EdgeGeometry) {
    NEON("neon", "Neon", EdgeGeometry()),
    GLOW("glow", "Glow", EdgeGeometry(borderWidthDp = 2.0f, glowRings = 3, topRail = false, cornerBlades = false)),
    MINIMAL("minimal", "Minimal", EdgeGeometry(borderWidthDp = 1.0f, glowRings = 0, topRail = false, cornerBlades = false)),
    NONE("none", "None", EdgeGeometry(borderWidthDp = 0f, glowRings = 0, topRail = false, cornerBlades = false)),
    CUSTOM("custom", "Custom", EdgeGeometry());
    companion object { fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: NEON }
}

data class SidebarStyle(
    val layout: StripLayout = StripLayout.VERTICAL,
    val position: StripPosition = StripPosition.END,
    val cornerStyle: CornerStyle = CornerStyle.CHAMFER,
    val cornerSizeDp: Int = 12,
    val stripWidthDp: Int = 52,
    val bgAlpha: Int = 6,
    val itemSpacingDp: Int = 6,
    val verticalPaddingDp: Int = 8,
    val iconShape: IconShape = IconShape.ROUNDED,
    val iconCornerDp: Int = 12,
    val iconSizeDp: Int = 40,
    val idleAlpha: Int = 100,
    val showLabels: Boolean = false,
    val labelSizeSp: Int = 9,
    val labelMode: LabelMode = LabelMode.OFF,
    val accentMode: StripAccentMode = StripAccentMode.FOLLOW_PANEL,
    val accentColor: Int = 0xFF3CEDFF.toInt(),
    val visibleButtons: Set<String> = StripButtonSpec.DEFAULT_VISIBLE,
    val handlePreset: HandlePreset = HandlePreset.PILL,
    val handleGeometry: HandleGeometry = HandlePreset.PILL.geometry,
    val handleWidthDp: Int = 14,
    val handleHeightDp: Int = 36,
    val handleIdleAlpha: Int = 25,
    val handleIdleTimeoutSec: Int = 3,
    val handleOffsetY: Int = 0,
    val handleAutoHide: Boolean = false,
    val handleColor: Int = 0xCC1A1A1A.toInt(),
    val edgePreset: EdgePreset = EdgePreset.NEON,
    val edgeGeometry: EdgeGeometry = EdgePreset.NEON.geometry,
    val edgeAlpha: Int = 65,
) {
    fun effectiveHandle(): HandleGeometry =
        if (handlePreset == HandlePreset.CUSTOM) handleGeometry else handlePreset.geometry

    fun effectiveEdge(): EdgeGeometry =
        if (edgePreset == EdgePreset.CUSTOM) edgeGeometry else edgePreset.geometry

    fun showsInlineLabel(): Boolean = showLabels || labelMode == LabelMode.INLINE

    fun toJson(): JSONObject = JSONObject().apply {
        put("layout", layout.key)
        put("position", position.key)
        put("cornerStyle", cornerStyle.key)
        put("cornerSizeDp", cornerSizeDp)
        put("stripWidthDp", stripWidthDp)
        put("bgAlpha", bgAlpha)
        put("itemSpacingDp", itemSpacingDp)
        put("verticalPaddingDp", verticalPaddingDp)
        put("iconShape", iconShape.key)
        put("iconCornerDp", iconCornerDp)
        put("iconSizeDp", iconSizeDp)
        put("idleAlpha", idleAlpha)
        put("showLabels", showLabels)
        put("labelSizeSp", labelSizeSp)
        put("labelMode", labelMode.key)
        put("accentMode", accentMode.key)
        put("accentColor", accentColor)
        put("visibleButtons", JSONArray(visibleButtons.toList()))
        put("handlePreset", handlePreset.key)
        put("handleGeometry", handleGeometry.toJson())
        put("handleWidthDp", handleWidthDp)
        put("handleHeightDp", handleHeightDp)
        put("handleIdleAlpha", handleIdleAlpha)
        put("handleIdleTimeoutSec", handleIdleTimeoutSec)
        put("handleOffsetY", handleOffsetY)
        put("handleAutoHide", handleAutoHide)
        put("handleColor", handleColor)
        put("edgePreset", edgePreset.key)
        put("edgeGeometry", edgeGeometry.toJson())
        put("edgeAlpha", edgeAlpha)
    }

    companion object {
        val DEFAULT = SidebarStyle()
        fun fromJson(o: JSONObject?): SidebarStyle {
            if (o == null) return DEFAULT
            val buttons = mutableSetOf<String>()
            o.optJSONArray("visibleButtons")?.let { arr ->
                for (i in 0 until arr.length()) buttons.add(arr.getString(i))
            }
            return SidebarStyle(
                layout = StripLayout.fromKey(o.optString("layout")),
                position = StripPosition.fromKey(o.optString("position")),
                cornerStyle = CornerStyle.fromKey(o.optString("cornerStyle")),
                cornerSizeDp = o.optInt("cornerSizeDp", 12),
                stripWidthDp = o.optInt("stripWidthDp", 52),
                bgAlpha = o.optInt("bgAlpha", 6),
                itemSpacingDp = o.optInt("itemSpacingDp", 6),
                verticalPaddingDp = o.optInt("verticalPaddingDp", 8),
                iconShape = IconShape.fromKey(o.optString("iconShape")),
                iconCornerDp = o.optInt("iconCornerDp", 12),
                iconSizeDp = o.optInt("iconSizeDp", 40),
                idleAlpha = o.optInt("idleAlpha", 100),
                showLabels = o.optBoolean("showLabels", false),
                labelSizeSp = o.optInt("labelSizeSp", 9),
                labelMode = LabelMode.fromKey(o.optString("labelMode")),
                accentMode = StripAccentMode.fromKey(o.optString("accentMode")),
                accentColor = o.optInt("accentColor", DEFAULT.accentColor),
                visibleButtons = buttons.ifEmpty { StripButtonSpec.DEFAULT_VISIBLE },
                handlePreset = HandlePreset.fromKey(o.optString("handlePreset")),
                handleGeometry = HandleGeometry.fromJson(o.optJSONObject("handleGeometry")),
                handleWidthDp = o.optInt("handleWidthDp", 14),
                handleHeightDp = o.optInt("handleHeightDp", 36),
                handleIdleAlpha = o.optInt("handleIdleAlpha", 25),
                handleIdleTimeoutSec = o.optInt("handleIdleTimeoutSec", 3),
                handleOffsetY = o.optInt("handleOffsetY", 0),
                handleAutoHide = o.optBoolean("handleAutoHide", false),
                handleColor = o.optInt("handleColor", 0xCC1A1A1A.toInt()),
                edgePreset = EdgePreset.fromKey(o.optString("edgePreset")),
                edgeGeometry = EdgeGeometry.fromJson(o.optJSONObject("edgeGeometry")),
                edgeAlpha = o.optInt("edgeAlpha", 65),
            )
        }
    }
}