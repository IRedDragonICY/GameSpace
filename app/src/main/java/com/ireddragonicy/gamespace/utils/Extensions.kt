/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2022 crDroid Android Project
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
package com.ireddragonicy.gamespace.utils

import android.app.ActivityManager
import android.content.Context
import android.content.res.Resources.getSystem
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.EntryPoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val Context.statusbarHeight
    get() =
        resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) } ?: 24.dp

val Int.dp
    get() = (this * getSystem().displayMetrics.density).toInt()

fun WindowManager.isPortrait() =
    maximumWindowMetrics.bounds.width() < maximumWindowMetrics.bounds.height()

inline fun <reified T : Any> Context.entryPointOf(): T =
    EntryPoints.get(applicationContext, T::class.java)

@Suppress("DEPRECATION") // Deprecated for third party services.
fun Context.isServiceRunning(serviceClass: Class<*>): Boolean =
    isServiceRunning(serviceClass.name)

@Suppress("DEPRECATION")
fun Context.isServiceRunning(className: String): Boolean =
    (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .getRunningServices(Integer.MAX_VALUE)
        .any { it.service.className == className }

inline fun View.repeatWhenAttached(
    crossinline block: suspend CoroutineScope.() -> Unit
) {
    if (isAttachedToWindow) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            block()
        }
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                v.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    block()
                }
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }
}

class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    init {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
}

fun ComposeView.setupForOverlayWindow(
    owner: OverlayLifecycleOwner = OverlayLifecycleOwner()
) {
    setViewTreeLifecycleOwner(owner)
    setViewTreeSavedStateRegistryOwner(owner)
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
}
