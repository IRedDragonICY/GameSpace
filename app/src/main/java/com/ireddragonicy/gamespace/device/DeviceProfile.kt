/*
* Copyright (C) 2026 IRedDragonICY
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*/
package com.ireddragonicy.gamespace.device

import android.content.Context
import android.os.Build
import android.os.SystemProperties
import java.io.File

data class ThermalZoneSpec(
    val index: Int,
    val type: String,
    val tempPath: String,
)

data class CpuClusterSpec(
    val policyIndex: Int,
    val policyDir: String,
    val cpuIds: List<Int>,
    val name: String,
    val hwMinKhz: Long,
    val hwMaxKhz: Long,
)

data class GpuSpec(
    val baseDir: String,
    val curFreqPaths: List<String>,
    val busyPercentPath: String?,
    val availableFreqsPath: String?,
    val devfreqMinPath: String?,
    val devfreqMaxPath: String?,
    val governorPath: String?,
    val availableGovernorsPath: String?,
)

data class DeviceProfile(
    val model: String,
    val platform: String,
    val thermalZones: List<ThermalZoneSpec>,
    val cpuClusters: List<CpuClusterSpec>,
    val gpu: GpuSpec?,
    val batteryTempPath: String?,
    val batteryCurrentNowPath: String?,
    val batteryVoltageNowPath: String?,
    val batteryCapacityPath: String?,
    val colorModeMap: Map<Int, Int>,
    val usesAospColorFallback: Boolean,
) {
    val cpuTempPaths: List<String>
        get() = thermalZones
            .filter {
                val t = it.type.lowercase()
                t.contains("cpu") || t.contains("core") || t.contains("cluster") ||
                    t.contains("big") || t.contains("little") || t.contains("prime")
            }
            .map { it.tempPath }
            .ifEmpty { fallbackCpuTempPaths() }

    val gpuTempPaths: List<String>
        get() = thermalZones
            .filter {
                val t = it.type.lowercase()
                t.contains("gpu") || t.contains("kgsl") || t.contains("adreno")
            }
            .map { it.tempPath }
            .ifEmpty { fallbackGpuTempPaths() }

    val ddrTempPath: String?
        get() = thermalZones
            .firstOrNull {
                val t = it.type.lowercase()
                t.contains("ddr") || t.contains("dram") || t.contains("mem")
            }
            ?.tempPath
            ?: fallbackDdrTempPath()

    val primaryCpuPolicyDir: String?
        get() = cpuClusters.lastOrNull()?.policyDir

    private fun fallbackCpuTempPaths(): List<String> = listOf(
        "/sys/class/thermal/thermal_zone9/temp"
    )

    private fun fallbackGpuTempPaths(): List<String> = listOf(
        "/sys/class/thermal/thermal_zone24/temp"
    )

    private fun fallbackDdrTempPath(): String? =
        "/sys/class/thermal/thermal_zone23/temp"
}

object DeviceProfiles {
    @Volatile
    private var instance: DeviceProfile? = null

    fun get(context: Context): DeviceProfile {
        return instance ?: synchronized(this) {
            instance ?: detect(context.applicationContext).also { instance = it }
        }
    }

    private fun detect(context: Context): DeviceProfile {
        val thermalZones = scanThermalZones()
        val cpuClusters = scanCpuClusters()
        val gpu = scanGpu()
        val battery = scanBattery()
        val color = detectColorModeMap(context)

        return DeviceProfile(
            model = Build.MODEL,
            platform = SystemProperties.get("ro.board.platform", "unknown"),
            thermalZones = thermalZones,
            cpuClusters = cpuClusters,
            gpu = gpu,
            batteryTempPath = battery.first,
            batteryCurrentNowPath = battery.second,
            batteryVoltageNowPath = battery.third,
            batteryCapacityPath = battery.fourth,
            colorModeMap = color.first,
            usesAospColorFallback = color.second,
        )
    }

    private fun scanThermalZones(): List<ThermalZoneSpec> {
        val zones = mutableListOf<ThermalZoneSpec>()
        runCatching {
            for (i in 0 until 128) {
                val typeFile = File("/sys/class/thermal/thermal_zone$i/type")
                val tempFile = File("/sys/class/thermal/thermal_zone$i/temp")
                if (!typeFile.exists() || !tempFile.exists()) continue
                val type = runCatching { typeFile.readText().trim() }.getOrDefault("zone$i")
                zones.add(
                    ThermalZoneSpec(
                        index = i,
                        type = type,
                        tempPath = tempFile.absolutePath,
                    )
                )
            }
        }

        if (zones.isEmpty()) {
            // Legacy POCO F7 fallback
            val cpu = intArrayOf(1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)
            val gpu = intArrayOf(24, 25, 26, 27, 28, 29)
            cpu.forEach {
                zones.add(ThermalZoneSpec(it, "cpu-fallback-$it", "/sys/class/thermal/thermal_zone$it/temp"))
            }
            gpu.forEach {
                zones.add(ThermalZoneSpec(it, "gpu-fallback-$it", "/sys/class/thermal/thermal_zone$it/temp"))
            }
            zones.add(ThermalZoneSpec(23, "ddr-fallback", "/sys/class/thermal/thermal_zone23/temp"))
        }

        return zones
    }

    private fun scanCpuClusters(): List<CpuClusterSpec> {
        val result = mutableListOf<CpuClusterSpec>()
        runCatching {
            val base = File("/sys/devices/system/cpu/cpufreq")
            val policies = base.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("policy") }
                ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 }
                ?: emptyList()

            policies.forEach { policyDir ->
                val policyIndex = policyDir.name.removePrefix("policy").toIntOrNull() ?: return@forEach
                val affected = File(policyDir, "affected_cpus")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    ?.split(" ")
                    ?.mapNotNull { it.toIntOrNull() }
                    ?: emptyList()

                val min = File(policyDir, "cpuinfo_min_freq")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 0L

                val max = File(policyDir, "cpuinfo_max_freq")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    ?.toLongOrNull()
                    ?: 0L

                val name = when {
                    max > 3_100_000L -> "Prime"
                    max > 2_500_000L -> "Gold"
                    else -> "Silver"
                }

                result.add(
                    CpuClusterSpec(
                        policyIndex = policyIndex,
                        policyDir = policyDir.absolutePath,
                        cpuIds = affected,
                        name = name,
                        hwMinKhz = min,
                        hwMaxKhz = max,
                    )
                )
            }
        }

        if (result.isEmpty()) {
            // Legacy POCO F7 fallback
            listOf(
                Triple(0, listOf(0, 1), "Silver"),
                Triple(2, listOf(2, 3, 4), "Gold"),
                Triple(5, listOf(5, 6), "Gold+"),
                Triple(7, listOf(7), "Prime"),
            ).forEach { (policy, ids, name) ->
                val dir = "/sys/devices/system/cpu/cpufreq/policy$policy"
                if (File(dir).exists()) {
                    result.add(
                        CpuClusterSpec(
                            policyIndex = policy,
                            policyDir = dir,
                            cpuIds = ids,
                            name = name,
                            hwMinKhz = File(dir, "cpuinfo_min_freq").readText().trim().toLongOrNull() ?: 0L,
                            hwMaxKhz = File(dir, "cpuinfo_max_freq").readText().trim().toLongOrNull() ?: 0L,
                        )
                    )
                }
            }
        }

        return result
    }

    private fun scanGpu(): GpuSpec? {
        val kgsl = File("/sys/class/kgsl/kgsl-3d0")
        if (kgsl.exists()) {
            return GpuSpec(
                baseDir = kgsl.absolutePath,
                curFreqPaths = listOf(
                    "${kgsl.absolutePath}/gpuclk",
                    "${kgsl.absolutePath}/devfreq/cur_freq",
                ),
                busyPercentPath = "${kgsl.absolutePath}/gpu_busy_percentage",
                availableFreqsPath = "${kgsl.absolutePath}/gpu_available_frequencies",
                devfreqMinPath = "${kgsl.absolutePath}/devfreq/min_freq",
                devfreqMaxPath = "${kgsl.absolutePath}/devfreq/max_freq",
                governorPath = "${kgsl.absolutePath}/devfreq/governor",
                availableGovernorsPath = "${kgsl.absolutePath}/devfreq/available_governors",
            )
        }

        // Generic devfreq fallback
        runCatching {
            val devfreq = File("/sys/class/devfreq")
            val gpuDir = devfreq.listFiles()?.firstOrNull {
                it.name.contains("gpu", true) ||
                    it.name.contains("kgsl", true) ||
                    it.name.contains("3d0", true)
            }
            if (gpuDir != null && File(gpuDir, "cur_freq").exists()) {
                return GpuSpec(
                    baseDir = gpuDir.absolutePath,
                    curFreqPaths = listOf("${gpuDir.absolutePath}/cur_freq"),
                    busyPercentPath = File(gpuDir, "load").takeIf { it.exists() }?.absolutePath,
                    availableFreqsPath = File(gpuDir, "available_frequencies")
                        .takeIf { it.exists() }?.absolutePath,
                    devfreqMinPath = File(gpuDir, "min_freq").takeIf { it.exists() }?.absolutePath,
                    devfreqMaxPath = File(gpuDir, "max_freq").takeIf { it.exists() }?.absolutePath,
                    governorPath = File(gpuDir, "governor").takeIf { it.exists() }?.absolutePath,
                    availableGovernorsPath = File(gpuDir, "available_governors")
                        .takeIf { it.exists() }?.absolutePath,
                )
            }
        }

        return null
    }

    private fun scanBattery(): Quad<String?, String?, String?, String?> {
        val battery = File("/sys/class/power_supply/battery")
        if (battery.exists()) {
            return Quad(
                File(battery, "temp").takeIf { it.exists() }?.absolutePath,
                File(battery, "current_now").takeIf { it.exists() }?.absolutePath,
                File(battery, "voltage_now").takeIf { it.exists() }?.absolutePath,
                File(battery, "capacity").takeIf { it.exists() }?.absolutePath,
            )
        }

        runCatching {
            val base = File("/sys/class/power_supply")
            val candidate = base.listFiles()?.firstOrNull { dir ->
                val type = File(dir, "type").takeIf { it.exists() }?.readText()?.trim()
                type.equals("Battery", ignoreCase = true)
            }
            if (candidate != null) {
                return Quad(
                    File(candidate, "temp").takeIf { it.exists() }?.absolutePath,
                    File(candidate, "current_now").takeIf { it.exists() }?.absolutePath,
                    File(candidate, "voltage_now").takeIf { it.exists() }?.absolutePath,
                    File(candidate, "capacity").takeIf { it.exists() }?.absolutePath,
                )
            }
        }

        return Quad(null, null, null, null)
    }

    private fun detectColorModeMap(context: Context): Pair<Map<Int, Int>, Boolean> {
        val vendorMap = mapOf(
            0 to 269, // Original
            1 to 258, // Vivid
            2 to 256, // Saturated
            3 to 268, // P3
            4 to 267, // sRGB
        )

        val aospMap = mapOf(
            0 to 0, // Natural
            1 to 1, // Boosted
            2 to 2, // Saturated
            3 to 3, // Automatic
            4 to 0, // sRGB-ish fallback
        )

        val cdm = runCatching {
            context.getSystemService(android.hardware.display.ColorDisplayManager::class.java)
        }.getOrNull()

        val allowed = runCatching {
            val m = cdm?.javaClass?.getMethod("getAllowedColorModes")
            (m?.invoke(cdm) as? IntArray)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

        val isXiaomiLike = Build.MANUFACTURER.equals("Xiaomi", true) ||
            SystemProperties.get("ro.miui.ui.version.name", "").isNotEmpty()

        val hasVendorModes = allowed.any { it >= 256 } || isXiaomiLike

        return if (hasVendorModes) {
            vendorMap to false
        } else {
            aospMap to true
        }
    }

    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
