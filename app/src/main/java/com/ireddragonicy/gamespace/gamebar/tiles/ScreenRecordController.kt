package com.ireddragonicy.gamespace.gamebar.tiles

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.ActiveSessionStore
import com.ireddragonicy.gamespace.gamebar.PanelStateStore
import com.ireddragonicy.gamespace.gamebar.SidebarMode
import com.ireddragonicy.gamespace.utils.isServiceRunning
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller that triggers AOSP SystemUI native RecordingService in AUTOSTART mode targeting single game task.
 */
@Singleton
class ScreenRecordController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val panelState: PanelStateStore,
    private val appSettings: AppSettings,
    private val activeSession: ActiveSessionStore,
) {
    private var lastToggleTime = 0L

    fun toggle(targetGameOnly: Boolean? = null) {
        if (panelState.screenRecordingActive.value) {
            stopRecording()
        } else {
            startRecording(targetGameOnly)
        }
    }

    fun startRecording(overrideTargetGameOnly: Boolean? = null) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToggleTime < 1500L) return
        lastToggleTime = now

        val targetGameOnly = overrideTargetGameOnly ?: (appSettings.screenRecordTargetMode == 0 && activeSession.mode == SidebarMode.MODE_GAME)

        try {
            val component = ComponentName(
                "com.android.systemui",
                "com.android.systemui.screenrecord.RecordingService"
            )

            val intent = Intent("com.android.systemui.screenrecord.START").apply {
                this.component = component
                putExtra("extra_resultCode", Activity.RESULT_OK)
                putExtra("extra_useAudio", appSettings.screenRecordAudioSource)
                putExtra("extra_showTaps", appSettings.screenRecordShowTaps)
                putExtra("extra_HEVC", appSettings.screenRecordHEVC)
                putExtra("extra_lowQuality", appSettings.screenRecordLowQuality)
                putExtra("extra_longerDuration", appSettings.screenRecordLongerDuration)
                putExtra("extra_resolutionMode", appSettings.screenRecordResolution)
                putExtra("extra_fpsMode", appSettings.screenRecordFps)

                if (targetGameOnly) {
                    val taskId = getFocusedTaskId()
                    if (taskId != null && taskId != -1) {
                        Log.d("ScreenRecordCtrl", "Attaching extra_captureTarget for taskId=$taskId")
                        putExtra("extra_captureTarget", MediaProjectionCaptureTarget(null, taskId))
                    } else {
                        Log.w("ScreenRecordCtrl", "No valid focused taskId found, falling back to full screen")
                    }
                }
            }
            context.startForegroundService(intent)
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 500)
        } catch (e: Exception) {
            Log.e("ScreenRecordCtrl", "Failed to start SystemUI screen recording", e)
        }
    }

    fun stopRecording() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToggleTime < 1500L) return
        lastToggleTime = now
        try {
            val component = ComponentName(
                "com.android.systemui",
                "com.android.systemui.screenrecord.RecordingService"
            )
            context.startForegroundService(
                Intent("com.android.systemui.screenrecord.STOP").apply { this.component = component })
            // The service takes a while to fully tear down (projection stop), so
            // re-check a few times — a single 500ms refresh can miss the change
            // and leave the panel showing a running counter forever.
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 500)
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 2000)
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 4000)
        } catch (e: Exception) {
            Log.e("ScreenRecordCtrl", "Failed to stop screen recording", e)
        }
    }

    fun refresh() {
        // getRunningServices is deprecated and stale on modern Android, so also
        // consult the actual projection state as ground truth.
        val projectionRunning = runCatching {
            val mpm = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
            mpm.activeProjectionInfos.any { it.packageName == "com.android.systemui" }
        }.getOrDefault(false)
        val running = projectionRunning ||
            context.isServiceRunning("com.android.systemui.screenrecord.RecordingService") ||
            context.isServiceRunning("com.android.systemui.screenrecord.service.ScreenRecordingService")
        panelState.screenRecordingActive.value = running
        if (running && panelState.screenRecordingStartElapsedMs.value == 0L) {
            panelState.screenRecordingStartElapsedMs.value = android.os.SystemClock.elapsedRealtime()
        } else if (!running) {
            panelState.screenRecordingStartElapsedMs.value = 0L
        }
    }

    private fun getFocusedTaskId(): Int? {
        return try {
            val tm = android.app.ActivityTaskManager.getService()
            val currentPkg = activeSession.currentPackage
            val tasks = tm.getTasks(10, false, true, -1)
            Log.d("ScreenRecordCtrl", "Looking for taskId for pkg: $currentPkg among ${tasks.size} tasks")
            if (currentPkg != null) {
                val gameTask = tasks.firstOrNull {
                    it.topActivity?.packageName == currentPkg || it.baseActivity?.packageName == currentPkg
                }
                if (gameTask != null) {
                    Log.d("ScreenRecordCtrl", "Found game task ID: ${gameTask.taskId} for $currentPkg")
                    return gameTask.taskId
                }
            }
            val topTask = tasks.firstOrNull()
            val taskId = topTask?.taskId ?: tm.focusedRootTaskInfo?.taskId
            Log.d("ScreenRecordCtrl", "Fallback task ID: $taskId")
            taskId
        } catch (e: Exception) {
            Log.w("ScreenRecordCtrl", "Failed to get focused task ID", e)
            null
        }
    }
}
