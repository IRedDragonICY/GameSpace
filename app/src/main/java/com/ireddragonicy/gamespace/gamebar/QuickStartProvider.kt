/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.DockSort
import com.ireddragonicy.gamespace.data.DockSource
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.data.model.AppRef
import com.ireddragonicy.gamespace.preferences.AppListPreferences
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

/**
 * Mode-aware app strip — v2, user-configurable.
 *
 * The strip mixes two kinds of slots:
 *
 *   PINNED — explicit user picks (added / reordered / tap-pinned in the
 *            strip's edit mode). Always first, always in the user's order,
 *            never moved by any refresh.
 *   AUTO   — free slots filled from the mode's app pool, ordered by the
 *            user-chosen [DockSort] (recently used / most used / A–Z).
 *
 * Total slots = AppSettings.dockMaxApps (default 20, range 5..30).
 * Removing an app in edit mode hides it from auto-fill for that mode;
 * picking it again from the add-picker unhides + pins it.
 *
 * A pref listener rebuilds every visible dock the moment a dock_* setting
 * changes, so the strip reacts live — no restart, no "apply" button.
 */
class QuickStartProvider(
    private val context: Context,
    private val appSettings: AppSettings,
    private val systemSettings: SystemSettings,
) {
    private val pm = context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flows = ConcurrentHashMap<Int, MutableStateFlow<List<DockItem>>>()
    private val built = ConcurrentHashMap.newKeySet<Int>()

    @Volatile private var launchableCache: Set<String>? = null
    @Volatile private var usageCache: Map<String, UsageScore>? = null
    @Volatile private var usageCacheAt = 0L

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            launchableCache = null
            usageCache = null
            flows.keys.forEach { build(it) }
        }
    }

    /** Any dock_* setting change rebuilds every visible dock — live. */
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("dock_") == true) {
                flows.keys.forEach { build(it) }
            }
        }

    init {
        context.registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        migrateLegacyIfNeeded()
        PreferenceManager.getDefaultSharedPreferences(context)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    // ── public API ──────────────────────────────────────────────────

    fun listFor(mode: Int): StateFlow<List<DockItem>> =
        flows.getOrPut(mode) { MutableStateFlow(emptyList()) }.also {
            if (built.add(mode)) build(mode)
        }

    fun prefetch(mode: Int) {
        listFor(mode)
    }

    /** Re-sort the AUTO slots now (panel opened). Pinned slots never move. */
    fun refresh(mode: Int) = build(mode)

    fun rebuild(mode: Int) = build(mode)

    /**
     * The strip's edit mode wrote back: [pinned] is the new pinned order,
     * [newlyHidden] are packages the user dragged out of the strip.
     */
    fun editDock(mode: Int, pinned: List<String>, newlyHidden: Set<String>) {
        appSettings.setDockPinned(mode, pinned)
        if (newlyHidden.isNotEmpty()) {
            appSettings.setDockHidden(mode, appSettings.getDockHidden(mode) + newlyHidden)
        }
        build(mode)
    }

    /** Add-picker chose an app: un-hide it and pin it at the end. */
    fun pinApp(mode: Int, pkg: String) {
        appSettings.setDockHidden(mode, appSettings.getDockHidden(mode) - pkg)
        val pinned = appSettings.getDockPinned(mode).toMutableList()
        if (pkg !in pinned) pinned.add(pkg)
        appSettings.setDockPinned(mode, pinned)
        build(mode)
    }

    fun isHidden(mode: Int, pkg: String): Boolean =
        pkg in appSettings.getDockHidden(mode)

    fun dispose() {
        runCatching { context.unregisterReceiver(packageReceiver) }
        PreferenceManager.getDefaultSharedPreferences(context)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
    }

    // ── build pipeline ──────────────────────────────────────────────

    private fun build(mode: Int) {
        scope.launch {
            val max = appSettings.dockMaxApps
            val exclude = EntryPointAccessors.fromApplication(
                context.applicationContext, ServiceViewEntryPoint::class.java
            ).activeSessionStore().currentPackage
            val hidden = appSettings.getDockHidden(mode)

            // 1 — pinned first, user's order, capped at the dock size.
            val pinned = resolve(appSettings.getDockPinned(mode), exclude, max)
            val items = pinned.map { DockItem(it, pinned = true) }.toMutableList()

            // 2 — fill the free slots from the mode's pool, sorted.
            if (appSettings.dockAutoFill && items.size < max) {
                val pinnedSet = pinned.map { it.packageName }.toSet()
                val sorted = sortPool(
                    poolFor(mode).filter { (pkg, _) -> pkg !in pinnedSet && pkg !in hidden },
                    appSettings.dockSort,
                )
                items += resolve(sorted, exclude, max - items.size)
                    .map { DockItem(it, pinned = false) }
            }

            flows.getOrPut(mode) { MutableStateFlow(emptyList()) }.value = items
        }
    }

    private fun poolFor(mode: Int): List<Pair<String, ApplicationInfo>> =
        when (appSettings.dockSource) {
            // User wants usage/recent across EVERYTHING — ignore the session
            // category entirely. This is what makes the strip stop being
            // "games only" while gaming.
            DockSource.ALL_APPS -> plainPool()
            // Default: candidates track what you're doing right now.
            DockSource.BY_MODE -> when (mode) {
                SidebarMode.MODE_GAME -> gamePool()
                SidebarMode.MODE_VIDEO -> videoPool()
                else -> plainPool()
            }
        }

    private fun gamePool(): List<Pair<String, ApplicationInfo>> {
        val registered = systemSettings.userGames.mapNotNull { g ->
            appInfo(g.packageName)?.let { g.packageName to it }
        }
        val registeredSet = registered.map { it.first }.toSet()
        val denied = readDeniedList()
        val detected = if (systemSettings.autoGameDetect) {
            installedLaunchable().filter { (pkg, info) ->
                pkg !in registeredSet && pkg !in denied && AppClassifier.isGame(info)
            }
        } else emptyList()
        return registered + detected
    }

    private fun videoPool(): List<Pair<String, ApplicationInfo>> {
        val overrides = readVideoOverrides()
        return installedLaunchable().filter { (pkg, info) ->
            when (overrides[pkg]) {
                "1" -> true
                "0" -> false
                else -> AppClassifier.isVideo(info, pkg)
            }
        }
    }

    private fun plainPool(): List<Pair<String, ApplicationInfo>> = installedLaunchable()

    private fun sortPool(
        pool: List<Pair<String, ApplicationInfo>>,
        sort: DockSort,
    ): List<String> = when (sort) {
        DockSort.ALPHA -> pool.sortedBy {
            runCatching { pm.getApplicationLabel(it.second).toString() }
                .getOrDefault(it.first).lowercase()
        }.map { it.first }
        DockSort.RECENT -> {
            val u = usageScores()
            pool.sortedByDescending { u[it.first]?.lastUsed ?: 0L }.map { it.first }
        }
        DockSort.USAGE -> {
            val u = usageScores()
            pool.sortedByDescending { u[it.first]?.score ?: 0.0 }.map { it.first }
        }
    }

    private fun resolve(pkgs: List<String>, exclude: String?, take: Int): List<AppRef> =
        pkgs.asSequence()
            .distinct()
            .filter { it != exclude && it != context.packageName && it !in NOISE }
            .take(take)
            .mapNotNull { pkg ->
                appInfo(pkg)?.let { info ->
                    AppRef(
                        packageName = pkg,
                        label = pm.getApplicationLabel(info).toString(),
                        icon = pm.getApplicationIcon(info),
                    )
                }
            }
            .toList()

    // ── usage stats ─────────────────────────────────────────────────

    private data class UsageScore(
        val lastUsed: Long,
        val foregroundMs: Long,
        val score: Double,
    )

    /**
     * Merged per-package usage over the last [USAGE_WINDOW_MS].
     *
     * Two fixes over the old lastUsedMap():
     *  - INTERVAL_BEST returns several buckets per package; they are merged
     *    (sum foreground, max lastUsed) instead of last-one-wins.
     *  - "Most used" decays with age: 3h yesterday beats 30h last month.
     */
    private fun usageScores(): Map<String, UsageScore> {
        val now = System.currentTimeMillis()
        usageCache?.let { if (now - usageCacheAt < USAGE_CACHE_MS) return it }
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - USAGE_WINDOW_MS, now)
        }.getOrNull() ?: return emptyMap()

        class Acc { var last = 0L; var fg = 0L }
        val merged = HashMap<String, Acc>()
        for (s in stats) {
            if (s.lastTimeUsed <= 0 && s.totalTimeInForeground <= 0) continue
            val a = merged.getOrPut(s.packageName) { Acc() }
            if (s.lastTimeUsed > a.last) a.last = s.lastTimeUsed
            a.fg += s.totalTimeInForeground.coerceAtLeast(0)
        }
        val out = merged.mapValues { (_, a) ->
            val ageDays = (now - a.last).coerceAtLeast(0).toDouble() / DAY_MS
            UsageScore(a.last, a.fg, a.fg * exp(-ageDays / DECAY_DAYS))
        }
        usageCache = out
        usageCacheAt = now
        return out
    }

    private fun installedLaunchable(): List<Pair<String, ApplicationInfo>> =
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .asSequence()
            .filter { it.packageName in launchable() }
            .map { it.packageName to it }
            .toList()

    private fun launchable(): Set<String> {
        launchableCache?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
            .also { launchableCache = it }
    }

    private fun appInfo(pkg: String): ApplicationInfo? =
        runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()

    private fun readVideoOverrides(): Map<String, String> =
        parsePairList(Settings.System.getStringForUser(
            context.contentResolver, KEY_VIDEO_LIST, UserHandle.USER_CURRENT))

    private fun readDeniedList(): Set<String> =
        (Settings.System.getStringForUser(
            context.contentResolver, AppListPreferences.KEY_DENIED_LIST, UserHandle.USER_CURRENT) ?: "")
            .split(";").filter { it.isNotBlank() }.toSet()

    // ── legacy migration ────────────────────────────────────────────

    /**
     * One-shot: the old per-mode full lists and the global quick_start_apps
     * were manual picks — they become the pinned lists, so upgrading users
     * keep their strip exactly as it was.
     */
    private fun migrateLegacyIfNeeded() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val editor = prefs.edit()
        listOf(SidebarMode.MODE_GAME, SidebarMode.MODE_VIDEO, SidebarMode.MODE_PLAIN).forEach { m ->
            val legacy = appSettings.getDockList(m)
            if (legacy.isNotEmpty() && appSettings.getDockPinned(m).isEmpty()) {
                editor.putString(AppSettings.KEY_DOCK_PINNED_PREFIX + m, legacy.joinToString(","))
            }
        }
        val quick = appSettings.quickStartApps.split(",").filter { it.isNotBlank() }
        if (quick.isNotEmpty() && appSettings.getDockPinned(SidebarMode.MODE_PLAIN).isEmpty()) {
            editor.putString(
                AppSettings.KEY_DOCK_PINNED_PREFIX + SidebarMode.MODE_PLAIN,
                quick.joinToString(","),
            )
        }
        editor.putBoolean(KEY_MIGRATED, true).apply()
    }

    companion object {
        const val KEY_VIDEO_LIST = "sidebar_video_list"
        private const val KEY_MIGRATED = "dock_v2_migrated"
        private const val USAGE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
        private const val USAGE_CACHE_MS = 60_000L
        private const val DAY_MS = 24.0 * 60 * 60 * 1000
        private const val DECAY_DAYS = 7.0   // usage "half-life"
        private val NOISE = setOf(
            "com.android.systemui", "com.android.settings", "android",
            "com.android.launcher3", "com.google.android.apps.nexuslauncher",
            "com.android.vending",
        )
        fun parsePairList(raw: String?): Map<String, String> =
            (raw ?: "").split(";").mapNotNull {
                val p = it.split("=")
                if (p.size == 2 && p[0].isNotBlank()) p[0] to p[1] else null
            }.toMap()
    }
}
