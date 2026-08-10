package com.ireddragonicy.gamespace.gamebar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * Triggers the platform (SystemUI) screenshot pipeline from the overlay panel.
 *
 * The old `android.intent.action.SCREENSHOT` broadcast has no receiver on
 * modern AOSP — screenshots now go through SystemUI's TakeScreenshotService
 * with a `ScreenshotRequest` parcelable payload, and the caller needs the
 * `com.android.systemui.permission.SELF` permission (system app).
 *
 * Both the service and the request class are @hide, so the request is built by
 * reflection and the message contract is replicated from AOSP
 * `ScreenshotHelper`:
 *   - msg.what = 0, msg.obj = ScreenshotRequest (type=TAKE_SCREENSHOT_FULLSCREEN=1, source=SCREENSHOT_GLOBAL_ACTIONS=0)
 *   - reply msg 1 = SCREENSHOT_MSG_URI (saved uri), msg 2 = SCREENSHOT_MSG_PROCESS_COMPLETE
 */
object ScreenshotUtil {

    private const val SCREENSHOT_MSG_URI = 1
    private const val SCREENSHOT_MSG_PROCESS_COMPLETE = 2
    private const val TYPE_FULLSCREEN = 1
    private const val SOURCE_GLOBAL_ACTIONS = 0

    fun takeScreenshot(context: Context) {
        val request = buildRequest() ?: return

        val component = ComponentName(
            "com.android.systemui",
            "com.android.systemui.screenshot.TakeScreenshotService"
        )

        val timeoutHandler = Handler(Looper.getMainLooper())
        var unbound = false
        var timeoutRunnable: Runnable? = null

        fun cleanup() {
            if (!unbound) {
                unbound = true
                timeoutRunnable?.let(timeoutHandler::removeCallbacks)
                runCatching { context.unbindService(connection) }
            }
        }

        timeoutRunnable = Runnable {
            Log.w("ScreenshotUtil", "screenshot service timed out")
            cleanup()
        }

        val replyHandler = Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                SCREENSHOT_MSG_URI -> {
                    Log.d("ScreenshotUtil", "screenshot saved: ${msg.obj}")
                    cleanup()
                }
                SCREENSHOT_MSG_PROCESS_COMPLETE -> cleanup()
            }
            true
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) return
                try {
                    val msg = Message.obtain(null, 0, request)
                    msg.replyTo = Messenger(replyHandler)
                    Messenger(service).send(msg)
                } catch (e: Exception) {
                    Log.w("ScreenshotUtil", "failed to send screenshot request", e)
                    cleanup()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = cleanup()
        }

        runCatching {
            context.bindService(
                Intent().setComponent(component),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.onFailure {
            Log.w("ScreenshotUtil", "bind to screenshot service failed", it)
            timeoutRunnable?.let(timeoutHandler::removeCallbacks)
        }
    }

    private fun buildRequest(): Any? = runCatching {
        val builderClass =
            Class.forName("com.android.internal.util.ScreenshotRequest\$Builder")
        val builder = builderClass
            .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(TYPE_FULLSCREEN, SOURCE_GLOBAL_ACTIONS)
        builderClass.getMethod("build").invoke(builder)
    }.onFailure {
        Log.w("ScreenshotUtil", "buildRequest failed", it)
    }.getOrNull()
}