/*
 * Copyright (C) 2026 IRedDragonICY
 * Copyright (C) 2026 IRedDragonICY
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ireddragonicy.gamespace.gamebar.fps

import android.app.ActivityTaskManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemProperties
import android.view.WindowManager
import android.window.TaskFpsCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(FlowPreview::class)
class FpsInteractor @Inject constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val taskManager = ActivityTaskManager.getService()

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── FPS Flows ───────────────────────────────────────────────────
    // Real game FPS: how many frames the game itself renders per second
    private val _realFpsHistory = MutableStateFlow<List<Float>>(emptyList())
    val realFpsHistory: StateFlow<List<Float>> get() = _realFpsHistory

    // Generated FPS: how many frames AFME creates via interpolation/extrapolation
    private val _genFpsHistory = MutableStateFlow<List<Float>>(emptyList())
    val genFpsHistory: StateFlow<List<Float>> get() = _genFpsHistory

    // Total FPS: real + generated = what the display actually shows
    private val _totalFpsHistory = MutableStateFlow<List<Float>>(emptyList())
    val totalFpsHistory: StateFlow<List<Float>> get() = _totalFpsHistory

    private val _dynamicMaxFps = MutableStateFlow(60f)
    val dynamicMaxFps: StateFlow<Float> get() = _dynamicMaxFps

    // AFME state
    private val _isAfmeActive = MutableStateFlow(false)
    val isAfmeActive: StateFlow<Boolean> get() = _isAfmeActive

    private val _afmeMultiplier = MutableStateFlow(1)
    val afmeMultiplier: StateFlow<Int> get() = _afmeMultiplier

    @Suppress("DEPRECATION")
    val maxRefreshRate: Float by lazy {
        wm.defaultDisplay?.mode?.refreshRate ?: 60f
    }

    private val historySize = 100
    private val realBuffer = ArrayDeque<Float>(historySize)
    private val genBuffer = ArrayDeque<Float>(historySize)
    private val totalBuffer = ArrayDeque<Float>(historySize)

    private val fpsFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)

    private val fpsCallback = object : TaskFpsCallback() {
        override fun onFpsReported(fps: Float) {
            fpsFlow.tryEmit(fps)
        }
    }

    // ── AFME layer stats (logcat channel) ───────────────────────────
    // The AFME VK layer runs inside the game process (untrusted_app), which
    // platform sepolicy forbids from setting system properties. It logs
    // "AFME-STATS real=N gen=N total=N" once per second instead; we hold
    // READ_LOGS and tail logcat for it.
    @Volatile private var layerStats: Triple<Int, Int, Int>? = null
    @Volatile private var layerStatsAtMs: Long = 0L

    private fun startAfmeStatsReader() {
        coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                var proc: Process? = null
                try {
                    proc = ProcessBuilder(
                        "logcat", "-s", "AFME:I", "-T", "1", "-v", "brief")
                        .redirectErrorStream(true)
                        .start()
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        val m = AFME_STATS_RE.find(line) ?: return@forEachLine
                        layerStats = Triple(
                            m.groupValues[1].toInt(),
                            m.groupValues[2].toInt(),
                            m.groupValues[3].toInt())
                        layerStatsAtMs = android.os.SystemClock.uptimeMillis()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FpsInteractor", "AFME stats reader died", e)
                } finally {
                    proc?.destroy()
                }
                delay(2000)  // logcat exited — back off and reattach
            }
        }
    }

    init {
        startAfmeStatsReader()
        coroutineScope.launch {
            fpsFlow
                .sample(500)  // Sample every 500ms for smoother updates
                .collect { reportedFps ->
                    // Read AFME state from system properties.
                    //
                    // BOTH flags are required: .enable only means the layers are
                    // armed, which is also true for a colour-filter-only or
                    // AF-only session. .fg is what says frames are being
                    // generated — without it this readout invented a synthetic
                    // frame rate for sessions that generate nothing.
                    val afmeOn =
                        SystemProperties.getBoolean("persist.sys.afme.enable", false) &&
                        SystemProperties.getBoolean("persist.sys.afme.fg", true)
                    val mult = SystemProperties.getInt("persist.sys.afme.multiplier", 2)
                        .coerceIn(2, 4)

                    _isAfmeActive.value = afmeOn
                    _afmeMultiplier.value = if (afmeOn) mult else 1

                    val realFps: Float
                    val genFps: Float
                    val totalFps: Float

                    if (afmeOn) {
                        // ── Strategy 1: layer stats from the AFME-STATS logcat
                        // channel (authoritative, ≤3s old) ──
                        val stats = layerStats
                        val fresh = stats != null &&
                            android.os.SystemClock.uptimeMillis() - layerStatsAtMs < 3000L

                        if (fresh && stats != null && stats.third > 0) {
                            realFps = stats.first.toFloat()
                            genFps = stats.second.toFloat().coerceAtLeast(0f)
                            totalFps = stats.third.toFloat()
                        } else {
                            // No layer stats. Do NOT fabricate a real/gen split
                            // from the multiplier — if the layer never loaded
                            // (or skipped generation for lack of display
                            // headroom), that math shows generated frames that
                            // don't exist and masks real failures. Report the
                            // measured present rate as-is; a working layer is
                            // detected only through its own stats.
                            totalFps = reportedFps
                            realFps = reportedFps
                            genFps = 0f
                        }
                    } else {
                        // No AFME — everything is the game's own FPS
                        realFps = reportedFps
                        genFps = 0f
                        totalFps = reportedFps
                    }

                    // Smooth with balanced EMA (0.5/0.5)
                    val smoothedReal = (realBuffer.lastOrNull() ?: realFps) * 0.5f + realFps * 0.5f
                    if (realBuffer.size >= historySize) realBuffer.removeFirst()
                    realBuffer.addLast(smoothedReal)

                    val smoothedGen = (genBuffer.lastOrNull() ?: genFps) * 0.5f + genFps * 0.5f
                    if (genBuffer.size >= historySize) genBuffer.removeFirst()
                    genBuffer.addLast(smoothedGen)

                    val smoothedTotal = (totalBuffer.lastOrNull() ?: totalFps) * 0.5f + totalFps * 0.5f
                    if (totalBuffer.size >= historySize) totalBuffer.removeFirst()
                    totalBuffer.addLast(smoothedTotal)

                    // Dynamic max based on the highest value we're showing
                    val maxSource = if (afmeOn) totalBuffer else realBuffer
                    val dynamicMax = maxSource.maxOrNull()?.coerceAtLeast(15f) ?: 60f

                    _realFpsHistory.value = realBuffer.toList()
                    _genFpsHistory.value = genBuffer.toList()
                    _totalFpsHistory.value = totalBuffer.toList()
                    _dynamicMaxFps.value = dynamicMax
                }
        }
    }

    fun start() {
        val taskId = taskManager?.focusedRootTaskInfo?.taskId ?: return
        wm.registerTaskFpsCallback(taskId, Runnable::run, fpsCallback)
    }

    fun dispose() {
        wm.unregisterTaskFpsCallback(fpsCallback)
    }

    companion object {
        // Matches the AFME VK layer's once-per-second stats line:
        //   AFME-STATS real=40 gen=40 total=80
        private val AFME_STATS_RE =
            Regex("""AFME-STATS real=(\d+) gen=(\d+) total=(\d+)""")
    }
}
