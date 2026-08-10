package com.ireddragonicy.gamespace.gamebar.tiles

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRouter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay

/**
 * ROM-agnostic system toggles (bluetooth, flashlight, DND, ...).
 *
 * Everything here is implemented with public framework APIs or AOSP-standard
 * hidden APIs (reflection), so it works identically on AOSP / LineageOS /
 * HyperOS / any other ROM — no platform service dependency.
 *
 * Tiles are only added to the pool when the device actually has the hardware
 * (e.g. NFC requires an NFC chip), and none of them are placed by default.
 */

/** Polling base for toggles backed by a Settings key / public getter. */
abstract class SystemToggleTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
) : TileAction {
    override val group: String get() = QS_GROUP_SYSTEM

    private val enabledState = mutableStateOf(false)

    protected abstract fun readEnabled(): Boolean
    protected open fun writeEnabled(enabled: Boolean) = Unit

    @Composable
    override fun observeEnabled(): State<Boolean> {
        enabledState.value = runCatching { readEnabled() }.getOrDefault(false)
        LaunchedEffect(Unit) {
            while (true) {
                enabledState.value = runCatching { readEnabled() }.getOrDefault(false)
                delay(1600)
            }
        }
        return enabledState
    }

    override fun toggle() {
        val target = !runCatching { readEnabled() }.getOrDefault(false)
        runCatching { writeEnabled(target) }
        enabledState.value = runCatching { readEnabled() }.getOrDefault(false)
    }
}

// ── Bluetooth ──────────────────────────────────────────────────────────────

/**
 * Bluetooth: tap opens a paired-devices window (like QS), long-press toggles
 * the adapter on/off directly.
 */
class BluetoothTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
    private val onOpenDevices: () -> Unit,
) : TileAction {
    override val group: String get() = QS_GROUP_SYSTEM

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val enabledState = mutableStateOf(
        runCatching { adapter?.isEnabled ?: false }.getOrDefault(false)
    )

    @Composable
    override fun observeEnabled(): State<Boolean> {
        LaunchedEffect(Unit) {
            while (true) {
                enabledState.value = runCatching { adapter?.isEnabled ?: false }.getOrDefault(false)
                delay(1600)
            }
        }
        return enabledState
    }

    override fun toggle() = onOpenDevices()

    override fun onLongClick() {
        runCatching {
            if (adapter?.isEnabled == true) adapter?.disable() else adapter?.enable()
        }
    }
}

// ── Flashlight ─────────────────────────────────────────────────────────────

/** Flashlight via CameraManager.setTorchMode; state via TorchCallback. */
class FlashlightTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : TileAction {
    override val group: String get() = QS_GROUP_SYSTEM

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(CameraManager::class.java)
    }

    val available: Boolean by lazy {
        runCatching {
            cameraManager.cameraIdList.any { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrDefault(false)
    }

    private val flashCameraId: String? by lazy {
        runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    private val enabledState = mutableStateOf(false)

    @Composable
    override fun observeEnabled(): State<Boolean> {
        DisposableEffect(Unit) {
            val callback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == flashCameraId) enabledState.value = enabled
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == flashCameraId) enabledState.value = false
                }
            }
            runCatching { cameraManager.registerTorchCallback(callback, null) }
            onDispose { runCatching { cameraManager.unregisterTorchCallback(callback) } }
        }
        return enabledState
    }

    override fun toggle() {
        flashCameraId?.let { cam ->
            runCatching { cameraManager.setTorchMode(cam, !enabledState.value) }
        }
    }
}

// ── Do Not Disturb ─────────────────────────────────────────────────────────

class DndTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    private val nm by lazy { context.getSystemService(android.app.NotificationManager::class.java) }

    override fun readEnabled(): Boolean {
        val filter = runCatching { nm.currentInterruptionFilter }
            .getOrDefault(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        return filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN &&
            filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
    }

    override fun writeEnabled(enabled: Boolean) {
        nm.setInterruptionFilter(
            if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }
}

// ── Airplane mode ──────────────────────────────────────────────────────────

class AirplaneTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    private val resolver get() = context.contentResolver

    override fun readEnabled(): Boolean =
        Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    override fun writeEnabled(enabled: Boolean) {
        Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, if (enabled) 1 else 0)
        context.sendBroadcast(
            Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", enabled)
        )
    }
}

// ── Auto-rotate / rotation lock ────────────────────────────────────────────

private const val ROTATION_ENABLED = 1
private const val ROTATION_LOCKED = 0

class AutoRotateTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    override fun readEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, ROTATION_ENABLED) == ROTATION_ENABLED

    override fun writeEnabled(enabled: Boolean) {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (enabled) ROTATION_ENABLED else ROTATION_LOCKED
        )
    }
}

class RotationLockTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    override fun readEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, ROTATION_ENABLED) == ROTATION_LOCKED

    override fun writeEnabled(enabled: Boolean) {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (enabled) ROTATION_LOCKED else ROTATION_ENABLED
        )
    }
}

// ── Location ───────────────────────────────────────────────────────────────

class LocationTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    override fun readEnabled(): Boolean =
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF
        ) != Settings.Secure.LOCATION_MODE_OFF

    override fun writeEnabled(enabled: Boolean) {
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.LOCATION_MODE,
            if (enabled) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
        )
    }
}

// ── NFC ────────────────────────────────────────────────────────────────────

class NfcTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    private val nfcAdapter by lazy {
        context.getSystemService(android.nfc.NfcAdapter::class.java)
    }

    override fun readEnabled(): Boolean =
        runCatching { nfcAdapter?.isEnabled ?: false }.getOrDefault(false)

    override fun writeEnabled(enabled: Boolean) {
        runCatching {
            val method = nfcAdapter?.javaClass?.getMethod(if (enabled) "enable" else "disable")
            method?.invoke(nfcAdapter)
        }
    }
}

// ── Wi-Fi hotspot ──────────────────────────────────────────────────────────

class HotspotTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    private val wifiManager by lazy {
        context.getSystemService(WifiManager::class.java)
    }

    private val setApMethod by lazy {
        runCatching {
            wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    private val isApMethod by lazy {
        runCatching { wifiManager.javaClass.getMethod("isWifiApEnabled") }.getOrNull()
    }

    override fun readEnabled(): Boolean =
        (isApMethod?.invoke(wifiManager) as? Boolean) ?: false

    override fun writeEnabled(enabled: Boolean) {
        runCatching { setApMethod?.invoke(wifiManager, null, enabled) }
    }
}

// ── Battery saver ──────────────────────────────────────────────────────────

class BatterySaverTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    private val powerManager by lazy { context.getSystemService(android.os.PowerManager::class.java) }

    private val setPowerSaveMethod by lazy {
        runCatching {
            powerManager.javaClass.getMethod("setPowerSaveMode", Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }

    override fun readEnabled(): Boolean =
        runCatching { powerManager.isPowerSaveMode }.getOrDefault(false)

    override fun writeEnabled(enabled: Boolean) {
        runCatching { setPowerSaveMethod?.invoke(powerManager, enabled) }
    }
}

// ── Dark mode ──────────────────────────────────────────────────────────────

class DarkModeTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    private val uiModeManager by lazy {
        context.getSystemService(android.app.UiModeManager::class.java)
    }

    override fun readEnabled(): Boolean =
        runCatching { uiModeManager.getNightMode() == android.app.UiModeManager.MODE_NIGHT_YES }
            .getOrDefault(false)

    override fun writeEnabled(enabled: Boolean) {
        runCatching {
            uiModeManager.setNightMode(
                if (enabled) android.app.UiModeManager.MODE_NIGHT_YES
                else android.app.UiModeManager.MODE_NIGHT_NO
            )
        }
    }
}

// ── Color inversion ────────────────────────────────────────────────────────

class ColorInversionTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    override fun readEnabled(): Boolean =
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
            0
        ) == 1

    override fun writeEnabled(enabled: Boolean) {
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
            if (enabled) 1 else 0
        )
    }
}

// ── Night light ────────────────────────────────────────────────────────────

class NightLightTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    override fun readEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, "night_display_activated", 0) == 1

    override fun writeEnabled(enabled: Boolean) {
        Settings.Secure.putInt(
            context.contentResolver,
            "night_display_activated",
            if (enabled) 1 else 0
        )
    }
}

// ── Data saver ─────────────────────────────────────────────────────────────

class DataSaverTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java) }

    private val setRestrictMethod by lazy {
        runCatching {
            cm.javaClass.getMethod("setRestrictBackground", Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }

    override fun readEnabled(): Boolean =
        cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    override fun writeEnabled(enabled: Boolean) {
        runCatching { setRestrictMethod?.invoke(cm, enabled) }
    }
}

// ── USB tethering ──────────────────────────────────────────────────────────

class UsbTetherTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : SystemToggleTile(id, label, icon) {
    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java) }

    private val tetheredIfacesMethod by lazy {
        runCatching { cm.javaClass.getMethod("getTetheredIfaces") }.getOrNull()
    }

    override fun readEnabled(): Boolean {
        val ifaces = runCatching {
            (tetheredIfacesMethod?.invoke(cm) as? Array<*>) ?: emptyArray<Any>()
        }.getOrDefault(emptyArray())
        return ifaces.any { it?.toString()?.startsWith("usb") == true }
    }

    override fun writeEnabled(enabled: Boolean) {
        runCatching {
            if (enabled) cm.startTethering(ConnectivityManager.TETHERING_USB, false, null)
            else cm.stopTethering(ConnectivityManager.TETHERING_USB)
        }
    }
}

// ── Cast (wireless display / media route) ──────────────────────────────────

class CastTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    private val context: Context,
) : TileAction {
    override val group: String get() = QS_GROUP_SYSTEM

    private val router by lazy { context.getSystemService(MediaRouter::class.java) }
    private val enabledState = mutableStateOf(false)
    private var activeRoute: MediaRouter.RouteInfo? = null
    private var candidateRoute: MediaRouter.RouteInfo? = null

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) {
            activeRoute = info.takeIf { !it.isDefault }
            enabledState.value = activeRoute != null
        }

        override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) {
            if (activeRoute == info) {
                activeRoute = null
                enabledState.value = false
            }
        }

        override fun onRouteAdded(router: MediaRouter, info: MediaRouter.RouteInfo) {
            if (candidateRoute == null && !info.isDefault && info.isEnabled) {
                candidateRoute = info
            }
        }

        override fun onRouteRemoved(router: MediaRouter, info: MediaRouter.RouteInfo) {
            if (candidateRoute == info) candidateRoute = null
        }

        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {}

        override fun onRouteGrouped(
            router: MediaRouter,
            info: MediaRouter.RouteInfo,
            group: MediaRouter.RouteGroup,
            index: Int
        ) {}

        override fun onRouteUngrouped(
            router: MediaRouter,
            info: MediaRouter.RouteInfo,
            group: MediaRouter.RouteGroup
        ) {}

        override fun onRouteVolumeChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {}
    }

    @Composable
    override fun observeEnabled(): State<Boolean> {
        DisposableEffect(Unit) {
            runCatching {
                activeRoute = router.selectedRoute.takeIf { !it.isDefault }
                enabledState.value = activeRoute != null
                router.addCallback(
                    MediaRouter.ROUTE_TYPE_LIVE_AUDIO or MediaRouter.ROUTE_TYPE_LIVE_VIDEO or
                        MediaRouter.ROUTE_TYPE_USER,
                    callback
                )
            }
            onDispose {
                runCatching { router.removeCallback(callback) }
            }
        }
        return enabledState
    }

    override fun toggle() {
        runCatching {
            if (activeRoute != null) {
                router.defaultRoute.select()
            } else {
                candidateRoute?.takeIf { it.isEnabled && !it.isDefault }?.select()
            }
        }
    }
}

/** True when the device has usable NFC hardware. */
fun hasNfcSupport(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

/** True when the device has a camera with a flash unit. */
fun hasFlashlightSupport(context: Context): Boolean {
    val cm = context.getSystemService(CameraManager::class.java)
    return runCatching {
        cm.cameraIdList.any { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrDefault(false)
}