/*
* Copyright (C) 2026 IRedDragonICY
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*/
package com.ireddragonicy.gamespace.data

/**
 * An ordered stack of filters, the way Freestyle models one: you add the
 * filters you want, arrange them, and each is a card with its own controls.
 *
 * Order is real, not cosmetic — the layer evaluates the stack in this sequence,
 * so Color-then-Details and Details-then-Color give different pictures.
 *
 * One exception, forced by frame generation: the SCREEN-space filters
 * ([FilterKind.isScreenSpace]) always run last, after generation. They cannot
 * run earlier because grain fed to the motion estimator reads as motion and
 * produces generation artifacts, and a vignette or letterbox applied before
 * generation is warped by the motion field and smears with the camera. Relative
 * order WITHIN each group is honoured; the UI shows the split so it is not a
 * surprise.
 */
enum class FilterKind(
    val label: String,
    /** Slider labels, in parameter order. At most 6. */
    val paramLabels: List<String>,
    val defaults: List<Float>,
    val ranges: List<ClosedFloatingPointRange<Float>>,
    val isScreenSpace: Boolean = false,
) {
    EXPOSURE(
        "Exposure",
        listOf("Exposure", "Contrast", "Highlights", "Shadows", "Gamma"),
        listOf(0f, 1f, 0f, 0f, 1f),
        listOf(-2f..2f, 0.5f..2f, -0.6f..0.6f, -0.6f..0.6f, 0.5f..2f),
    ),
    COLOR(
        "Color",
        listOf("Temperature", "Tint", "Vibrance", "Saturation", "Hue"),
        listOf(0f, 0f, 0f, 1f, 0f),
        listOf(-1f..1f, -1f..1f, -1f..1f, 0f..2f, -3.14f..3.14f),
    ),
    DETAILS(
        "Details",
        listOf("Sharpen", "Clarity", "HDR Toning", "Bloom", "Bloom threshold"),
        listOf(0f, 0f, 0f, 0f, 0.75f),
        listOf(0f..1f, -1f..1f, 0f..1f, 0f..1f, 0.2f..1f),
    ),
    LEVELS(
        "Levels",
        listOf("Black point", "White point", "Brightness"),
        listOf(0f, 1f, 0f),
        listOf(0f..0.5f, 0.5f..1f, -0.5f..0.5f),
    ),
    BLACK_WHITE(
        "Black & White",
        listOf("Amount"),
        listOf(1f),
        listOf(0f..1f),
    ),
    SEPIA(
        "Sepia",
        listOf("Amount"),
        listOf(0.85f),
        listOf(0f..1f),
    ),
    COLORBLIND(
        "Colorblind",
        listOf("Mode", "Strength"),
        listOf(1f, 0.7f),
        listOf(0f..3f, 0f..1f),
    ),
    // Compares against the UNFILTERED frame, so it belongs to the pre-generation
    // stage where the original is still available — not with the screen-space
    // group below.
    SPLITSCREEN(
        "Splitscreen",
        listOf("Divider"),
        listOf(0.5f),
        listOf(0f..1f),
    ),
    VIGNETTE(
        "Vignette",
        listOf("Strength"),
        listOf(0.4f),
        listOf(0f..1f),
        isScreenSpace = true,
    ),
    FILM_GRAIN(
        "Film Grain",
        listOf("Amount"),
        listOf(0.4f),
        listOf(0f..1f),
        isScreenSpace = true,
    ),
    LETTERBOX(
        "Letterbox",
        listOf("Size"),
        listOf(0.11f),
        listOf(0f..0.25f),
        isScreenSpace = true,
    );

    /** Integer-valued parameters render as a segmented picker, not a slider. */
    fun isDiscrete(index: Int): Boolean = this == COLORBLIND && index == 0
}

/** One filter in the stack: a kind plus up to six parameters. */
data class FilterNode(
    val kind: FilterKind,
    val params: List<Float> = kind.defaults,
) {
    fun withParam(index: Int, value: Float): FilterNode {
        val next = params.toMutableList()
        while (next.size <= index) next.add(0f)
        next[index] = value
        return copy(params = next)
    }

    fun param(index: Int): Float = params.getOrElse(index) {
        kind.defaults.getOrElse(index) { 0f }
    }

    /** "kind,p0..p5" — one system property per node, so 92 bytes is plenty. */
    fun serialize(): String {
        val p = (0 until 6).map { param(it) }
        return (listOf(kind.ordinal.toFloat()) + p).joinToString(",") { String.format(java.util.Locale.ROOT, "%.3f", it) }
    }
}

/**
 * The whole stack. Capped at [MAX] because the layer holds the parameters in
 * fixed-size uniform arrays and reads one property per slot.
 */
data class FilterStack(val nodes: List<FilterNode> = emptyList()) {

    fun add(kind: FilterKind): FilterStack =
        if (nodes.size >= MAX) this else FilterStack(nodes + FilterNode(kind))

    fun removeAt(index: Int): FilterStack =
        FilterStack(nodes.filterIndexed { i, _ -> i != index })

    fun move(index: Int, delta: Int): FilterStack {
        val target = index + delta
        if (index !in nodes.indices || target !in nodes.indices) return this
        val next = nodes.toMutableList()
        next.add(target, next.removeAt(index))
        return FilterStack(next)
    }

    fun replaceAt(index: Int, node: FilterNode): FilterStack {
        if (index !in nodes.indices) return this
        val next = nodes.toMutableList()
        next[index] = node
        return FilterStack(next)
    }

    /** Persisted form: nodes separated by ';'. */
    fun serialize(): String = nodes.joinToString(";") { it.serialize() }

    companion object {
        const val MAX = 8

        val EMPTY = FilterStack()

        fun deserialize(s: String?): FilterStack {
            if (s.isNullOrBlank()) return EMPTY
            val kinds = FilterKind.values()
            val nodes = s.split(";").mapNotNull { chunk ->
                val f = chunk.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (f.isEmpty()) return@mapNotNull null
                val ord = f[0].toInt()
                if (ord !in kinds.indices) return@mapNotNull null
                FilterNode(kinds[ord], f.drop(1))
            }
            return FilterStack(nodes.take(MAX))
        }
    }
}

/**
 * Ready-made stacks. Each is just a list of filters the user could have built
 * by hand, so loading one and then editing it behaves exactly as expected.
 */
enum class FilterPreset(val label: String, val stack: FilterStack?) {
    VIVID("Vivid", FilterStack(listOf(
        FilterNode(FilterKind.COLOR, listOf(0f, 0f, 0.30f, 1.25f, 0f)),
        FilterNode(FilterKind.EXPOSURE, listOf(0f, 1.10f, 0f, 0f, 1f)),
        FilterNode(FilterKind.DETAILS, listOf(0.35f, 0f, 0f, 0f, 0.75f)),
    ))),
    CINEMA("Cinema", FilterStack(listOf(
        FilterNode(FilterKind.EXPOSURE, listOf(0f, 1.15f, -0.08f, -0.12f, 1f)),
        FilterNode(FilterKind.COLOR, listOf(0.12f, 0f, 0f, 0.90f, 0f)),
        FilterNode(FilterKind.DETAILS, listOf(0f, 0f, 0f, 0.25f, 0.75f)),
        FilterNode(FilterKind.VIGNETTE, listOf(0.35f)),
        FilterNode(FilterKind.LETTERBOX, listOf(0.11f)),
    ))),
    COMPETE("Compete", FilterStack(listOf(
        FilterNode(FilterKind.EXPOSURE, listOf(0f, 1.20f, 0f, 0.28f, 1.15f)),
        FilterNode(FilterKind.COLOR, listOf(0f, 0f, 0f, 1.15f, 0f)),
        FilterNode(FilterKind.DETAILS, listOf(0.60f, 0.25f, 0f, 0f, 0.75f)),
    ))),
    HDR("HDR", FilterStack(listOf(
        FilterNode(FilterKind.DETAILS, listOf(0f, 0f, 0.75f, 0.20f, 0.75f)),
        FilterNode(FilterKind.COLOR, listOf(0f, 0f, 0.20f, 1f, 0f)),
    ))),
    FILM("Film", FilterStack(listOf(
        FilterNode(FilterKind.COLOR, listOf(0f, 0f, 0f, 0.85f, 0f)),
        FilterNode(FilterKind.SEPIA, listOf(0.35f)),
        FilterNode(FilterKind.FILM_GRAIN, listOf(0.45f)),
        FilterNode(FilterKind.VIGNETTE, listOf(0.40f)),
    ))),
    MONO("Mono", FilterStack(listOf(
        FilterNode(FilterKind.BLACK_WHITE, listOf(1f)),
        FilterNode(FilterKind.EXPOSURE, listOf(0f, 1.10f, 0f, 0f, 1f)),
        FilterNode(FilterKind.DETAILS, listOf(0f, 0.30f, 0f, 0f, 0.75f)),
    ))),
    NIGHT("Night", FilterStack(listOf(
        FilterNode(FilterKind.COLOR, listOf(0.45f, 0f, 0f, 0.85f, 0f)),
        FilterNode(FilterKind.LEVELS, listOf(0f, 1f, -0.05f)),
    ))),
}
