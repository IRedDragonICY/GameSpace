package com.ireddragonicy.gamespace.gamebar.monitor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R

// Base Colors
val MonBg = Color(0xFF161616)
val MonBorder = Color(0xFF2E2E2E)
val MonGreen = Color(0xFF76FF03)
val MonYellow = Color(0xFFFFD600)
val MonRed = Color(0xFFFF1744)
val MonCyan = Color(0xFF00E5FF)
val MonLabel = Color.White.copy(alpha = 0.5f)
val MonText = Color.White

val DataTextStyle = TextStyle(
    fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
    lineHeight = 8.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both
    )
)
val CoreTextStyle = TextStyle(
    fontSize = 7.sp, fontFamily = FontFamily.Monospace, lineHeight = 7.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both
    )
)
val SubLabelTextStyle = TextStyle(
    fontSize = 7.sp, fontWeight = FontWeight.Bold, lineHeight = 7.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both
    )
)

@Composable
fun DraggableWidget(
    isPinned: Boolean,
    onDragUpdate: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.pointerInput(isPinned) {
            if (!isPinned) {
                detectDragGestures(onDragEnd = onDragEnd) { change, dragAmount ->
                    change.consume()
                    onDragUpdate(dragAmount.x, dragAmount.y)
                }
            }
        }
    ) { content() }
}

/** A3: one snapshot collect; only rendered fields are read. */
@Composable
fun ClassicalMonitorWidget(source: MonitorDataSource, settings: MonitorSettings) {
    val s by source.snapshot.collectAsState()
    val currentFps by source.fpsHistory.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    val cpu = s.cpuUsageTotal.toFloat()
    val gpu = s.gpuUsage.toFloat()
    val cpuF = (s.cpuFreqMaxKhz / 1000L).toInt()
    val gpuF = s.gpuFreqMhz
    val fpsVal = currentFps.lastOrNull() ?: 0f
    val cTemp = s.cpuTempC
    val ramGb = s.ramUsedGb
    val pwr = s.batteryPowerW
    val f0 = s.cpuFreqPerClusterMhz.getOrElse(0) { 0 }
    val f1 = s.cpuFreqPerClusterMhz.getOrElse(1) { 0 }
    val f2 = s.cpuFreqPerClusterMhz.getOrElse(2) { 0 }
    val f3 = s.cpuFreqPerClusterMhz.getOrElse(3) { 0 }
    val coreUsages = s.cpuCoreUsage
    val coreFreqs = remember(s.cpuCoreFreqKhz) { s.cpuCoreFreqKhz.map { (it / 1000L).toInt() } }
    val batPct = s.batteryCapacity
    val bTemp = s.batteryTempC

    Row(
        modifier = Modifier
            .background(MonBg.copy(alpha = settings.opacity), RoundedCornerShape(8.dp))
            .border(1.dp, MonBorder.copy(alpha = settings.opacity), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!expanded) {
            if (settings.classicalShowCpu) CircularGauge("CPU", cpu, subLabel = "${cpuF}MHz")
            if (settings.classicalShowGpu) CircularGauge("GPU", gpu, subLabel = "${gpuF}MHz")
            if (settings.classicalShowTemp)
                CircularGauge("${batPct}%+", batPct.toFloat(), subLabel = "%.1f°C".format(bTemp))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (settings.classicalShowCpu) CircularGauge("CPU", cpu, subLabel = "${cpuF}MHz")
                if (settings.classicalShowGpu) CircularGauge("GPU", gpu, subLabel = "${gpuF}MHz")
                if (settings.classicalShowTemp)
                    CircularGauge("${batPct}%+", batPct.toFloat(), subLabel = "%.1f°C".format(bTemp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.width(36.dp)) {
                if (settings.classicalShowFps) {
                    Text(text = "FPS", color = MonLabel, style = SubLabelTextStyle)
                    Text(text = "${fpsVal.toInt()}", color = MonText, style = CoreTextStyle)
                }
                if (settings.classicalShowTemp) {
                    Text(text = "SYS", color = MonLabel, style = SubLabelTextStyle)
                    Text(text = "%.1f°C".format(cTemp), color = MonText, style = CoreTextStyle)
                }
                if (settings.classicalShowRam) {
                    Text(text = "RAM", color = MonLabel, style = SubLabelTextStyle)
                    Text(text = "%.1fG".format(ramGb), color = MonText, style = CoreTextStyle)
                }
                if (settings.classicalShowTemp) {
                    Text(text = "PWR", color = MonLabel, style = SubLabelTextStyle)
                    Text(text = "%.1fW".format(pwr), color = MonText, style = CoreTextStyle)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                DataTextRow("#CPU", "%.1f°C".format(cTemp))
                DataTextRow("#0~1", "${f0}MHz")
                CoreRow(coreUsages.getOrElse(0) { 0f }, coreFreqs.getOrElse(0) { 0 })
                CoreRow(coreUsages.getOrElse(1) { 0f }, coreFreqs.getOrElse(1) { 0 })
                DataTextRow("#2~4", "${f1}MHz")
                CoreRow(coreUsages.getOrElse(2) { 0f }, coreFreqs.getOrElse(2) { 0 })
                CoreRow(coreUsages.getOrElse(3) { 0f }, coreFreqs.getOrElse(3) { 0 })
                CoreRow(coreUsages.getOrElse(4) { 0f }, coreFreqs.getOrElse(4) { 0 })
                DataTextRow("#5~6", "${f2}MHz")
                CoreRow(coreUsages.getOrElse(5) { 0f }, coreFreqs.getOrElse(5) { 0 })
                CoreRow(coreUsages.getOrElse(6) { 0f }, coreFreqs.getOrElse(6) { 0 })
                DataTextRow("#7~7", "${f3}MHz")
                CoreRow(coreUsages.getOrElse(7) { 0f }, coreFreqs.getOrElse(7) { 0 })
                DataTextRow("#FPS", "%.1f".format(fpsVal))
                DataTextRow("#PWR", "%.2fW".format(pwr))
            }
        }
    }
}

@Composable
fun MiniMonitorWidget(source: MonitorDataSource, settings: MonitorSettings) {
    val s by source.snapshot.collectAsState()
    val cpuF = (s.cpuFreqMaxKhz / 1000L).toInt()
    val gpuF = s.gpuFreqMhz
    val ramGb = s.ramUsedGb
    val pwr = s.batteryPowerW

    Row(
        modifier = Modifier
            .background(MonBg.copy(alpha = settings.opacity), RoundedCornerShape(12.dp))
            .border(1.dp, MonBorder.copy(alpha = settings.opacity), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CPU", color = MonCyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(4.dp))
            Text(cpuF.toString(), color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GPU", color = MonYellow, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(4.dp))
            Text(gpuF.toString(), color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_memory), null, tint = MonGreen, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("%.1fGB".format(ramGb), color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_bolt), null, tint = MonRed, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("%.2fW".format(pwr), color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

private data class ProcessDisplayItem(
    val rawName: String,
    val displayName: String,
    val iconBitmap: ImageBitmap?,
    val cpuUsage: Float,
    val isApp: Boolean,
)

@Composable
fun ProcessesMonitorWidget(source: MonitorDataSource, settings: MonitorSettings) {
    val rawProcesses by source.topProcesses.collectAsState()
    var filterMode by remember { mutableIntStateOf(0) } // 0 = Processes (All), 1 = Apps
    val iconCache = com.ireddragonicy.gamespace.utils.rememberAppIconCache()

    val resolvedList = remember(rawProcesses) {
        rawProcesses.map { proc ->
            ProcessDisplayItem(
                rawName = proc.name,
                displayName = iconCache.label(proc.name),
                iconBitmap = iconCache.icon(proc.name, 48),
                cpuUsage = proc.usagePercent,
                isApp = iconCache.isApp(proc.name),
            )
        }
    }
    val displayList = remember(resolvedList, filterMode) {
        if (filterMode == 1) resolvedList.filter { it.isApp } else resolvedList
    }

    Column(
        modifier = Modifier
            .width(230.dp)
            .background(MonBg.copy(alpha = settings.opacity), RoundedCornerShape(14.dp))
            .border(1.dp, MonBorder.copy(alpha = settings.opacity), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_memory), null,
                    tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (filterMode == 0) "Processes" else "Apps",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Processes", fontSize = 10.sp,
                    fontWeight = if (filterMode == 0) FontWeight.Bold else FontWeight.Normal,
                    color = if (filterMode == 0) Color.White else Color.Gray,
                    modifier = Modifier.clickable { filterMode = 0 }.padding(horizontal = 2.dp, vertical = 2.dp))
                Text("|", fontSize = 10.sp, color = Color.Gray.copy(alpha = 0.5f))
                Text("Apps", fontSize = 10.sp,
                    fontWeight = if (filterMode == 1) FontWeight.Bold else FontWeight.Normal,
                    color = if (filterMode == 1) Color.White else Color.Gray,
                    modifier = Modifier.clickable { filterMode = 1 }.padding(horizontal = 2.dp, vertical = 2.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (displayList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                Text(if (rawProcesses.isEmpty()) "Loading..." else "No active apps",
                    color = Color.Gray, fontSize = 10.sp)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                displayList.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                            if (item.iconBitmap != null) {
                                Image(item.iconBitmap, item.displayName, modifier = Modifier.size(18.dp))
                            } else {
                                Box(modifier = Modifier.size(18.dp)
                                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_memory), null,
                                        tint = Color.Gray, modifier = Modifier.size(11.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(item.displayName, color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("%.1f%%".format(item.cpuUsage),
                            color = if (item.cpuUsage > 50f) MonRed
                            else if (item.cpuUsage > 20f) MonYellow else Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun TempMonitorWidget(source: MonitorDataSource, settings: MonitorSettings) {
    val s by source.snapshot.collectAsState()
    Column(
        modifier = Modifier
            .background(MonBg.copy(alpha = settings.opacity), RoundedCornerShape(8.dp))
            .border(1.dp, MonBorder.copy(alpha = settings.opacity), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        DataTextRow("# BAT", "%.1f°C".format(s.batteryTempC))
        DataTextRow("# CPU", "%.1f°C".format(s.cpuTempC))
        DataTextRow("# GPU", "%.1f°C".format(s.gpuTempC))
    }
}

@Composable
fun CircularGauge(label: String, percentage: Float, isFps: Boolean = false, subLabel: String? = null) {
    val animatedPct by animateFloatAsState(targetValue = percentage.coerceIn(0f, 100f),
        animationSpec = tween(400), label = "pct")
    val ringColor by animateColorAsState(
        targetValue = when {
            isFps -> if (animatedPct > 80f) MonGreen else MonYellow
            animatedPct < 50f -> MonGreen
            animatedPct < 85f -> MonYellow
            else -> MonRed
        }, animationSpec = tween(400), label = "color"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) {
            Canvas(modifier = Modifier.size(30.dp)) {
                drawArc(color = Color.DarkGray, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    style = Stroke(width = 4f, cap = StrokeCap.Round))
                drawArc(color = ringColor, startAngle = -90f,
                    sweepAngle = 360f * (animatedPct / 100f), useCenter = false,
                    style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
            Text(label, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }
        if (subLabel != null) Text(subLabel, color = Color.White, style = SubLabelTextStyle)
    }
}

@Composable
fun DataTextRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(68.dp)) {
        Text(label, color = Color.White, style = DataTextStyle)
        Text(value, color = Color.White, style = DataTextStyle)
    }
}

@Composable
fun CoreRow(usage: Float, freq: Int) {
    Row(horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.width(68.dp).padding(start = 14.dp, end = 0.dp)) {
        Text("${usage.toInt()}%", color = Color.LightGray, style = CoreTextStyle)
        Text("${freq}M", color = Color.LightGray, style = CoreTextStyle)
    }
}