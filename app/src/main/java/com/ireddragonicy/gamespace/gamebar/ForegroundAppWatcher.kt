/*
 * Copyright (C) 2026 IRedDragonICY
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ireddragonicy.gamespace.gamebar

import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Event-driven watcher that listens to foreground app changes via TaskStackListener.
 * 0% CPU overhead while idle.
 */
class ForegroundAppWatcher(
    private val context: Context,
    private val onForegroundAppChanged: (packageName: String) -> Unit
) {
    companion object {
        private const val TAG = "ForegroundAppWatcher"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRegistered = false
    private var lastTopPackage: String? = null

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            handler.post {
                checkTopPackage()
            }
        }

        override fun onTaskMovedToFront(taskInfo: android.app.ActivityManager.RunningTaskInfo?) {
            handler.post {
                checkTopPackage()
            }
        }
    }

    fun start() {
        if (isRegistered) return
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener)
            isRegistered = true
            Log.i(TAG, "ForegroundAppWatcher started")
            checkTopPackage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register TaskStackListener", e)
        }
    }

    fun stop() {
        if (!isRegistered) return
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(taskStackListener)
            isRegistered = false
            Log.i(TAG, "ForegroundAppWatcher stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister TaskStackListener", e)
        }
    }

    private fun checkTopPackage() {
        try {
            val tasks = ActivityTaskManager.getService().getTasks(1, false, true, -1)
            val topTask = tasks.firstOrNull() ?: return
            val topPackage = topTask.topActivity?.packageName ?: topTask.baseActivity?.packageName ?: return

            if (topPackage != lastTopPackage) {
                lastTopPackage = topPackage
                onForegroundAppChanged(topPackage)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get top task", e)
        }
    }
}
