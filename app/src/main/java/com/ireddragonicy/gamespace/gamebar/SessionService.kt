/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2022-2024 crDroid Android Project
 * Copyright (C) 2025 AxionOS
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
package com.ireddragonicy.gamespace.gamebar

import android.annotation.SuppressLint
import android.app.GameManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager
import com.android.axion.platform.AxPlatformClient
import dagger.hilt.android.AndroidEntryPoint
import com.google.gson.Gson
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.GameSession
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.gamebar.brightness.BrightnessInteractor
import com.ireddragonicy.gamespace.gamebar.fps.FpsInteractor
import com.ireddragonicy.gamespace.gamebar.mapper.MapperController
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.touch.GameTouchModeManager
import com.ireddragonicy.gamespace.utils.GameModeUtils
import com.ireddragonicy.gamespace.utils.ScreenUtils
import com.ireddragonicy.gamespace.utils.isServiceRunning
import com.ireddragonicy.gamespace.data.fpsstats.FpsStatsCollector
import com.ireddragonicy.gamespace.data.fpsstats.FpsStatsRepository
import com.ireddragonicy.gamespace.data.ActiveGameHolder
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.display.DisplayColorManager

import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint(Service::class)
class SessionService : Hilt_SessionService() {
    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var settings: SystemSettings
    @Inject lateinit var session: GameSession
    @Inject lateinit var screenUtils: ScreenUtils
    @Inject lateinit var gameModeUtils: GameModeUtils
    @Inject lateinit var callListener: CallListener
    @Inject lateinit var danmakuService: DanmakuService
    @Inject lateinit var brightnessInteractor: BrightnessInteractor
    @Inject lateinit var fpsInteractor: FpsInteractor
    @Inject lateinit var tileRepository: TileRepository
    @Inject lateinit var fpsStatsCollector: FpsStatsCollector
    @Inject lateinit var fpsStatsRepository: FpsStatsRepository
    @Inject lateinit var perfTuner: PerfTuner
    @Inject lateinit var gson: Gson
    @Inject lateinit var monitorSettings: com.ireddragonicy.gamespace.gamebar.monitor.MonitorSettings
    @Inject lateinit var monitorTelemetry: com.ireddragonicy.gamespace.gamebar.monitor.MonitorTelemetry
    @Inject lateinit var telemetryBus: com.ireddragonicy.gamespace.telemetry.TelemetryBus

    private val displayColorManager by lazy { DisplayColorManager.get(this) }
    private val perAppStore by lazy { PerAppSettingStore(this) }

    private var currentPackage: String? = null
    private lateinit var gameManager: GameManager
    private lateinit var sidebar: GameSidebar
    private lateinit var mapperController: MapperController
    private lateinit var platform: AxPlatformClient
    private lateinit var touchModeManager: GameTouchModeManager
    private lateinit var monitorOverlayManager: com.ireddragonicy.gamespace.gamebar.monitor.MonitorOverlayManager

    private var dndEnabledByUs = false
    private var previousDndFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    @SuppressLint("WrongConstant")
    override fun onCreate() {
        // Workaround: When a system priv-app is updated via `adb install -r`,
        // Android may load the Application class from the old system APK but
        // Service classes from the new data overlay. This causes Hilt's
        // GeneratedComponentManager instanceof check to fail with:
        //   "Hilt service must be attached to an @HiltAndroidApp Application"
        // Fix: catch the exception, kill the process, and let the system restart
        // it cleanly with the correct classloader from the updated APK.
        try {
            super.onCreate()
        } catch (e: IllegalStateException) {
            if (e.message?.contains("HiltAndroidApp") == true) {
                val prefs = getSharedPreferences("hilt_recovery", Context.MODE_PRIVATE)
                val attempts = prefs.getInt("restart_attempts", 0)
                if (attempts < 3) {
                    prefs.edit().putInt("restart_attempts", attempts + 1).apply()
                    Log.w(TAG, "Hilt classloader mismatch after app update, " +
                            "restarting process (attempt ${attempts + 1}/3)", e)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return
                } else {
                    // Give up after 3 attempts to prevent restart loop
                    prefs.edit().putInt("restart_attempts", 0).apply()
                    Log.e(TAG, "Hilt injection failed after 3 retries, service disabled", e)
                    stopSelf()
                    return
                }
            }
            throw e
        }
        // Reset recovery counter on successful Hilt init
        getSharedPreferences("hilt_recovery", Context.MODE_PRIVATE)
            .edit().putInt("restart_attempts", 0).apply()

        Log.d(TAG, "SessionService created")

        platform = AxPlatformClient.getInstance()
        platform.init(this)

        gameManager = getSystemService(Context.GAME_SERVICE) as GameManager
        gameModeUtils.bind(gameManager)

        touchModeManager = GameTouchModeManager(this)

        tileRepository.init(platform)
        tileRepository.touchModeManager = touchModeManager

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val mainHandler = Handler(Looper.getMainLooper())

        monitorOverlayManager = com.ireddragonicy.gamespace.gamebar.monitor.MonitorOverlayManager(
            this, windowManager, monitorSettings, monitorTelemetry, fpsInteractor
        )
        tileRepository.monitorSettings = monitorSettings
        tileRepository.monitorOverlayManager = monitorOverlayManager

        mapperController = MapperController(
            context = this,
            wm = windowManager,
            handler = mainHandler,
            gson = gson,
        )

        sidebar = GameSidebar(
            context = this,
            wm = windowManager,
            handler = mainHandler,
            appSettings = appSettings,
            screenUtils = screenUtils,
            danmakuService = danmakuService,
            brightnessInteractor = brightnessInteractor,
            fpsInteractor = fpsInteractor,
            settings = settings,
            tileRepository = tileRepository,
            platform = platform,
            mapperController = mapperController,
        )
        sidebar.onCreate()

        // Wire FPS Stats recording
        setupFpsStatsRecording()

        tileRepository.bringSidebarToFront = {
            sidebar.bringToFront()
        }
    }

    private val fpsStatsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun setupFpsStatsRecording() {
        tileRepository.onFpsStatsToggle = { shouldRecord ->
            if (shouldRecord && currentPackage != null) {
                val pkg = currentPackage!!
                val appName = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                } catch (_: Exception) { pkg }

                fpsStatsCollector.startRecording(pkg, appName)
                tileRepository.fpsStatsRecordingState.value = true

                // Observe elapsed seconds
                fpsStatsScope.launch {
                    fpsStatsCollector.elapsedSeconds.collect { seconds ->
                        tileRepository.fpsStatsElapsedSeconds.value = seconds
                    }
                }
            } else if (!shouldRecord) {
                val session = fpsStatsCollector.stopRecording()
                tileRepository.fpsStatsRecordingState.value = false
                tileRepository.fpsStatsElapsedSeconds.value = 0
                fpsStatsScope.coroutineContext.cancelChildren()

                if (session != null) {
                    fpsStatsRepository.saveSession(session)
                    android.widget.Toast.makeText(
                        this@SessionService,
                        "FPS Stats saved (${session.durationSec}s)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (packageName != null) {
                    startGameSession(packageName)
                } else {
                    Log.e(TAG, "No package name provided, stopping")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopGameSession()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sidebar.onConfigurationChanged(newConfig)
    }

    private fun startGameSession(packageName: String) {
        if (currentPackage == packageName) {
            Log.d(TAG, "Session already active for $packageName")
            return
        }
        
        if (currentPackage != null) {
            stopGameSession()
        }
        
        Log.i(TAG, "Starting game session for $packageName")
        currentPackage = packageName
        ActiveGameHolder.currentPackage = packageName
        tileRepository.currentGamePackage = packageName
        tileRepository.sessionStartTimeMs = android.os.SystemClock.elapsedRealtime()
        
        session.unregister()
        session.register(packageName)
        
        applyAutoDnd()

        // Activate Xiaomi touch HAL optimizations (HTSR, SuperTouch, etc.)
        touchModeManager.onGameStart(packageName)

        // Apply per-game CPU/GPU tuning (freq ranges + governors)
        perfTuner.applyForGame(packageName)

        // Start network ping monitor — resolve game UID for /proc/net/tcp filtering
        try {
            val gameUid = packageManager.getPackageUid(packageName, 0)
            tileRepository.networkPingMonitor.start(gameUid)
            tileRepository.networkSpeedMonitor.start(gameUid)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start ping monitor for $packageName", e)
        }

        sidebar.onGameStart(packageName)
        telemetryBus.start()
        monitorOverlayManager.start()

        // Unified display color mode — single owner
        displayColorManager.applyForGame(perAppStore.displayStyle(packageName))

        callListener.init()
    }

    private fun stopGameSession() {
        Log.i(TAG, "Stopping game session")

        // Auto-stop FPS Stats recording if active
        if (fpsStatsCollector.isRecording.value) {
            val fpsSession = fpsStatsCollector.stopRecording()
            tileRepository.fpsStatsRecordingState.value = false
            tileRepository.fpsStatsElapsedSeconds.value = 0
            fpsStatsScope.coroutineContext.cancelChildren()
            if (fpsSession != null) {
                fpsStatsRepository.saveSession(fpsSession)
            }
        }

        sidebar.onGameLeave()
        telemetryBus.stop()
        monitorOverlayManager.stop()
        touchModeManager.onGameStop()
        perfTuner.restoreDefaults()
        tileRepository.networkPingMonitor.stop()
        tileRepository.networkSpeedMonitor.stop()
        session.unregister()
        callListener.destroy()
        restoreAutoDnd()

        displayColorManager.restore()
        ActiveGameHolder.currentPackage = null

        currentPackage = null
        tileRepository.currentGamePackage = null
    }

    private fun applyAutoDnd() {
        if (!appSettings.autoDnd) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val currentFilter = nm.currentInterruptionFilter
        if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            currentFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            previousDndFilter = currentFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            dndEnabledByUs = true
        }
    }

    private fun restoreAutoDnd() {
        if (!dndEnabledByUs) return
        dndEnabledByUs = false
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.setInterruptionFilter(previousDndFilter)
    }

    override fun onDestroy() {
        Log.d(TAG, "SessionService destroyed")
        stopGameSession()
        tileRepository.dispose()
        fpsStatsScope.cancel()
        gameModeUtils.unbind()
        danmakuService.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "SessionService"
        const val ACTION_START = "game_start"
        const val ACTION_STOP = "game_stop"
        const val EXTRA_PACKAGE_NAME = "package_name"

        fun start(context: Context, app: String) {
            if (!context.isServiceRunning(SessionService::class.java)) {
                Intent(context, SessionService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_PACKAGE_NAME, app)
                }.let {
                    context.startServiceAsUser(it, UserHandle.CURRENT)
                }
            }
        }

        fun stop(context: Context) {
            if (context.isServiceRunning(SessionService::class.java)) {
                Intent(context, SessionService::class.java).apply {
                    action = ACTION_STOP
                }.let {
                    context.startServiceAsUser(it, UserHandle.CURRENT)
                }
            }
        }
    }
}
