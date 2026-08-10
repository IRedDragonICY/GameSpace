/* --- gamespace/gamebar/video/DolbyController.kt --- */
package com.ireddragonicy.gamespace.gamebar.video

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the global Dolby state, shared by every surface
 * that touches it (game-panel tile, SOUND tuner tab, video toolbox) and by the
 * non-Compose session auto-apply path.
 *
 * Held as a Compose [mutableStateOf] on purpose: reading [state] inside a
 * composition tracks it, so a write from ANY owner — us, or LunarisDolby
 * publishing Settings.Global — recomposes all readers in lockstep. This is what
 * makes a *global* control safe to show in *several* places without the stale
 * mirror bug the video toolbox currently has.
 *
 * Dolby is a single global DAP session, so there is deliberately no per-game
 * state here — the per-game *automation* (apply a profile when a game launches)
 * lives in PerAppSettingStore + SessionService and only ever pokes the global
 * value through [setProfile].
 */
@Singleton
class DolbyController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Snapshot(val available: Boolean, val enabled: Boolean, val profile: Int)

    private val _state = mutableStateOf(
        Snapshot(
            available = DolbyBridge.isAvailable(context),
            enabled = DolbyBridge.isEnabled(context),
            profile = DolbyBridge.currentProfile(context),
        )
    )

    /** Read this in a composition to subscribe; read [isAvailable] elsewhere. */
    val state: Snapshot get() = _state.value
    val isAvailable: Boolean get() = _state.value.available

    init {
        // Only worth observing when LunarisDolby is actually installed
        if (_state.value.available) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
            }
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(DolbyBridge.SETTINGS_DAP_ACTIVE), false, observer)
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(DolbyBridge.SETTINGS_PROFILE_ACTIVE), false, observer)
        }
    }

    /** Reconcile from Settings.Global (observer + manual refresh). */
    fun refresh() {
        _state.value = _state.value.copy(
            enabled = DolbyBridge.isEnabled(context),
            profile = DolbyBridge.currentProfile(context),
        )
    }

    fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)   // optimistic
        DolbyBridge.setEnabled(context, enabled)
    }

    /** Selecting a profile also turns the DAP on, LunarisDolby-side. */
    fun setProfile(profile: Int) {
        _state.value = _state.value.copy(enabled = true, profile = profile)
        DolbyBridge.setProfile(context, profile)
    }

    /** Live global profile for non-Compose callers (session auto-apply). */
    fun currentProfile(): Int = DolbyBridge.currentProfile(context)
}
