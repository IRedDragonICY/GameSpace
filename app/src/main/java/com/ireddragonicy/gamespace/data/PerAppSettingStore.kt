package com.ireddragonicy.gamespace.data

import android.app.GameManager
import android.app.IGameManagerService
import android.content.Context
import android.os.Parcel
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.ireddragonicy.gamespace.data.settings.AfmeSettingsStore
import com.ireddragonicy.gamespace.data.settings.DisplaySettingsStore
import com.ireddragonicy.gamespace.data.settings.GfxSettingsStore
import com.ireddragonicy.gamespace.data.settings.TouchSettingsStore
import com.ireddragonicy.gamespace.thermal.ThermalProfiles
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade over Afme/Gfx/Touch/Display sub-stores. Owns cross-domain concerns:
 * live-prop publishing, AFME arming revision, filter push, GPU composition.
 * Public API is unchanged so all existing call-sites keep compiling.
 */
@Singleton
class PerAppSettingStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val io: SettingsIO = SettingsIO(context),
    private val activeSession: ActiveSessionStore = ActiveSessionStore(),
    private val afme: AfmeSettingsStore = AfmeSettingsStore(io),
    private val gfx: GfxSettingsStore = GfxSettingsStore(io),
    private val touch: TouchSettingsStore = TouchSettingsStore(io),
    private val display: DisplaySettingsStore = DisplaySettingsStore(context, io, activeSession),
) {
    companion object {
        private const val TAG = "PerAppSettingStore"
        private val afmeRevisionState = mutableIntStateOf(0)
        val afmeRevision: Int get() = afmeRevisionState.intValue
    }

    private fun isLiveSession(pkg: String) = activeSession.currentPackage == pkg

    private fun setLiveProp(pkg: String, name: String, value: String) {
        if (!isLiveSession(pkg)) return
        io.setProp(name, value)
    }

    private inline fun <T> withAfmeRevision(block: (Int) -> T): T = block(afmeRevision)

    // ── AFME ──
    fun afmeEnabled(pkg: String): Boolean = withAfmeRevision { afme.enabled(pkg) }
    fun setAfmeEnabled(pkg: String, enabled: Boolean) { afme.setEnabled(pkg, enabled); syncAfmeState(pkg) }

    fun afmeMultiplier(pkg: String): Int = withAfmeRevision { afme.multiplier(pkg) }
    fun setAfmeMultiplier(pkg: String, m: Int) { afme.setMultiplier(pkg, m); syncAfmeState(pkg) }

    fun afmeFactor(pkg: String) = afme.factor(pkg)
    fun setAfmeFactor(pkg: String, factor: String) {
        afme.setFactor(pkg, factor)
        setLiveProp(pkg, "persist.sys.afme.factor", if (factor == "auto") "" else factor)
    }
    fun afmeMethod(pkg: String) = afme.method(pkg)
    fun setAfmeMethod(pkg: String, method: Int) {
        afme.setMethod(pkg, method)
        setLiveProp(pkg, "persist.sys.afme.method", method.toString())
    }
    fun afmeVrsFg(pkg: String) = afme.vrsFg(pkg)
    fun setAfmeVrsFg(pkg: String, e: Boolean) {
        afme.setVrsFg(pkg, e); setLiveProp(pkg, "persist.sys.afme.vrs_fg", if (e) "1" else "0")
    }
    fun afmeHudMask(pkg: String) = afme.hudMask(pkg)
    fun setAfmeHudMask(pkg: String, e: Boolean) {
        afme.setHudMask(pkg, e); setLiveProp(pkg, "persist.sys.afme.hud_mask", if (e) "1" else "0")
    }
    fun smoothMotion(pkg: String) = afme.smoothMotion(pkg)
    fun setSmoothMotion(pkg: String, e: Boolean) {
        afme.setSmoothMotion(pkg, e); setLiveProp(pkg, "persist.sys.smooth_motion", if (e) "1" else "0")
    }

    fun publishForSession(pkg: String) {
        syncAfmeState(pkg)
        if (isLiveSession(pkg)) pushStack(filterStack(pkg))
    }

    private fun syncAfmeState(pkg: String) {
        afmeRevisionState.intValue++
        if (!isLiveSession(pkg)) return
        val wantFg = afmeEnabled(pkg)
        val wantFilter = filterEnabled(pkg)
        val wantAf = gpuAf(pkg)
        val mult = afmeMultiplier(pkg)
        val enable = wantFg || wantFilter || wantAf > 0
        io.setProp("persist.sys.afme.enable", if (enable) "1" else "0")
        io.setProp("persist.sys.afme.app", if (enable) pkg else "")
        io.setProp("persist.sys.afme.fg", if (wantFg) "1" else "0")
        io.setProp("persist.sys.afme.multiplier", (if (wantFg) mult else 2).toString())
        io.setProp("persist.sys.afme.filter", if (wantFilter) "1" else "0")
        io.setProp("persist.sys.afme.af", wantAf.toString())
    }

    // ── Filter (GFX) ──
    fun filterEnabled(pkg: String): Boolean = withAfmeRevision { gfx.filterEnabled(pkg) }
    fun setFilterEnabled(pkg: String, enabled: Boolean) {
        gfx.setFilterEnabled(pkg, enabled); syncAfmeState(pkg)
        if (enabled && isLiveSession(pkg)) pushStack(filterStack(pkg))
    }
    fun filterStack(pkg: String): FilterStack = gfx.filterStack(pkg)
    fun setFilterStack(pkg: String, stack: FilterStack) {
        gfx.setFilterStack(pkg, stack)
        if (isLiveSession(pkg)) pushStack(stack)
    }
    fun pushStack(stack: FilterStack) {
        for (i in 0 until FilterStack.MAX) {
            io.setProp("persist.sys.afme.filter.s$i", stack.nodes.getOrNull(i)?.serialize() ?: "")
        }
        io.setProp("persist.sys.afme.filter.n", stack.nodes.size.toString())
    }
    fun setFilterLive(live: Boolean) = io.setProp("persist.sys.afme.filter.live", if (live) "1" else "0")

    // ── GFX scalars ──
    fun sgsrMode(pkg: String) = gfx.sgsrMode(pkg)
    fun setSgsrMode(pkg: String, mode: Int) {
        gfx.setSgsrMode(pkg, mode)
        setLiveProp(pkg, "persist.sys.sgsr.enable", if (mode > 0) "1" else "0")
        if (mode > 0) setLiveProp(pkg, "persist.sys.sgsr.mode", mode.toString())
    }
    fun vrsLevel(pkg: String) = gfx.vrsLevel(pkg)
    fun setVrsLevel(pkg: String, level: Int) = gfx.setVrsLevel(pkg, level)
    fun resolution(pkg: String) = gfx.resolution(pkg)
    fun setResolution(pkg: String, factor: String) {
        gfx.setResolution(pkg, factor)
        applyResolutionToFramework(pkg, factor)
    }
    fun applyResolutionForSession(pkg: String, scale: Float) {
        applyResolutionToFramework(pkg, scale.toString())
    }
    fun resetResolutionForSession(pkg: String) {
        applyResolutionToFramework(pkg, "1.0")
    }
    private fun applyResolutionToFramework(pkg: String, factor: String) {
        val scalingFactor = factor.toFloatOrNull() ?: 1.0f
        val gm = context.getSystemService(Context.GAME_SERVICE) as? GameManager
        if (gm == null) {
            Log.w(TAG, "GameManager service null")
            return
        }
        val binder = ServiceManager.getService(Context.GAME_SERVICE)
        if (binder == null) {
            Log.w(TAG, "game binder null")
            return
        }
        val igms = IGameManagerService.Stub.asInterface(binder)
        val userId = UserHandle.myUserId()
        val gameMode = try {
            gm.getGameMode(pkg)
        } catch (e: Throwable) {
            Log.e(TAG, "getGameMode threw", e)
            return
        }
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            Log.w(TAG, "gameMode UNSUPPORTED for $pkg")
            return
        }
        try {
            igms.updateResolutionScalingFactor(pkg, gameMode, scalingFactor, userId)
            Log.i(TAG, "updateResolutionScalingFactor ok: $pkg mode=$gameMode scale=$scalingFactor")
        } catch (e: Throwable) {
            Log.e(TAG, "updateResolutionScalingFactor threw: $pkg mode=$gameMode scale=$scalingFactor", e)
        }
        // Live rescale is handled inside GameManagerService (system_server) via
        // WindowManagerInternal.updateCompatScaleForPackage — no app-side hook needed.
    }
    fun colorEnhance(pkg: String) = gfx.colorEnhance(pkg)
    fun setColorEnhance(pkg: String, e: Boolean) = gfx.setColorEnhance(pkg, e)

    fun gpuMsaa(pkg: String) = gfx.gpuMsaa(pkg)
    fun setGpuMsaa(pkg: String, level: Int) {
        gfx.setGpuMsaa(pkg, level)
        setLiveProp(pkg, "persist.sys.gamespace.gpu_msaa", level.toString())
        setLiveProp(pkg, "debug.egl.force_msaa", if (level > 0) "1" else "0")
    }
    fun gpuAf(pkg: String): Int = withAfmeRevision { gfx.gpuAf(pkg) }
    fun setGpuAf(pkg: String, level: Int) { gfx.setGpuAf(pkg, level); syncAfmeState(pkg) }
    fun gpuTexQuality(pkg: String) = gfx.gpuTexQuality(pkg)
    fun setGpuTexQuality(pkg: String, quality: Int) = gfx.setGpuTexQuality(pkg, quality)

    // ── Touch (HAL report-rate + OSS input-side accidental-touch filter) ──
    fun touchSuperReport(pkg: String) = touch.superReport(pkg)
    fun setTouchSuperReport(pkg: String, v: Boolean) = touch.setSuperReport(pkg, v)
    fun touchEdgeFilter(pkg: String) = touch.edgeFilter(pkg)
    fun setTouchEdgeFilter(pkg: String, level: Int) = touch.setEdgeFilter(pkg, level)
    fun touchGripSuppression(pkg: String) = touch.gripSuppression(pkg)
    fun setTouchGripSuppression(pkg: String, level: Int) = touch.setGripSuppression(pkg, level)

    // ── Display ──
    fun displayStyle(pkg: String) = display.style(pkg)
    fun setDisplayStyle(pkg: String, mode: Int) = display.setStyle(pkg, mode)

    // ── Thermal (kept here; reads shared key) ──
    fun thermalProfile(pkg: String): Int = io.getPerAppInt(ThermalProfiles.KEY_APP_PROFILES, pkg, 0)
    fun setThermalProfile(pkg: String, index: Int) =
        io.putPerApp(ThermalProfiles.KEY_APP_PROFILES, pkg, index.takeIf { it > 0 })

    // ── GPU composition (global SurfaceFlinger) ──
    fun gpuComposition(pkg: String): Boolean {
        val current = Settings.Secure.getStringForUser(
            context.contentResolver, "disable_hw_overlays_apps", UserHandle.USER_CURRENT).orEmpty()
        return current.split(",").any { it == pkg }
    }
    fun setGpuComposition(pkg: String, enabled: Boolean) {
        val current = Settings.Secure.getStringForUser(
            context.contentResolver, "disable_hw_overlays_apps", UserHandle.USER_CURRENT).orEmpty()
        val set = current.split(",").filter { it.isNotBlank() }.toMutableSet()
        if (enabled) set.add(pkg) else set.remove(pkg)
        runCatching {
            Settings.Secure.putStringForUser(context.contentResolver,
                "disable_hw_overlays_apps", set.joinToString(","), UserHandle.USER_CURRENT)
        }
        if (isLiveSession(pkg)) forceClientComposition(enabled)
    }
    fun forceClientComposition(enabled: Boolean) {
        runCatching {
            val sf = ServiceManager.getService("SurfaceFlinger") ?: return
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.ui.ISurfaceComposer")
                data.writeInt(if (enabled) 1 else 0)
                sf.transact(1008, data, reply, 0)
            } finally { data.recycle(); reply.recycle() }
        }
    }

    fun clearSession() {
        io.setProp("persist.sys.afme.enable", "0")
        io.setProp("persist.sys.afme.app", "")
        io.setProp("persist.sys.afme.fg", "0")
        io.setProp("persist.sys.afme.multiplier", "2")
        io.setProp("persist.sys.afme.filter", "0")
        io.setProp("persist.sys.afme.af", "0")
        io.setProp("persist.sys.afme.factor", "")
        io.setProp("persist.sys.afme.method", "0")
        io.setProp("persist.sys.afme.vrs_fg", "0")
        io.setProp("persist.sys.afme.hud_mask", "0")
        io.setProp("persist.sys.smooth_motion", "0")
        io.setProp("persist.sys.afme.filter.n", "0")
        io.setProp("persist.sys.afme.filter.live", "0")
        for (i in 0 until FilterStack.MAX) {
            io.setProp("persist.sys.afme.filter.s$i", "")
        }
        io.setProp("persist.sys.sgsr.enable", "0")
        io.setProp("persist.sys.sgsr.mode", "")
        io.setProp("persist.sys.gamespace.gpu_msaa", "0")
        io.setProp("debug.egl.force_msaa", "0")
    }

    // ── Cleanup ──
    fun removeAllForPackage(pkg: String) {
        afme.removeAll(pkg); gfx.removeAll(pkg); touch.removeAll(pkg); display.removeAll(pkg)
        io.putPerApp(ThermalProfiles.KEY_APP_PROFILES, pkg, null)
        setGpuComposition(pkg, false)
        syncAfmeState(pkg)
    }
}
