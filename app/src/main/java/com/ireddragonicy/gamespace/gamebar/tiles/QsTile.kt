package com.ireddragonicy.gamespace.gamebar.tiles

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.service.quicksettings.IQSService
import android.service.quicksettings.IQSTileService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "QsTile"

/** Grouping label for platform / system-signed tile sources. */
const val QS_GROUP_SYSTEM = "System"

/**
 * A TileService discovered on the device (system or third-party app).
 * Holds the static metadata needed for the "available tiles" pool.
 */
data class DiscoveredQsTile(
    val componentName: ComponentName,
    val label: String,
    val appLabel: String,
    val isSystemApp: Boolean,
    val serviceInfo: ServiceInfo,
)

/**
 * Discovers every exported [TileService] on the device — vendor/system tiles
 * and third-party app tiles — so they can be offered in the tile pool.
 */
@Singleton
class QsTileCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val discoveredTiles: List<DiscoveredQsTile> by lazy {
        val pm = context.packageManager
        val intent = Intent(TileService.ACTION_QS_TILE)
        val infos = runCatching {
            pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }.getOrNull() ?: emptyList()

        infos.asSequence()
            .mapNotNull { info ->
                val si = runCatching {
                    pm.getServiceInfo(ComponentName(info.serviceInfo.packageName, info.serviceInfo.name), PackageManager.MATCH_ALL)
                }.getOrNull()
                if (si == null || !si.exported || si.packageName == context.packageName) null else si
            }
            .mapNotNull { si ->
                val component = ComponentName(si.packageName, si.name)
                runCatching {
                    val label = si.loadLabel(pm).toString()
                    DiscoveredQsTile(
                        componentName = component,
                        label = label.ifBlank { component.shortClassName },
                        appLabel = si.applicationInfo?.loadLabel(pm)?.toString()
                            ?: si.packageName,
                        isSystemApp = si.applicationInfo != null &&
                            (si.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                        serviceInfo = si,
                    )
                }.getOrNull()
            }
            .sortedWith(compareBy<DiscoveredQsTile> { !it.isSystemApp }
                .thenBy { it.appLabel.lowercase() }
                .thenBy { it.label.lowercase() })
            .toList()
    }

    fun groupOf(tile: DiscoveredQsTile): String = if (tile.isSystemApp) QS_GROUP_SYSTEM else tile.appLabel
}

/**
 * A live QS tile hosted from another app's TileService.
 *
 * Acts as the tile HOST (the role SystemUI normally plays): binds the target
 * TileService, hands it a [Tile] through our [IQSService] implementation and
 * forwards clicks back via [IQSTileService.onClick].
 *
 * Binding is lifecycle-driven from [observeEnabled]: the tile only binds while
 * it is composed in a functional row (QS row 1 / tool grid). Tiles sitting in
 * the "available" pool never call it, so nothing is bound for tiles the user
 * has not placed.
 */
class QsServiceTile(
    override val id: String,
    override val label: String,
    override val icon: Int,
    val sourceGroup: String,
    private val discovered: DiscoveredQsTile,
    private val context: Context,
) : TileAction {
    override val iconDrawable: Drawable?
        get() = liveIconDrawable.value ?: staticIconDrawable

    override val group: String get() = sourceGroup

    private val staticIconDrawable: Drawable? = loadServiceIcon()
    private val liveIconDrawable = mutableStateOf<Drawable?>(null)

    private val enabledState = mutableStateOf(false)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile private var tileService: IQSTileService? = null
    @Volatile private var currentTile: Tile? = null

    private val token: IBinder = Binder()

    private val qsService = object : IQSService.Stub() {
        override fun getTile(tile: IBinder): Tile = currentTile ?: Tile().apply {
            setState(Tile.STATE_INACTIVE)
            setLabel(label)
            setIcon(loadServiceIcon()?.let { android.graphics.drawable.Icon.createWithBitmap(
                toBitmap(it, 48)) })
        }

        override fun updateQsTile(tile: Tile?, service: IBinder?) {
            if (tile == null) return
            currentTile = tile
            mainHandler.post {
                enabledState.value = tile.state == Tile.STATE_ACTIVE
                tile.icon?.let { icon ->
                    runCatching { icon.loadDrawable(context) }
                        .getOrNull()
                        ?.let { drawable -> liveIconDrawable.value = drawable }
                }
            }
        }

        override fun updateStatusIcon(tile: IBinder?, icon: android.graphics.drawable.Icon?, contentDescription: String?) = Unit

        override fun onShowDialog(tile: IBinder?) = Unit

        override fun onStartActivity(tile: IBinder?) = Unit

        override fun startActivity(tile: IBinder?, pendingIntent: android.app.PendingIntent?) {
            runCatching { pendingIntent?.send() }
        }

        override fun isLocked(): Boolean = false

        override fun isSecure(): Boolean = false

        override fun startUnlockAndRun(tile: IBinder?) {
            runCatching { tileService?.onUnlockComplete() }
        }

        override fun onDialogHidden(tile: IBinder?) = Unit

        override fun onStartSuccessful(tile: IBinder?) = Unit
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            val bound = IQSTileService.Stub.asInterface(service)
            tileService = bound
            runCatching { bound.onTileAdded() }
            runCatching { bound.onStartListening() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            tileService = null
        }
    }

    private fun bind() {
        if (tileService != null) return
        runCatching {
            val intent = Intent(TileService.ACTION_QS_TILE)
                .setComponent(discovered.componentName)
                .putExtra(TileService.EXTRA_SERVICE, qsService.asBinder())
                .putExtra(TileService.EXTRA_TOKEN, token)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbind() {
        val svc = tileService
        if (svc == null) return
        tileService = null
        runCatching { svc.onStopListening() }
        runCatching { svc.onTileRemoved() }
        runCatching { context.unbindService(connection) }
    }

    private var holderCount = 0

    private fun retain() {
        if (holderCount++ == 0) bind()
    }

    private fun release() {
        if (--holderCount <= 0) {
            holderCount = 0
            unbind()
        }
    }

    @Composable
    override fun observeEnabled(): State<Boolean> {
        DisposableEffect(Unit) {
            retain()
            onDispose { release() }
        }
        return enabledState
    }

    override fun toggle() {
        val svc = tileService ?: run { bind(); return }
        runCatching { svc.onClick(token) }
    }

    override fun onLongClick() {
        runCatching {
            val prefs = Intent(TileService.ACTION_QS_TILE_PREFERENCES)
                .setComponent(discovered.componentName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(prefs)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is QsServiceTile && other.id == id

    override fun hashCode(): Int = id.hashCode()

    private fun loadServiceIcon(): Drawable? = runCatching {
        val pm = context.packageManager
        val si = discovered.serviceInfo
        val res = pm.getResourcesForApplication(si.packageName)
        si.iconResource.takeIf { it != 0 }?.let { rid ->
            runCatching { res.getDrawable(rid, null) }.getOrNull()
        } ?: runCatching { si.loadIcon(pm) }.getOrNull()
    }.getOrNull()

    private fun toBitmap(drawable: Drawable, size: Int): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }
}