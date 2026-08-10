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
 * Colour look for video playback, expressed in Qualcomm VPP AIE terms.
 *
 * Unlike [FilterStack] — which is our own shader running inside the game's GL/VK pipeline
 * — every field here maps 1:1 onto a codec2 vendor param the VPP block already implements,
 * so the grading happens in the display post-processing hardware and costs no GPU time:
 *
 * | field            | `vendor.qti-ext-vpp-aie.` param   |
 * |------------------|-----------------------------------|
 * | [cade]           | `cade-level`                      |
 * | [ltm]            | `ltm-level`                       |
 * | [aceStrength]    | `ltm-ace-str`                     |
 * | [brightnessHigh] | `ltm-ace-brightness-high`         |
 * | [brightnessLow]  | `ltm-ace-brightness-low`          |
 * | [satGain]        | `ltm-sat-gain`                    |
 * | [satOffset]      | `ltm-sat-offset`                  |
 *
 * CADE is contrast/detail enhancement; LTM is local tone mapping (the "HDR-ish" lift).
 *
 * NOTE: the 0..100 ranges below are an assumption, not a measurement — the blob exposes the
 * parameter names but not their bounds. Phase 0 device verification must confirm them; the
 * VPP filter clamps rather than fails, so an out-of-range value degrades quietly.
 */
data class VideoGrade(
    val cade: Int = 0,
    val ltm: Int = 0,
    val aceStrength: Int = 0,
    val brightnessHigh: Int = 0,
    val brightnessLow: Int = 0,
    val satGain: Int = 0,
    val satOffset: Int = 0,
) {
    /** Fixed field order — the media hook splits on it positionally. */
    fun serialize(): String = listOf(
        cade, ltm, aceStrength, brightnessHigh, brightnessLow, satGain, satOffset
    ).joinToString(",")

    /** True when the grade is the hardware default and the AIE pass can be skipped. */
    fun isIdentity(): Boolean = this == DEFAULT

    companion object {
        const val FIELD_COUNT = 7
        const val RANGE_MAX = 100

        val DEFAULT = VideoGrade()

        /** Gentle all-round lift — the "Standard" preset. */
        val VIVID = VideoGrade(cade = 60, ltm = 50, aceStrength = 40, satGain = 55)

        /** Detail-forward, no saturation push — better for text-heavy or animated content. */
        val CRISP = VideoGrade(cade = 80, ltm = 30, aceStrength = 25)

        /** Shadow lift for dark scenes without blowing highlights. */
        val CINEMA = VideoGrade(ltm = 70, aceStrength = 60, brightnessLow = 45, satGain = 35)

        fun parse(csv: String): VideoGrade {
            if (csv.isEmpty()) return DEFAULT
            val v = csv.split(",")
            if (v.size < FIELD_COUNT) return DEFAULT
            fun at(i: Int) = v[i].trim().toIntOrNull()?.coerceIn(0, RANGE_MAX) ?: 0
            return VideoGrade(
                cade = at(0),
                ltm = at(1),
                aceStrength = at(2),
                brightnessHigh = at(3),
                brightnessLow = at(4),
                satGain = at(5),
                satOffset = at(6),
            )
        }
    }
}
