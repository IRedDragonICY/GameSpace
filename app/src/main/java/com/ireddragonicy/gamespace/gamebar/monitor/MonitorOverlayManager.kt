/* --- gamespace/gamebar/monitor/MonitorOverlayManager.kt --- */
package com.ireddragonicy.gamespace.gamebar.monitor

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ireddragonicy.gamespace.gamebar.fps.FpsInteractor

class MonitorOverlayManager(
    private val context: Context,
    private val wm: WindowManager,
    private val settings: MonitorSettings,
    private val telemetry: MonitorTelemetry,
    private val fpsInteractor: FpsInteractor
) {
    private val views = mutableMapOf<String, ComposeView>()
    private var overlayStateOwner: OverlayStateOwner? = null

    fun start() {
        if (overlayStateOwner != null) return
        telemetry.start()
        fpsInteractor.start()
        
        val owner = OverlayStateOwner().apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        overlayStateOwner = owner

        updateWindowParams()
    }

    fun updateWindowParams() {
        val owner = overlayStateOwner ?: return
        val isPinned = settings.isPinned

        updateMonitor(owner, isPinned, "classical", settings.isClassicalMonitorEnabled) { onDrag, onEnd ->
            DraggableWidget(isPinned, onDrag, onEnd) { ClassicalMonitorWidget(telemetry, fpsInteractor, settings) }
        }
        updateMonitor(owner, isPinned, "mini", settings.isMiniMonitorEnabled) { onDrag, onEnd ->
            DraggableWidget(isPinned, onDrag, onEnd) { MiniMonitorWidget(telemetry, settings) }
        }
        updateMonitor(owner, isPinned, "processes", settings.isProcessesMonitorEnabled) { onDrag, onEnd ->
            DraggableWidget(isPinned, onDrag, onEnd) { ProcessesMonitorWidget(telemetry, settings) }
        }
        updateMonitor(owner, isPinned, "temp", settings.isTempMonitorEnabled) { onDrag, onEnd ->
            DraggableWidget(isPinned, onDrag, onEnd) { TempMonitorWidget(telemetry, settings) }
        }
    }

    private fun updateMonitor(
        owner: OverlayStateOwner,
        isPinned: Boolean,
        key: String,
        enabled: Boolean,
        content: @Composable (updateDrag: (Float, Float) -> Unit, endDrag: () -> Unit) -> Unit
    ) {
        if (enabled) {
            var view = views[key]
            if (view == null) {
                view = ComposeView(context).apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                }
                views[key] = view
            }

            val offset = settings.getMonitorPosition(key, 100f, 100f)
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            
            if (isPinned) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                x = offset.x.toInt()
                y = offset.y.toInt()
            }

            view.setContent {
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                MaterialExpressiveTheme(
                    colorScheme = dynamicDarkColorScheme(context),
                    motionScheme = MotionScheme.expressive(),
                ) {
                    content(
                        { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            wm.updateViewLayout(view, params)
                        },
                        {
                            settings.saveMonitorPosition(key, androidx.compose.ui.geometry.Offset(params.x.toFloat(), params.y.toFloat()))
                        }
                    )
                }
            }

            if (view.isAttachedToWindow) {
                wm.updateViewLayout(view, params)
            } else {
                wm.addView(view, params)
            }
        } else {
            views[key]?.let {
                if (it.isAttachedToWindow) wm.removeViewImmediate(it)
                views.remove(key)
            }
        }
    }

    fun stop() {
        overlayStateOwner?.apply {
            handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        overlayStateOwner = null
        
        telemetry.stop()
        fpsInteractor.dispose()
        for (view in views.values) {
            if (view.isAttachedToWindow) wm.removeViewImmediate(view)
        }
        views.clear()
    }
}

/**
 * Class khusus untuk menyediakan environment yang dibutuhkan Compose 
 * saat dipasang langsung via WindowManager (di luar Activity).
 */
class OverlayStateOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
    }

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
