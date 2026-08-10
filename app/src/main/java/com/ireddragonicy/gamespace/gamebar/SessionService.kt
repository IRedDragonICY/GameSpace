/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2022-2024 crDroid Android Project
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
package com.ireddragonicy.gamespace.gamebar

import android.annotation.SuppressLint
import android.app.GameManager
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager
import com.ireddragonicy.gamespace.gamebar.SidebarMode
import dagger.hilt.android.AndroidEntryPoint
import com.ireddragonicy.gamespace.gamebar.mapper.MapperController
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.DolbyProfileClient
import com.ireddragonicy.gamespace.data.GameSession
import com.ireddragonicy.gamespace.gamebar.video.DolbyBridge
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.gamebar.brightness.BrightnessInteractor
import com.ireddragonicy.gamespace.gamebar.fps.FpsInteractor
import com.ireddragonicy.gamespace.gamebar.tiles.TileRepository
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.touch.GameTouchModeManager
import com.ireddragonicy.gamespace.utils.GameModeUtils
import com.ireddragonicy.gamespace.utils.ScreenUtils
import com.ireddragonicy.gamespace.utils.isServiceRunning
import com.ireddragonicy.gamespace.data.fpsstats.FpsStatsCollector
import com.ireddragonicy.gamespace.data.fpsstats.FpsStatsRepository
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.data.SettingsIO
import com.ireddragonicy.gamespace.data.VideoSettingStore
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
    @Inject lateinit var monitorSettings: com.ireddragonicy.gamespace.gamebar.monitor.MonitorSettings
    @Inject lateinit var monitorTelemetry: com.ireddragonicy.gamespace.gamebar.monitor.MonitorTelemetry
    @Inject lateinit var telemetryBus: com.ireddragonicy.gamespace.telemetry.TelemetryBus

    private val displayColorManager by lazy { DisplayColorManager.get(this) }
    @Inject lateinit var perAppStore: PerAppSettingStore
    @Inject lateinit var settingsIO: SettingsIO
    @Inject lateinit var orchestrator: SessionOrchestrator
    private val videoStore by lazy { VideoSettingStore(this, settingsIO) }

    private var currentPackage: String? = null
    private var currentMode: Int = SidebarMode.MODE_NONE
    private lateinit var gameManager: GameManager
    private lateinit var sidebar: GameSidebar
    private lateinit var mapperController: MapperController
    @Inject lateinit var touchModeManager: GameTouchModeManager
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

        gameManager = getSystemService(Context.GAME_SERVICE) as GameManager
        gameModeUtils.bind(gameManager)

        tileRepository.init()

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val mainHandler = Handler(Looper.getMainLooper())

        monitorOverlayManager = com.ireddragonicy.gamespace.gamebar.monitor.MonitorOverlayManager(
            this, windowManager, monitorSettings, monitorTelemetry
        )
        tileRepository.monitorSettings = monitorSettings
        tileRepository.monitorOverlayManager = monitorOverlayManager

        mapperController = MapperController(
            context = this,
            wm = windowManager,
            handler = mainHandler,
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
            mapperController = mapperController,
        )
        sidebar.onCreate()

        // Wire FPS Stats recording
        setupFpsStatsRecording()

        tileRepository.bringSidebarToFront = {
            sidebar.bringToFront()
        }

        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    /**
     * The touch panel resets its report rate to 120Hz across suspend, so the
     * session's touch state has to be pushed again every time the screen comes
     * back on. Registered for the whole service life; [GameTouchModeManager]
     * no-ops when no session is active.
     */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            touchModeManager.onScreenOn()
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
                    startSession(packageName, intent.getIntExtra(EXTRA_MODE, SidebarMode.MODE_GAME))
                } else {
                    Log.e(TAG, "No package name provided, stopping")
                    stopSelf()
                }
            }
            ACTION_MODE_CHANGE -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val mode = intent.getIntExtra(EXTRA_MODE, SidebarMode.MODE_PLAIN)
                if (packageName != null) changeSessionMode(packageName, mode)
            }
            ACTION_STOP -> {
                stopSession()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sidebar.onConfigurationChanged(newConfig)
        touchModeManager.onConfigurationChanged(newConfig)
    }

    private fun startSession(packageName: String, mode: Int) {
        if (currentPackage == packageName && currentMode == mode) {
            Log.d(TAG, "Session already active for $packageName")
            return
        }

        if (currentPackage != null) {
            stopSession()
        }

        Log.i(TAG, "Starting ${SidebarMode.toString(mode)} session for $packageName")
        currentPackage = packageName
        currentMode = mode
        // Session state lives in ActiveSessionStore, set by
        // orchestrator.onSessionStart() below.

        // Everything below the sidebar itself is game-only. A video or plain session
        // must not register a GameManager session, seize the touch HAL, retune CPU/GPU
        // or take over the display colour pipeline — the user opened a browser, not a
        // game, and those side effects would be both surprising and costly on battery.
        val gameUid = runCatching { packageManager.getApplicationInfo(packageName, 0).uid }.getOrDefault(0)
        orchestrator.onSessionStart(packageName, mode, gameUid)

        if (mode == SidebarMode.MODE_GAME) {
            session.unregister()
            session.register(packageName)
            applyAutoDnd()
            touchModeManager.onGameStart(packageName)
            perfTuner.applyForGame(packageName)
            telemetryBus.start()
            monitorOverlayManager.start()
            callListener.init()
            applyDolbyPerGameProfile(packageName)
        }

        if (mode == SidebarMode.MODE_VIDEO) {
            videoStore.applyForSession(packageName)
        }

        sidebar.onSessionStart(packageName, mode)
    }

    /**
     * Apply per-game Dolby profile from LunarisDolby's SSOT.
     *
     * Flow:
     * 1. O(1) ContentProvider call → read profile for this game
     * 2. If present (>= 0): O(1) broadcast SET_PROFILE to LunarisDolby
     * 3. Ensure Dolby enabled: O(1) broadcast SET_ENABLED true
     */
    private fun applyDolbyPerGameProfile(packageName: String) {
        if (!DolbyBridge.isAvailable(this)) return

        val profile = DolbyProfileClient.getProfile(this, packageName)
        if (profile >= 0) {
            DolbyBridge.setEnabled(this, true)
            DolbyBridge.setProfile(this, profile)
            Log.i(TAG, "Dolby per-game profile applied: $packageName → " +
                    "${DolbyProfileClient.profileName(profile)} ($profile)")
        } else {
            Log.d(TAG, "No Dolby per-game profile for $packageName, " +
                    "LunarisDolby monitor will handle defaults")
        }
    }

    /**
     * Same app, different flavour — playback started or stopped. Only the overlay swaps;
     * no game tuning is involved, because a mode change never crosses into or out of
     * [SidebarMode.MODE_GAME] (the game list is static for the life of a session).
     */
    private fun changeSessionMode(packageName: String, mode: Int) {
        if (currentPackage != packageName) {
            // Focus moved on before the mode change landed — treat it as a fresh start
            // rather than applying a stale mode to whatever is in front now.
            startSession(packageName, mode)
            return
        }
        if (currentMode == mode) return

        Log.i(TAG, "Session mode ${SidebarMode.toString(currentMode)} -> " +
                "${SidebarMode.toString(mode)} for $packageName")
        val wasVideo = currentMode == SidebarMode.MODE_VIDEO
        currentMode = mode

        if (mode == SidebarMode.MODE_VIDEO) {
            videoStore.applyForSession(packageName)
        } else if (wasVideo) {
            videoStore.clearSession()
        }

        orchestrator.onSessionModeChanged(mode)
        sidebar.onSessionModeChanged(mode)
    }

    private fun stopSession() {
        Log.i(TAG, "Stopping session")

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

        sidebar.onSessionEnd()
        orchestrator.onSessionEnd()

        telemetryBus.stop()
        monitorOverlayManager.stop()
        touchModeManager.onGameStop()
        // Fall back to the global baseline, not to hardware defaults — leaving a
        // session should undo the game's overrides, not the user's daily tuning.
        perfTuner.applyGlobal()
        session.unregister()
        callListener.destroy()
        restoreAutoDnd()

        perAppStore.clearSession()
        videoStore.clearSession()

        currentPackage = null
        currentMode = SidebarMode.MODE_NONE
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
        runCatching { unregisterReceiver(screenOnReceiver) }
        stopSession()
        sidebar.dispose()
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
        const val ACTION_MODE_CHANGE = "session_mode_change"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_MODE = "mode"

        // NB: no isServiceRunning() guard on start. It used to be safe when only games
        // opened a session, but stopSelf() is asynchronous — with a session per app
        // switch, the next start would routinely arrive while the service was still
        // shutting down, see itself as "already running" and drop the intent, losing
        // the sidebar until the user switched apps again. startSession() already
        // dedupes on (package, mode), so re-sending is free.
        fun start(context: Context, app: String, mode: Int) {
            Intent(context, SessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PACKAGE_NAME, app)
                putExtra(EXTRA_MODE, mode)
            }.let {
                context.startServiceAsUser(it, UserHandle.CURRENT)
            }
        }

        fun changeMode(context: Context, app: String, mode: Int) {
            if (!context.isServiceRunning(SessionService::class.java)) return
            Intent(context, SessionService::class.java).apply {
                action = ACTION_MODE_CHANGE
                putExtra(EXTRA_PACKAGE_NAME, app)
                putExtra(EXTRA_MODE, mode)
            }.let {
                context.startServiceAsUser(it, UserHandle.CURRENT)
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
