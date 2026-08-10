/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.touch

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Client for the OSS input-side accidental-touch filter.
 *
 * This intentionally does not use `ITouchFeature`: on onyx's THP stack the
 * proprietary HAL accepts scalar modes 7 and 15 but does not issue an ioctl
 * to `/dev/xiaomi-touch`.  The companion kernel change exposes one atomic
 * profile node at the point where TouchReport's coordinates become Android
 * input events, plus read-back and rejection counters for verification.
 */
object KernelTouchFilterClient {
    private const val TAG = "KernelTouchFilter"
    private const val BASE_PATH = "/sys/devices/virtual/touch/touch_dev"
    private const val PROFILE_PATH = "$BASE_PATH/gamespace_touch_profile"
    private const val CAPABILITIES_PATH = "$BASE_PATH/gamespace_touch_capabilities"
    private const val STATS_PATH = "$BASE_PATH/gamespace_touch_stats"

    const val LEVEL_OFF = 0
    const val LEVEL_LOW = 1
    const val LEVEL_MEDIUM = 2
    const val LEVEL_HIGH = 3

    const val ORIENTATION_PORTRAIT = 0
    const val ORIENTATION_LANDSCAPE = 1

    data class Profile(
        val edgeFilter: Int = LEVEL_OFF,
        val gripSuppression: Int = LEVEL_OFF,
        val orientation: Int = ORIENTATION_PORTRAIT,
    ) {
        fun normalized() = copy(
            edgeFilter = edgeFilter.coerceIn(LEVEL_OFF, LEVEL_HIGH),
            gripSuppression = gripSuppression.coerceIn(LEVEL_OFF, LEVEL_HIGH),
            orientation = orientation.coerceIn(ORIENTATION_PORTRAIT, 3),
        )
    }

    data class Stats(
        val edgeRejected: Long,
        val gripRejected: Long,
    ) {
        val totalRejected: Long get() = edgeRejected + gripRejected
    }

    /** True only after the matching OSS kernel ABI and SELinux policy are live. */
    val isAvailable: Boolean
        get() = File(CAPABILITIES_PATH).canRead() &&
            File(PROFILE_PATH).canRead() && File(PROFILE_PATH).canWrite()

    fun capabilities(): String? = readText(CAPABILITIES_PATH)

    fun readProfile(): Profile? {
        val fields = parseLongFields(readText(PROFILE_PATH) ?: return null)
        return Profile(
            edgeFilter = fields["edge_filter"]?.toInt() ?: return null,
            gripSuppression = fields["grip_suppression"]?.toInt() ?: return null,
            orientation = fields["orientation"]?.toInt() ?: return null,
        ).normalized()
    }

    /**
     * Atomically update edge level, grip level and orientation, then read the
     * driver state back.  `true` therefore means the kernel accepted *and*
     * retained exactly the requested profile—not merely that a write returned.
     */
    fun apply(profile: Profile): Boolean {
        val expected = profile.normalized()
        if (!isAvailable) return false

        return runCatching {
            FileOutputStream(PROFILE_PATH).use { output ->
                output.write(
                    "${expected.edgeFilter} ${expected.gripSuppression} ${expected.orientation}\n"
                        .toByteArray(Charsets.US_ASCII),
                )
            }
            readProfile() == expected
        }.onFailure {
            Log.w(TAG, "Unable to apply $expected", it)
        }.getOrDefault(false)
    }

    fun readStats(): Stats? {
        val fields = parseLongFields(readText(STATS_PATH) ?: return null)
        return Stats(
            edgeRejected = fields["edge_rejected"] ?: return null,
            gripRejected = fields["grip_rejected"] ?: return null,
        )
    }

    private fun readText(path: String): String? = runCatching {
        File(path).readText()
    }.onFailure {
        Log.d(TAG, "Cannot read $path", it)
    }.getOrNull()

    private fun parseLongFields(text: String): Map<String, Long> =
        text.trim().split(Regex("\\s+")).mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) null else {
                token.substring(0, separator) to token.substring(separator + 1).toLongOrNull()
            }
        }.filter { it.second != null }.associate { it.first to it.second!! }
}
