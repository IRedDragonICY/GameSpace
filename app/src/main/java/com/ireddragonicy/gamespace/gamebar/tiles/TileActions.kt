package com.ireddragonicy.gamespace.gamebar.tiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.gamebar.TunerTab
import com.ireddragonicy.gamespace.gamebar.video.DolbyController
import com.ireddragonicy.gamespace.utils.rememberDrawablePainter
import kotlinx.coroutines.delay

/**
 * Resolves the visible painter for a tile: live drawable (TileService tiles)
 * when present, otherwise the static resource drawable.
 */
@Composable
fun tilePainter(tile: TileAction): Painter {
    val drawable = tile.iconDrawable
    return if (drawable != null) rememberDrawablePainter(drawable)
    else painterResource(tile.icon)
}

/** Base tile interface for overlay quick toggles and tool grid items. */
sealed interface TileAction {
    val id: String
    val label: String
    val icon: Int

    /** Live drawable icon (TileService tiles); when non-null it wins over [icon]. */
    val iconDrawable: android.graphics.drawable.Drawable? get() = null

    /** Grouping label shown in the tile pool, e.g. "System" or an app name. */
    val group: String get() = ""

    @Composable fun observeEnabled(): State<Boolean>
    fun toggle() {}
    fun onLongClick() {}
}

// B1: PlatformTile removed — it was backed by PlatformTileSync, whose
// getTileState() always returned null and whose onToggle was never wired,
// so the tiles were dead buttons. Screenshot/cast live on as FixedActionTile.

/** Toggleable tile backed by a boolean state flow/mutableState. */
class ToggleableTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    val state: State<Boolean>,
    val setter: (Boolean) -> Unit,
    val onLongClickAction: (() -> Unit)? = null,
) : TileAction {
    @Composable
    override fun observeEnabled(): State<Boolean> = state

    override fun toggle() { setter(!state.value) }

    override fun onLongClick() {
        onLongClickAction?.invoke()
    }
}

/** Screen recording tile supporting custom short click and long click actions. */
class ScreenRecordTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    val state: State<Boolean>,
    val onToggle: () -> Unit,
    val onLongClickAction: () -> Unit,
) : TileAction {
    @Composable
    override fun observeEnabled(): State<Boolean> = state
    override fun toggle() { onToggle() }
    override fun onLongClick() { onLongClickAction() }
}

/** One-shot action tile (e.g. Memory Boost, Settings, Screenshot, Cast). */
class FixedActionTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    val action: () -> Unit,
) : TileAction {
    private val alwaysOff = mutableStateOf(false)

    @Composable
    override fun observeEnabled(): State<Boolean> = alwaysOff

    override fun toggle() { action() }
}

/** AFME Frame Generation tile. */
class AfmeTile(
    override val id: String,
    override val icon: Int,
    val baseLabel: String,
    val store: PerAppSettingStore,
    val currentPackage: () -> String?,
    val requestTuner: (TunerTab) -> Unit,
) : TileAction {
    override val label: String
        get() {
            val pkg = currentPackage() ?: return baseLabel
            if (!store.afmeEnabled(pkg)) return baseLabel
            return "AFME (${store.afmeMultiplier(pkg)}x)"
        }

    @Composable
    override fun observeEnabled(): State<Boolean> {
        val pkg = currentPackage()
        val rev = PerAppSettingStore.afmeRevision
        return androidx.compose.runtime.remember(pkg, rev) {
            mutableStateOf(pkg?.let { store.afmeEnabled(it) } ?: false)
        }
    }

    override fun toggle() {
        val pkg = currentPackage() ?: return
        store.setAfmeEnabled(pkg, !store.afmeEnabled(pkg))
    }

    override fun onLongClick() { requestTuner(TunerTab.FRAME) }
}

/** Dolby Atmos tile. */
class DolbyTile(
    override val id: String,
    override val icon: Int,
    val controller: DolbyController,
    val currentPackage: () -> String?,
    val requestTuner: (TunerTab) -> Unit,
) : TileAction {
    override val label: String get() = "Dolby"

    @Composable
    override fun observeEnabled(): State<Boolean> = derivedStateOf { controller.state.enabled }

    override fun toggle() { controller.setEnabled(!controller.state.enabled) }
    override fun onLongClick() { requestTuner(TunerTab.SOUND) }
}

/**
 * Live system-state toggle backed by a real platform switch (Wi-Fi / Mobile Data).
 * State is read directly from the platform and refreshed once a second while the
 * chip is composed, so it tracks external changes (QS panel, settings, etc.).
 */
abstract class PollingToggleTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
) : TileAction {
    private val state = mutableStateOf(readState())

    protected abstract fun readState(): Boolean
    protected abstract fun writeState(enabled: Boolean)

    @Composable
    override fun observeEnabled(): State<Boolean> {
        LaunchedEffect(Unit) {
            while (true) {
                state.value = readState()
                delay(1000)
            }
        }
        return state
    }

    override fun toggle() {
        writeState(!readState())
        state.value = readState()
    }
}

/** Wi-Fi quick toggle — flips WifiManager.isWifiEnabled directly. */
class WifiTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val wifiManager: android.net.wifi.WifiManager,
) : PollingToggleTile(id, label, icon) {
    override fun readState(): Boolean =
        runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)

    override fun writeState(enabled: Boolean) {
        runCatching { wifiManager.setWifiEnabled(enabled) }
    }
}

/** Mobile Data quick toggle — flips TelephonyManager.setDataEnabled directly. */
class MobileDataTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val telephonyManager: android.telephony.TelephonyManager,
) : PollingToggleTile(id, label, icon) {
    override fun readState(): Boolean =
        runCatching { telephonyManager.isDataEnabled }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun writeState(enabled: Boolean) {
        runCatching { telephonyManager.setDataEnabled(enabled) }
    }
}