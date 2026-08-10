/*
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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.ireddragonicy.gamespace.gamebar

import android.annotation.SuppressLint
import android.app.ActivityTaskManager
import android.app.ActivityManager
import android.content.*
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.util.Log
import android.os.UserHandle
import android.provider.Settings
import android.view.*
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.*
import com.ireddragonicy.gamespace.gamebar.SidebarMode
import com.ireddragonicy.gamespace.utils.repeatWhenAttached
import com.ireddragonicy.gamespace.utils.setupForOverlayWindow
import com.ireddragonicy.gamespace.BuildFlags
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.gamebar.brightness.*
import com.ireddragonicy.gamespace.gamebar.fps.*
import com.ireddragonicy.gamespace.gamebar.mapper.MapperController
import com.ireddragonicy.gamespace.gamebar.tiles.*
import com.ireddragonicy.gamespace.utils.*
import java.math.RoundingMode
import java.text.DecimalFormat

/**
 * GameSidebar manages the floating game overlay consisting of:
 * - Floating game bar handle (drag to open panel)
 * - Main game panel (toggles, stats, app launcher)
 * - Floating FPS monitor
 */
class GameSidebar(
    private val context: Context,
    private val wm: WindowManager,
    private val handler: Handler,
    private val appSettings: AppSettings,
    private val screenUtils: ScreenUtils,
    private val danmakuService: DanmakuService,
    private val brightnessInteractor: BrightnessInteractor,
    private val fpsInteractor: FpsInteractor,
    private val settings: SystemSettings,
    private val tileRepository: TileRepository,
    private val mapperController: MapperController,
) {
    private val quickStart = QuickStartProvider(context, appSettings, settings)

    // ──────────────────────────────────────────────────────────────────
    // Layout params
    // ──────────────────────────────────────────────────────────────────

    private val gameBarLayoutParam = createGameBarLayoutParam()
    private val panelLayoutParam = createPanelLayoutParam()

    // ──────────────────────────────────────────────────────────────────
    // Screen metrics
    // ──────────────────────────────────────────────────────────────────

    private var halfWidth = 0
    private var safeHeight = 0
    private var safeArea = 0

    // ──────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────

    private var shouldClose = false
    private var panelShowing = false

    private lateinit var gameBarView: ComposeView
    private var panelView: View? = null

    private val firstPaint = Runnable { initActions() }

    private var circleOnLeft = false
    private val dockedOnLeftState = mutableStateOf(false)

    private val noOpBackDispatcherOwner = object : OnBackPressedDispatcherOwner {
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
        private val reg = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = reg
    }

    private val panelDismissing = mutableStateOf(false)

    private val showFpsState = mutableStateOf(false)
    private val fpsTextState = mutableStateOf("")
    private val isIdleState = mutableStateOf(true)

    private val taskManager by lazy { ActivityTaskManager.getService() }
    private val sidebarStyleStore by lazy {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint::class.java
        ).sidebarStyleStore()
    }



    // ──────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────

    fun onCreate() {
        // Wire TileRepository callbacks for gaming tiles
        tileRepository.onGestureLockChanged = { locked -> setGestureLock(locked) }
        tileRepository.onMapControls = { handler.post { enterMapperEdit() } }
        tileRepository.onShowFpsChanged = { enabled ->
            handler.post {
                showFpsState.value = enabled
                if (enabled) fpsInteractor.start() else fpsInteractor.dispose()
            }
        }

        gameBarView = ComposeView(context).apply {
            setupForOverlayWindow()
            repeatWhenAttached {
                setContent {
                        CompositionLocalProvider(
                            LocalOnBackPressedDispatcherOwner provides noOpBackDispatcherOwner,
                        ) {
                            MaterialExpressiveTheme(
                                colorScheme = dynamicDarkColorScheme(context),
                                motionScheme = MotionScheme.expressive(),
                            ) {
                                val barStyleStore = remember {
                                    dagger.hilt.android.EntryPointAccessors.fromApplication(
                                        context.applicationContext,
                                        com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint::class.java
                                    ).sidebarStyleStore()
                                }
                                val barStyle = barStyleStore.current
                                val fpsInteractorRef = fpsInteractor
                                LaunchedEffect(Unit) {
                                    fpsInteractorRef.realFpsHistory.collect { history ->
                                        val fps = history.lastOrNull() ?: return@collect
                                        fpsTextState.value = java.text.DecimalFormat("#")
                                            .apply { roundingMode = java.math.RoundingMode.HALF_EVEN }
                                            .format(fps)
                                    }
                                }
                                GameBarView(
                                    style = barStyle,
                                    showFps = showFpsState.value,
                                    fpsText = fpsTextState.value,
                                    isIdle = isIdleState.value,
                                    idleAlpha = (barStyle.idleAlpha / 100f).coerceAtLeast(0.05f),
                                    dockedOnLeft = dockedOnLeftState.value,
                                    accent = rememberPanelAccent(
                                        appSettings.panelColorMode,
                                        appSettings.panelCustomColor,
                                    ),
                                    onShowPanel = { handler.post { showPanel() } },
                                    onDragStart = {
                                        val loc = IntArray(2)
                                        gameBarView.getLocationOnScreen(loc)
                                        gameBarLayoutParam.gravity = Gravity.TOP or Gravity.START
                                        gameBarLayoutParam.x = loc[0]
                                        gameBarLayoutParam.y = loc[1]
                                        runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
                                        Pair(loc[0], loc[1])
                                    },
                                    onDragUpdate = { x, y ->
                                        gameBarLayoutParam.x = x
                                        gameBarLayoutParam.y = y
                                        runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
                                    },
                                    onDragEnd = { x, y ->
                                        circleOnLeft = x < halfWidth
                                        dockedOnLeftState.value = circleOnLeft
                                        appSettings.x = if (circleOnLeft) -1 else 1
                                        appSettings.y = y
                                        dockGameBar()
                                        runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
                                        scheduleIdle()
                                    },
                                )
                            }
                        }
                    }
            }
            // Exclude game bar area from system gesture zones (back swipe)
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.setSystemGestureExclusionRects(
                    listOf(Rect(0, 0, v.width, v.height))
                )
            }
        }

        updateScreenMetrics()
        danmakuService.init()
    }

    fun onSessionStart(packageName: String, mode: Int) {
        // Must be cleared before initActions() runs: onSessionEnd() leaves it set, and
        // with a session per app switch this object can outlive a single session, so a
        // stale `true` would make the pill invisible for the rest of the process.
        shouldClose = false
        quickStart.prefetch(mode)

        if (mode == SidebarMode.MODE_GAME) {
            if (BuildFlags.MAPPER_ENABLED) {
                mapperController.onGameStart(packageName)
            }
            if (appSettings.showFps) {
                fpsInteractor.start()
            }
        }
        handler.post {
            if (!::gameBarView.isInitialized) return@post
            try {
                dockGameBar()
                if (gameBarView.isAttachedToWindow) {
                    wm.updateViewLayout(gameBarView, gameBarLayoutParam)
                } else {
                    wm.addView(gameBarView, gameBarLayoutParam)
                }
            } catch (e: Exception) {
                Log.w("GameSidebar", "Window attachment warning", e)
            }
            initActions()
        }
    }

    /**
     * The foreground app changed flavour (playback started/stopped) without losing focus.
     * [TileRepository.sidebarMode] is already updated, so an open panel recomposes into
     * the other layout without manually refreshing the container.
     */
    fun onSessionModeChanged(mode: Int) {
        quickStart.prefetch(mode)
        handler.post {
            // A tile sheet belonging to the outgoing panel would be orphaned by the swap.
            tileRepository.showPingDetails.value = false
            tileRepository.showPerfTuner.value = false
            tileRepository.touchTesterExpanded.value = false
        }
    }

    fun onSessionEnd() {
        dispose()
        mapperController.onGameLeave()
        fpsInteractor.dispose()
        shouldClose = true
        setGestureLock(false)
        handler.removeCallbacksAndMessages(null)
        forceRemovePanel()
        runCatching { wm.removeViewImmediate(gameBarView) }
    }

    fun dispose() {
        quickStart.dispose()
    }

    private fun recordGame() {
        tileRepository.toggleScreenRecord(targetGameOnly = true)
    }

    fun setGestureLock(value: Boolean) {
        tileRepository.gestureLockState.value = value
        Settings.Secure.putIntForUser(
            context.contentResolver,
            "gamespace_gesture_lock",
            if (value) 1 else 0,
            UserHandle.USER_CURRENT
        )
    }

    private fun toggleGameRecording() {
        tileRepository.toggleScreenRecord(targetGameOnly = true)
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        mapperController.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        forceRemovePanel()
        if (gameBarView.visibility != View.VISIBLE) {
            handler.removeCallbacks(firstPaint)
            handler.postDelayed({ firstPaint.run() }, 100)
        } else {
            dockGameBar()
            runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
        }
        danmakuService.updateConfiguration(newConfig)
    }

    // ──────────────────────────────────────────────────────────────────
    // Panel lifecycle
    // ──────────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (panelShowing) return
        panelShowing = true
        panelDismissing.value = false
        tileRepository.touchTesterExpanded.value = false
        handler.removeCallbacks(idleRunnable)
        isIdleState.value = false

        tileRepository.refreshScreenRecordState()
        tileRepository.republishAfmeForCurrentGame()
        // Free slots follow the user's sort — re-rolled every time the panel
        // opens (toggleable in settings). Pinned slots never move.
        if (appSettings.dockRefreshOnOpen) {
            quickStart.refresh(tileRepository.sidebarMode)
        }
        fpsInteractor.start()
        brightnessInteractor.start()

        val pv = createPanelView()
        panelView = pv

        // Dismiss panel when user touches outside
        pv.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                handler.post { requestDismissPanel() }
            }
            false
        }

        // Panel kini bersifat layar penuh (MATCH_PARENT), posisinya digeser dari dalam Compose
        panelLayoutParam.gravity = Gravity.CENTER
        panelLayoutParam.x = 0
        panelLayoutParam.y = 0

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            Process.setThreadGroupAndCpuset(Process.myTid(), Process.THREAD_GROUP_SYSTEM)
            Process.setProcessGroup(Process.myPid(), Process.THREAD_GROUP_SYSTEM)
            wm.addView(pv, panelLayoutParam)
            gameBarView.visibility = View.GONE
        } catch (_: Exception) {
            brightnessInteractor.dispose()
            fpsInteractor.dispose()
            panelShowing = false
        }
    }

    private fun requestDismissPanel() {
        panelDismissing.value = true
    }

    private fun removePanelAndRestoreCircle() {
        if (!panelShowing) return
        panelShowing = false

        brightnessInteractor.dispose()
        fpsInteractor.dispose()

        panelView?.let { pv ->
            runCatching { wm.removeViewImmediate(pv) }
            panelView = null
        }
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            Process.setThreadGroupAndCpuset(Process.myTid(), Process.THREAD_GROUP_BACKGROUND)
            Process.setProcessGroup(Process.myPid(), Process.THREAD_GROUP_BACKGROUND)
        }

        if (!shouldClose) {
            gameBarView.visibility = View.VISIBLE
            scheduleIdle()
        }
    }

    private fun forceRemovePanel() {
        if (!panelShowing) return
        panelShowing = false
        panelDismissing.value = true

        brightnessInteractor.dispose()
        fpsInteractor.dispose()

        panelView?.let { pv ->
            runCatching { wm.removeViewImmediate(pv) }
            panelView = null
        }
        if (::gameBarView.isInitialized) gameBarView.visibility = View.VISIBLE
    }

    fun bringToFront() {
        panelView?.let { pv ->
            if (pv.isAttachedToWindow) {
                runCatching { wm.updateViewLayout(pv, panelLayoutParam) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Panel view creation
    // ──────────────────────────────────────────────────────────────────

    private fun createPanelView(): ComposeView {
        android.util.Log.w("GameSidebar", "createPanelView: creating")
        return ComposeView(context).apply {
            setupForOverlayWindow()
            repeatWhenAttached {
                android.util.Log.w("GameSidebar", "createPanelView: repeatWhenAttached fired")
                setContent {
                    CompositionLocalProvider(
                        LocalOnBackPressedDispatcherOwner provides noOpBackDispatcherOwner,
                    ) {
                        MaterialExpressiveTheme(
                            colorScheme = dynamicDarkColorScheme(context),
                            motionScheme = MotionScheme.expressive(),
                        ) {
                            PanelOverlay()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PanelOverlay() {
        android.util.Log.w("GameSidebar", "PanelOverlay: composing")
        val mode = tileRepository.sidebarMode
        val appsFlow = remember(mode) { quickStart.listFor(mode) }
        val dockItems by appsFlow.collectAsState()
        val dismissing by panelDismissing
        val onLeft = circleOnLeft

        // Observe keyboard focus requests from InlineAppPicker search bar
        val needsKeyboard by tileRepository.keyboardFocusRequested
        LaunchedEffect(needsKeyboard) {
            setKeyboardFocusable(needsKeyboard)
        }
        // Reset keyboard focus when panel dismisses
        DisposableEffect(Unit) {
            onDispose {
                tileRepository.keyboardFocusRequested.value = false
                setKeyboardFocusable(false)
            }
        }
        
        // Reactive state for Bubble Mode toggle
        var isBubbleMode by remember { mutableStateOf(appSettings.launchAppInBubble) }

        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }

        val show = entered && !dismissing
        val slideSign = if (onLeft) -1f else 1f

        val slideOffset by animateFloatAsState(
            targetValue = if (show) 0f else slideSign * 400f,
            animationSpec = tween(
                durationMillis = if (show) 350 else 250,
                easing = if (show) FastOutSlowInEasing else FastOutLinearInEasing,
            ),
            label = "panel_slide",
            finishedListener = {
                if (dismissing) handler.post { removePanelAndRestoreCircle() }
            },
        )

        val panelAlpha by animateFloatAsState(
            targetValue = if (show) 1f else 0f,
            animationSpec = tween(if (show) 280 else 180),
            label = "panel_alpha",
        )

        val isBlurEnabled by tileRepository.isBlurEnabled
        val blurRadius by tileRepository.blurRadius


        val density = LocalDensity.current
        val paddingPx = with(density) { 8.dp.toPx() }.toInt()
        LaunchedEffect(slideOffset, panelAlpha) {
            panelView?.let { pv ->
                panelLayoutParam.x = if (onLeft) slideOffset.toInt() + paddingPx else -(slideOffset.toInt() + paddingPx)
                panelLayoutParam.alpha = panelAlpha
                runCatching { wm.updateViewLayout(pv, panelLayoutParam) }
            }
        }

        val panelScale by animateFloatAsState(
            targetValue = if (show) 1f else 0.88f,
            animationSpec = tween(
                durationMillis = if (show) 350 else 220,
                easing = if (show) FastOutSlowInEasing else FastOutLinearInEasing,
            ),
            label = "panel_scale",
        )

        val screenHeightDp = with(density) { safeHeight.toDp() }
        val panelMaxHeight = screenHeightDp.coerceIn(240.dp, 520.dp)

        // Panel design language — angular dark glass + neon chrome (see PanelWidgets.kt)
        val panelAccent = rememberPanelAccent(
            tileRepository.panelColorMode.intValue,
            tileRepository.panelCustomColor.intValue
        )

        // GPU-OPTIMIZED PANEL CHROME  —  `chrome` lets the app strip opt out so it can
        // draw its OWN style-driven EdgeChrome (1:1 with the studio) instead of the
        // hardcoded panel chamfer. Blur + glass tint are always kept.
        @Composable
        fun GlassmorphismBox(
            modifier: Modifier = Modifier,
            chrome: Boolean = true,
            content: @Composable () -> Unit
        ) {
            val panelShape = remember { chamferShape() }

            // ── KUNCI FIX ────────────────────────────────────────────────────────
            // Read state in COMPOSITION scope (not inside AndroidView lambdas)
            // so Compose registers the observer, triggering recomposition when sliders move.
            val blurOn = isBlurEnabled
            val radius = blurRadius
            // ─────────────────────────────────────────────────────────────────────

            Box(
                // PERF KEY: clip BEFORE blur so the GPU never blurs outside the panel
                modifier = modifier.clip(panelShape)
            ) {
                // 1. HARDWARE ACCELERATED BLUR
                if (blurOn) {
                    AndroidView(
                        factory = { ctx ->
                            android.view.View(ctx).apply {
                                // PERF KEY 2: force this View onto a GPU hardware layer
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                                addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                                    override fun onViewAttachedToWindow(v: android.view.View) {
                                        try {
                                            v.viewRootImpl?.createBackgroundBlurDrawable()?.let { drawable ->
                                                drawable.setBlurRadius(radius)
                                                drawable.setCornerRadius(with(density) { 12.dp.toPx() })
                                                v.background = drawable
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("GameSidebar", "Blur failed", e)
                                        }
                                    }
                                    override fun onViewDetachedFromWindow(v: android.view.View) {}
                                })
                            }
                        },
                        update = { view ->
                            (view.background as? BackgroundBlurDrawable)?.setBlurRadius(radius)
                            view.invalidate()
                        },
                        // The blur view must only cover this box
                        modifier = Modifier.matchParentSize()
                    )
                }

                // 2. DEEP GLASS TINT — near-black gradient with a hint of accent
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(glassBrush(panelAccent))
                )

                // 3. PANEL CONTENT
                content()

                // 4. NEON CHROME — edge glow, energized top rail, corner blades
                if (chrome) {
                    PanelChromeOverlay(
                        accent = panelAccent,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
        }

        // LAYOUT UTAMA LAYAR
        Box(modifier = Modifier.fillMaxSize()) {
            // TAP-TO-DISMISS CATCHER
            //
            // Deliberately has NO background. This used to paint black at 40%
            // over the whole screen, which greyed out the game behind the panel
            // — and with the color filter running that is actively misleading,
            // because the grade you are tuning is judged against a dimmed image.
            // A transparent Box still receives pointer input, so dismissing on
            // an outside tap works exactly as before.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { requestDismissPanel() })
                    }
            )

            // CONTAINER ANIMASI PANEL & SIDEBAR
            Box(
                modifier = Modifier
                    .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .offset { IntOffset(slideOffset.toInt(), 0) }
                    .padding(if (onLeft) PaddingValues(start = 16.dp) else PaddingValues(end = 16.dp))
                    .graphicsLayer {
                        // KUNCI PERFORMA 3: Render seluruh grup ini sebagai satu layer GPU saat animasi
                        scaleX = panelScale
                        scaleY = panelScale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        alpha = panelAlpha
                    }
            ) {
                // Which panel sits next to the app strip is decided here and nowhere
                // else. PLAIN gets no panel at all — that is the whole point of the
                // third mode: an ordinary app gets the app strip and nothing more.
                val mode = tileRepository.sidebarMode

                val styleStore = remember {
                    dagger.hilt.android.EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint::class.java
                    ).sidebarStyleStore()
                }
                val style = styleStore.current

                @Composable
                fun AppStrip() {
                    // chrome = false: the strip renders its own style-driven EdgeChrome inside
                    // VerticalAppSidebar, exactly like the studio preview → guaranteed 1:1.
                    GlassmorphismBox(chrome = false) {
                        VerticalAppSidebar(
                            items = dockItems,
                            tileRepository = tileRepository,
                            style = style,
                            maxHeight = panelMaxHeight,
                            sidebarMode = mode,
                            isRecording = tileRepository.screenRecordingActive.value,
                            onRecordClick = { toggleGameRecording() },
                            onDockEdited = { pinned, hidden -> quickStart.editDock(mode, pinned, hidden) },
                            onAddApp = { pkg -> quickStart.pinApp(mode, pkg) },
                            isHiddenApp = { pkg -> quickStart.isHidden(mode, pkg) },
                            onKeyboardFocusChanged = { focused ->
                                tileRepository.keyboardFocusRequested.value = focused
                            },
                            isBubbleMode = isBubbleMode,
                            onToggleBubbleMode = {
                                isBubbleMode = it
                                appSettings.launchAppInBubble = it
                            }
                        )
                    }
                }

                @Composable
                fun MainPanel() {
                    when (mode) {
                        SidebarMode.MODE_GAME -> GlassmorphismBox {
                            GamePanelCard(
                                interactor = brightnessInteractor,
                                fpsInteractor = fpsInteractor,
                                tileRepository = tileRepository,
                                maxHeight = panelMaxHeight,
                            )
                        }
                        SidebarMode.MODE_VIDEO -> GlassmorphismBox {
                            VideoToolboxCard(
                                tileRepository = tileRepository,
                                maxHeight = panelMaxHeight,
                                onRecordClick = { toggleGameRecording() },
                            )
                        }
                        else -> Unit // MODE_PLAIN: app strip only
                    }
                }

                Row(
                    modifier = Modifier.height(panelMaxHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp), // Jarak antara sidebar & panel utama
                ) {
                    if (onLeft) {
                        AppStrip()
                        MainPanel()
                    } else {
                        MainPanel()
                        AppStrip()
                    }
                }
            }

        }
    }


    // ──────────────────────────────────────────────────────────────────
    // Pill state
    // ──────────────────────────────────────────────────────────────────

    private fun dockGameBar() {
        gameBarLayoutParam.gravity = Gravity.TOP or (if (circleOnLeft) Gravity.START else Gravity.END)
        gameBarLayoutParam.x = 0
        gameBarLayoutParam.y = gameBarLayoutParam.y.coerceIn(safeArea, safeHeight)
    }

    private val idleRunnable = Runnable {
        isIdleState.value = true
    }

    private fun enterMapperEdit() {
        forceRemovePanel()
        gameBarView.animate().alpha(0f).setDuration(150).withEndAction {
            gameBarView.visibility = View.GONE
        }.start()
        mapperController.onEditStarted = null
        mapperController.onEditFinished = {
            handler.post {
                gameBarView.visibility = View.VISIBLE
                gameBarView.animate().alpha(1f).setDuration(200).start()
                scheduleIdle()
            }
        }
        mapperController.enterEditMode()
    }

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        isIdleState.value = false
        val timeoutMs = (sidebarStyleStore.current.handleIdleTimeoutSec * 1000L).coerceIn(1000L, 10000L)
        handler.postDelayed(idleRunnable, timeoutMs)
    }

    // ──────────────────────────────────────────────────────────────────
    // Quick-start app list
    // ──────────────────────────────────────────────────────────────────





    // ──────────────────────────────────────────────────────────────────
    // Screen metrics
    // ──────────────────────────────────────────────────────────────────

    private fun updateScreenMetrics() {
        val bounds = wm.maximumWindowMetrics.bounds
        halfWidth = bounds.width() / 2
        safeArea = context.statusbarHeight + (4 * context.resources.displayMetrics.density).toInt()
        safeHeight = bounds.height() - safeArea
    }

    private fun initActions() {
        if (shouldClose) return
        gameBarView.visibility = View.VISIBLE
        gameBarView.animate().alpha(1f).setDuration(300).start()
        circleOnLeft = appSettings.x < 0
        dockedOnLeftState.value = circleOnLeft
        gameBarLayoutParam.y = appSettings.y
        dockGameBar()
        runCatching { wm.updateViewLayout(gameBarView, gameBarLayoutParam) }
        showFpsState.value = appSettings.showFps
        setGestureLock(appSettings.lockGesture)
        scheduleIdle()
    }

    // ──────────────────────────────────────────────────────────────────
    // Layout param factories
    // ──────────────────────────────────────────────────────────────────

    private fun createGameBarLayoutParam() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        preferMinimalPostProcessing = true
        gravity = Gravity.TOP or Gravity.END
    }

    private fun createPanelLayoutParam() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, // <-- UBAH KE MATCH_PARENT UNTUK BACKGROUND BLUR
        WindowManager.LayoutParams.MATCH_PARENT, // <-- UBAH KE MATCH_PARENT UNTUK BACKGROUND BLUR
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        preferMinimalPostProcessing = true
        @Suppress("DEPRECATION")
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        gravity = Gravity.CENTER // Posisi diatur secara dinamis oleh Box di Compose
    }

    /**
     * Dynamically toggle FLAG_NOT_FOCUSABLE on the panel overlay.
     * Called when search bar needs keyboard (focusable=true) or
     * when search is dismissed (focusable=false).
     */
    private fun setKeyboardFocusable(focusable: Boolean) {
        val pv = panelView ?: return
        if (focusable) {
            panelLayoutParam.flags = panelLayoutParam.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            panelLayoutParam.flags = panelLayoutParam.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(pv, panelLayoutParam) }
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 3000L
    }
}
