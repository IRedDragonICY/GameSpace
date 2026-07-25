/*
* Copyright (C) 2026 IRedDragonICY
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*/
package com.ireddragonicy.gamespace.settings.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.device.DeviceProfiles
import com.ireddragonicy.gamespace.touch.XiaomiTouchFeatureClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Model (single source of truth for UI + copy text) ────────────────────────

private sealed interface DiagRow
private data class StatusRow(val label: String, val ok: Boolean, val detail: String = "") : DiagRow
private data class InfoRow(val label: String, val value: String) : DiagRow
private data class NodeRow(val path: String, val exists: Boolean) : DiagRow

private data class DiagSection(
    val title: String,
    val icon: ImageVector,
    val rows: List<DiagRow>,
)

private data class DiagReport(
    val sections: List<DiagSection>,
    val reportText: String,
    val servicesOk: Int,
    val servicesTotal: Int,
    val model: String,
    val platform: String,
)

// ── Activity (NO Hilt — this screen injects nothing) ─────────────────────────

class DiagnosticsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // KEY FIX: provide a real colorScheme, otherwise Compose falls back to
            // the default LIGHT scheme and renders dark text on the dark window.
            val isDark = isSystemInDarkTheme()
            val scheme = if (isDark) dynamicDarkColorScheme(this)
            else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) {
                DiagnosticsScreen(onBack = { finish() })
            }
        }
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var report by remember { mutableStateOf<DiagReport?>(null) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { collectReport(context) }
    }

    val onCopy: () -> Unit = {
        val r = report
        if (r == null) {
            scope.launch { snackbar.showSnackbar("Still collecting diagnostics…") }
        } else {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("GameSpace Diagnostics", r.reportText))
            }
            scope.launch { snackbar.showSnackbar("Diagnostics copied — paste anywhere to share") }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Diagnostics",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = report?.let { "${it.model} · ${it.platform}" } ?: "Probing device…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onCopy, enabled = report != null) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy report",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.mediumTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        val r = report
        if (r == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeroCard(r) }
            items(r.sections, key = { it.title }) { section ->
                SectionCard(section)
            }
            item {
                Text(
                    text = "Tap the copy icon to send this report to a developer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
    }
}

// ── Hero "System Health" card ────────────────────────────────────────────────

@Composable
private fun HeroCard(report: DiagReport) {
    val allOk = report.servicesOk == report.servicesTotal
    val isDark = isSystemInDarkTheme()
    val accent = if (allOk) okContent(isDark) else warnContent(isDark)
    val accentBg = if (allOk) okContainer(isDark) else warnContainer(isDark)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), // flat
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accentBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (allOk) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (allOk) "All systems operational" else "Attention needed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${report.servicesOk}/${report.servicesTotal} core services · " +
                        "${report.sections.sumOf { it.rows.size }} checks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(
                text = report.platform.uppercase(),
                content = accent,
                container = accentBg,
            )
        }
    }
}

// ── Section card ─────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(section: DiagSection) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), // flat
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${section.rows.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))

            // Rows
            section.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                when (row) {
                    is StatusRow -> StatusRowView(row)
                    is InfoRow -> InfoRowView(row)
                    is NodeRow -> NodeRowView(row)
                }
            }
        }
    }
}

// ── Row views ────────────────────────────────────────────────────────────────

@Composable
private fun StatusRowView(row: StatusRow) {
    val isDark = isSystemInDarkTheme()
    val content = if (row.ok) okContent(isDark) else failContent(isDark)
    val container = if (row.ok) okContainer(isDark) else failContainer(isDark)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (row.ok) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.detail.ifEmpty { if (row.ok) "OK" else "FAIL" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (row.detail.isEmpty()) content
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRowView(row: InfoRow) {
    val long = row.value.length > 26
    if (long) {
        // Stacked layout for long values (paths, etc.)
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    } else {
        // Side-by-side layout for short values
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun NodeRowView(row: NodeRow) {
    val isDark = isSystemInDarkTheme()
    val content = if (row.exists) okContent(isDark) else neutralContent(isDark)
    val container = if (row.exists) okContainer(isDark) else neutralContainer(isDark)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
        StatusChip(
            text = if (row.exists) "exists" else "missing",
            content = content,
            container = container,
        )
    }
}

@Composable
private fun StatusChip(text: String, content: Color, container: Color) {
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
        )
    }
}

// ── Semantic palette (Google Material green / red / amber / neutral) ─────────

private fun okContent(isDark: Boolean) = if (isDark) Color(0xFF81C995) else Color(0xFF1E8E3E)
private fun okContainer(isDark: Boolean) =
    if (isDark) Color(0xFF81C995).copy(alpha = 0.16f) else Color(0xFF1E8E3E).copy(alpha = 0.12f)
private fun failContent(isDark: Boolean) = if (isDark) Color(0xFFF28B82) else Color(0xFFD93025)
private fun failContainer(isDark: Boolean) =
    if (isDark) Color(0xFFF28B82).copy(alpha = 0.16f) else Color(0xFFD93025).copy(alpha = 0.12f)
private fun warnContent(isDark: Boolean) = if (isDark) Color(0xFFFFD666) else Color(0xFFE37400)
private fun warnContainer(isDark: Boolean) =
    if (isDark) Color(0xFFFFD666).copy(alpha = 0.16f) else Color(0xFFE37400).copy(alpha = 0.12f)
private fun neutralContent(isDark: Boolean) =
    if (isDark) Color(0xFFC4C7C5) else Color(0xFF444746)
private fun neutralContainer(isDark: Boolean) =
    if (isDark) Color(0xFFC4C7C5).copy(alpha = 0.14f) else Color(0xFF444746).copy(alpha = 0.10f)

// ── Data collection (runs once on IO) ────────────────────────────────────────

private class Sec(val title: String, val icon: ImageVector) {
    val rows = mutableListOf<DiagRow>()
    fun status(label: String, ok: Boolean, detail: String = "") {
        rows += StatusRow(label, ok, detail)
    }
    fun info(label: String, value: Any?) {
        rows += InfoRow(label, value.toString())
    }
    fun node(path: String, exists: Boolean) {
        rows += NodeRow(path, exists)
    }
}

private val NODE_CHECKS = listOf(
    "/sys/class/thermal/thermal_message/sconfig",
    "/sys/class/thermal/thermal_zone0/temp",
    "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
    "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
    "/sys/class/power_supply/battery/temp",
    "/sys/class/power_supply/battery/current_now",
    "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq",
    "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq",
    "/sys/module/migt/parameters/frame_boost_enable",
    "/sys/module/metis/parameters/mi_fboost_enable",
)

private fun collectReport(context: Context): DiagReport {
    val profile = DeviceProfiles.get(context)

    val gameSpaceOk = runCatching { ServiceManager.getService("game_space") != null }
        .getOrDefault(false)
    val axOk = runCatching {
        Class.forName("com.android.axion.platform.AxPlatformClient"); true
    }.getOrDefault(false)
    val touchOk = runCatching { XiaomiTouchFeatureClient.isAvailable }.getOrDefault(false)
    val selinux = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getenforce"))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.destroy()
        out
    }.getOrDefault("Unknown")

    val servicesOk = listOf(gameSpaceOk, axOk, touchOk).count { it }
    val servicesTotal = 3

    val core = Sec("Core Services", Icons.Rounded.Dns).apply {
        status("game_space service", gameSpaceOk)
        status("AxPlatformClient", axOk)
        status("Xiaomi Touch HAL", touchOk)
        info("SELinux", selinux)
    }

    val device = Sec("Device Profile", Icons.Rounded.Smartphone).apply {
        info("Model", profile.model)
        info("SoC platform", profile.platform)
        info("CPU clusters", profile.cpuClusters.size)
        info("Thermal zones", profile.thermalZones.size)
        info("GPU node", if (profile.gpu != null) "present" else "missing")
        info("AOSP color fallback", profile.usesAospColorFallback)
        info("Color mode map", "${profile.colorModeMap.size} entries")
    }

    val cpu = Sec("CPU Clusters", Icons.Rounded.Memory).apply {
        profile.cpuClusters.forEach { c ->
            info(
                "policy${c.policyIndex} · ${c.name}",
                "cpus ${c.cpuIds} · max ${c.hwMaxKhz / 1000} MHz",
            )
        }
    }

    val thermal = Sec("Thermal & Power", Icons.Rounded.Thermostat).apply {
        info("CPU temp paths", profile.cpuTempPaths.size)
        info("GPU temp paths", profile.gpuTempPaths.size)
        info("DDR temp", profile.ddrTempPath ?: "none")
        info("Battery temp", profile.batteryTempPath ?: "none")
        info("Battery current", profile.batteryCurrentNowPath ?: "none")
        info("Battery voltage", profile.batteryVoltageNowPath ?: "none")
        info("Battery capacity", profile.batteryCapacityPath ?: "none")
    }

    val gpu = Sec("GPU", Icons.Rounded.DeveloperBoard).apply {
        val g = profile.gpu
        if (g != null) {
            info("Base dir", g.baseDir)
            info("Busy %", g.busyPercentPath ?: "none")
            info("Governor", g.governorPath ?: "none")
            info("Available freqs", g.availableFreqsPath ?: "none")
        } else {
            info("GPU node", "NOT FOUND")
        }
    }

    val nodes = Sec("Node Checks", Icons.Rounded.Storage).apply {
        NODE_CHECKS.forEach { p -> node(p, File(p).exists()) }
    }

    val sections = listOf(core, device, cpu, thermal, gpu, nodes)

    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())
    val sb = StringBuilder()
    sb.appendLine("=== GameSpace Diagnostics ===")
    sb.appendLine("Generated : $ts")
    sb.appendLine("Model     : ${profile.model}")
    sb.appendLine("Platform  : ${profile.platform}")
    sb.appendLine("Services  : $servicesOk/$servicesTotal OK")
    sb.appendLine()
    sections.forEach { sec ->
        sb.appendLine("[${sec.title}]")
        sec.rows.forEach { r ->
            when (r) {
                is StatusRow -> sb.appendLine(
                    "  ${r.label}: ${if (r.ok) "OK" else "FAIL"}" +
                        if (r.detail.isNotEmpty()) "  (${r.detail})" else ""
                )
                is InfoRow -> sb.appendLine("  ${r.label}: ${r.value}")
                is NodeRow -> sb.appendLine(
                    "  ${if (r.exists) "[OK]      " else "[MISSING] "} ${r.path}"
                )
            }
        }
        sb.appendLine()
    }

    return DiagReport(
        sections = sections.map { DiagSection(it.title, it.icon, it.rows.toList()) },
        reportText = sb.toString(),
        servicesOk = servicesOk,
        servicesTotal = servicesTotal,
        model = profile.model,
        platform = profile.platform,
    )
}
