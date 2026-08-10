package com.ireddragonicy.gamespace.data.settings

import com.ireddragonicy.gamespace.data.FilterStack
import com.ireddragonicy.gamespace.data.SettingsIO
import javax.inject.Inject
import javax.inject.Singleton

/** Raw per-app graphics persistence (SGSR/VRS/resolution/colour/GPU/filter). */
@Singleton
class GfxSettingsStore @Inject constructor(private val io: SettingsIO) {

    companion object {
        const val KEY_SGSR_MODE      = "gamespace_sgsr_mode"
        const val KEY_VRS_LEVEL      = "gamespace_vrs_level"
        const val KEY_RESOLUTION     = "gamespace_game_resolution"
        const val KEY_COLOR_ENHANCE  = "gamespace_color_enhance"
        const val KEY_FILTER_ENABLED = "gamespace_filter_enabled"
        const val KEY_FILTER_STACK   = "gamespace_filter_stack"
        const val KEY_GPU_MSAA       = "gamespace_gpu_msaa"
        const val KEY_GPU_AF         = "gamespace_gpu_af"
        const val KEY_GPU_TEX_QUALITY= "gamespace_gpu_tex_quality"
    }

    fun sgsrMode(pkg: String) = io.getPerAppInt(KEY_SGSR_MODE, pkg, 0)
    fun setSgsrMode(pkg: String, mode: Int) = io.putPerApp(KEY_SGSR_MODE, pkg, mode.takeIf { it > 0 })

    fun vrsLevel(pkg: String) = io.getPerAppInt(KEY_VRS_LEVEL, pkg, 0)
    fun setVrsLevel(pkg: String, level: Int) = io.putPerApp(KEY_VRS_LEVEL, pkg, level.takeIf { it > 0 })

    fun resolution(pkg: String) = io.getPerAppString(KEY_RESOLUTION, pkg, "1.0")
    fun setResolution(pkg: String, factor: String) = io.putPerApp(KEY_RESOLUTION, pkg, factor.takeIf { it != "1.0" })

    fun colorEnhance(pkg: String) = io.getPerAppBoolean(KEY_COLOR_ENHANCE, pkg, false)
    fun setColorEnhance(pkg: String, enabled: Boolean) = io.putPerApp(KEY_COLOR_ENHANCE, pkg, if (enabled) true else null)

    fun filterEnabled(pkg: String) = io.getPerAppBoolean(KEY_FILTER_ENABLED, pkg, false)
    fun setFilterEnabled(pkg: String, enabled: Boolean) = io.putPerApp(KEY_FILTER_ENABLED, pkg, enabled.takeIf { it })

    fun filterStack(pkg: String): FilterStack =
        FilterStack.deserialize(io.getPerAppString(KEY_FILTER_STACK, pkg, ""))
    fun setFilterStack(pkg: String, stack: FilterStack) =
        io.putPerApp(KEY_FILTER_STACK, pkg, stack.serialize().takeIf { stack.nodes.isNotEmpty() })

    fun gpuMsaa(pkg: String) = io.getPerAppInt(KEY_GPU_MSAA, pkg, 0)
    fun setGpuMsaa(pkg: String, level: Int) = io.putPerApp(KEY_GPU_MSAA, pkg, level.takeIf { it > 0 })

    fun gpuAf(pkg: String) = io.getPerAppInt(KEY_GPU_AF, pkg, 0)
    fun setGpuAf(pkg: String, level: Int) = io.putPerApp(KEY_GPU_AF, pkg, level.takeIf { it > 0 })

    fun gpuTexQuality(pkg: String) = io.getPerAppInt(KEY_GPU_TEX_QUALITY, pkg, 0)
    fun setGpuTexQuality(pkg: String, quality: Int) = io.putPerApp(KEY_GPU_TEX_QUALITY, pkg, quality.takeIf { it > 0 })

    fun removeAll(pkg: String) = listOf(
        KEY_SGSR_MODE, KEY_VRS_LEVEL, KEY_RESOLUTION, KEY_COLOR_ENHANCE,
        KEY_FILTER_ENABLED, KEY_FILTER_STACK, KEY_GPU_MSAA, KEY_GPU_AF, KEY_GPU_TEX_QUALITY,
    ).forEach { io.putPerApp(it, pkg, null) }
}
