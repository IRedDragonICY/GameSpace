/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.ireddragonicy.gamespace.data.GameOptimizationManager
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.data.VideoSettingStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint(Service::class)
class GameSpaceService : Hilt_GameSpaceService() {

    companion object {
        private const val TAG = "GameSpaceService"
    }

    @Inject
    lateinit var gameOptimization: GameOptimizationManager

    @Inject
    lateinit var systemSettings: SystemSettings

    private var foregroundAppWatcher: ForegroundAppWatcher? = null
    private var currentPackage: String? = null
    private var currentMode: Int = SidebarMode.MODE_NONE

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GameSpaceService created — starting standalone ForegroundAppWatcher")

        foregroundAppWatcher = ForegroundAppWatcher(applicationContext) { pkg ->
            handleForegroundPackageChanged(pkg)
        }.also { it.start() }
    }

    private fun handleForegroundPackageChanged(packageName: String) {
        if (packageName == applicationContext.packageName) return
        if (packageName == "com.android.systemui" || packageName == "android") {
            // Launcher / System UI / Keyguard -> end active game session
            if (currentPackage != null) {
                onSessionEnd()
            }
            return
        }

        val mode = resolveSidebarMode(applicationContext, packageName)
        if (mode == SidebarMode.MODE_NONE) {
            if (currentPackage != null) {
                onSessionEnd()
            }
            return
        }

        if (currentPackage != packageName || currentMode != mode) {
            onSessionStart(packageName, mode)
        }
    }

    private fun resolveSidebarMode(context: Context, packageName: String): Int {
        return try {
            if (systemSettings.userGames.any { it.packageName == packageName })
                return SidebarMode.MODE_GAME

            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            if (systemSettings.autoGameDetect && AppClassifier.isGame(appInfo))
                return SidebarMode.MODE_GAME

            when (QuickStartProvider.parsePairList(Settings.System.getStringForUser(
                context.contentResolver, QuickStartProvider.KEY_VIDEO_LIST,
                UserHandle.USER_CURRENT))[packageName]) {
                "1" -> SidebarMode.MODE_VIDEO
                "0" -> SidebarMode.MODE_NONE
                else -> if (AppClassifier.isVideo(appInfo, packageName))
                            SidebarMode.MODE_VIDEO else SidebarMode.MODE_NONE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving sidebar mode for $packageName", e)
            SidebarMode.MODE_NONE
        }
    }

    private fun onSessionStart(packageName: String, mode: Int) {
        Log.d(TAG, "Session started: $packageName mode=${SidebarMode.toString(mode)}")
        currentPackage = packageName
        currentMode = mode
        SessionService.start(applicationContext, packageName, mode)

        if (mode == SidebarMode.MODE_GAME) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            Process.setThreadGroupAndCpuset(Process.myTid(), Process.THREAD_GROUP_SYSTEM)
            Process.setProcessGroup(Process.myPid(), Process.THREAD_GROUP_SYSTEM)
            gameOptimization.optimizeGameLaunch(packageName)
        }
    }

    private fun onSessionEnd() {
        Log.d(TAG, "Session ended")
        currentPackage = null
        currentMode = SidebarMode.MODE_NONE
        SessionService.stop(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "game_start" -> {
                intent.getStringExtra("package_name")?.let {
                    onSessionStart(it, intent.getIntExtra("mode", SidebarMode.MODE_GAME))
                }
            }
            "game_stop" -> onSessionEnd()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        foregroundAppWatcher?.stop()
        foregroundAppWatcher = null
        onSessionEnd()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
