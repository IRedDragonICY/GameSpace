/* --- gamespace/gamebar/monitor/MonitorWidgets.kt --- */
package com.ireddragonicy.gamespace.gamebar.monitor

import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.fps.FpsInteractor
import kotlin.math.roundToInt

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
    fontSize = 8.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    lineHeight = 8.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

val CoreTextStyle = TextStyle(
    fontSize = 7.sp,
    fontFamily = FontFamily.Monospace,
    lineHeight = 7.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

val SubLabelTextStyle = TextStyle(
    fontSize = 7.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 7.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
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
        modifier = Modifier
            .pointerInput(isPinned) {
                if (!isPinned) {
                    detectDragGestures(
                        onDragEnd = onDragEnd
                    ) { change, dragAmount ->
                        change.consume()
                        onDragUpdate(dragAmount.x, dragAmount.y)
                    }
                }
            }
    ) {
        content()
    }
}

@Composable
fun ClassicalMonitorWidget(telemetry: MonitorTelemetry, fps: FpsInteractor, settings: MonitorSettings) {
    var expanded by remember { mutableStateOf(false) }

    val cpu by telemetry.cpuUsage.collectAsState()
    val gpu by telemetry.gpuUsage.collectAsState()
    val cpuF by telemetry.cpuFreq.collectAsState()
    val gpuF by telemetry.gpuFreq.collectAsState()
    
    val currentFps by fps.fpsHistory.collectAsState(initial = emptyList())
    val fpsVal = currentFps.lastOrNull() ?: 0f
    
    val cTemp by telemetry.cpuTemp.collectAsState()
    val ramGb by telemetry.ramUsedGb.collectAsState()
    val pwr by telemetry.powerWatt.collectAsState()
    
    val f0 by telemetry.freqC0.collectAsState()
    val f1 by telemetry.freqC1.collectAsState()
    val f2 by telemetry.freqC2.collectAsState()
    val f3 by telemetry.freqC3.collectAsState()
    
    val coreUsages by telemetry.coreUsages.collectAsState()
    val coreFreqs by telemetry.coreFreqs.collectAsState()
    val ddr by telemetry.ddrFreq.collectAsState()
    val batPct by telemetry.batteryPct.collectAsState()
    val bTemp by telemetry.batteryTemp.collectAsState()

    Row(
        modifier = Modifier
            .background(MonBg.copy(alpha = settings.opacity), RoundedCornerShape(8.dp))
            .border(1.dp, MonBorder.copy(alpha = settings.opacity), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(4.dp), // A very slight edge padding to not clip the background bounds
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!expanded) {
            if (settings.classicalShowCpu) CircularGauge("CPU", cpu, subLabel = "${cpuF}MHz")
            if (settings.classicalShowGpu) CircularGauge("GPU", gpu, subLabel = "${gpuF}MHz")
            if (settings.classicalShowTemp) CircularGauge("${batPct}%+", batPct.toFloat(), subLabel = "%.1f°C".format(bTemp))
        } else {
            // Arcs Section
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (settings.classicalShowCpu) CircularGauge("CPU", cpu, subLabel = "${cpuF}MHz")
                if (settings.classicalShowGpu) CircularGauge("GPU", gpu, subLabel = "${gpuF}MHz")
            }
            
            // Ram & Pwr
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

            // Data List Section
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
fun MiniMonitorWidget(telemetry: MonitorTelemetry, settings: MonitorSettings) {
    val cpuF by telemetry.cpuFreq.collectAsState()
    val gpuF by telemetry.gpuFreq.collectAsState()
    val ramGb by telemetry.ramUsedGb.collectAsState()
    val pwr by telemetry.powerWatt.collectAsState()

    Row(
        modifier = Modifier
            .background(MonBg.copy(alpha = settings.opacity), RoundedCornerShape(12.dp))
            .border(1.dp, MonBorder.copy(alpha = settings.opacity), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // CPU Freq
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CPU", color = MonCyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(4.dp))
            Text(cpuF.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        // GPU Freq
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GPU", color = MonYellow, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(4.dp))
            Text(gpuF.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        // RAM GB
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_memory), null, tint = MonGreen, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("%.1fGB".format(ramGb), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        // Power Watt
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_bolt), null, tint = MonRed, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text("%.2fW".format(pwr), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun ProcessesMonitorWidget(telemetry: MonitorTelemetry, settings: MonitorSettings) {
    val processes by telemetry.topProcesses.collectAsState()

    Column(
        modifier = Modifier
            .width(200.dp)
            .background(MonBg.copy(alpha = settings.opacity), RoundedCornerShape(12.dp))
            .border(1.dp, MonBorder.copy(alpha = settings.opacity), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_memory), null, tint = Color.Gray, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Processes", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        
        if (processes.isEmpty()) {
            Text("Loading...", color = Color.Gray, fontSize = 10.sp)
        } else {
            processes.forEach { proc ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(proc.name.take(15), color = Color.White, fontSize = 11.sp, maxLines = 1)
                    Text("%.1f%%".format(proc.usagePercent), color = MonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun TempMonitorWidget(telemetry: MonitorTelemetry, settings: MonitorSettings) {
    val cTemp by telemetry.cpuTemp.collectAsState()
    val gTemp by telemetry.gpuTemp.collectAsState()
    val bTemp by telemetry.batteryTemp.collectAsState()

    Column(
        modifier = Modifier
            .background(MonBg.copy(alpha = settings.opacity), RoundedCornerShape(8.dp))
            .border(1.dp, MonBorder.copy(alpha = settings.opacity), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        DataTextRow("# BAT", "%.1f°C".format(bTemp))
        DataTextRow("# CPU", "%.1f°C".format(cTemp))
        DataTextRow("# GPU", "%.1f°C".format(gTemp))
    }
}

@Composable
fun CircularGauge(label: String, percentage: Float, isFps: Boolean = false, subLabel: String? = null) {
    val animatedPct by animateFloatAsState(targetValue = percentage.coerceIn(0f, 100f), animationSpec = tween(400), label = "pct")
    
    val ringColor by animateColorAsState(
        targetValue = when {
            isFps -> if (animatedPct > 80f) MonGreen else MonYellow
            animatedPct < 50f -> MonGreen
            animatedPct < 85f -> MonYellow
            else -> MonRed
        }, animationSpec = tween(400), label = "color"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) {
            Canvas(modifier = Modifier.size(30.dp)) {
                drawArc(
                    color = Color.DarkGray,
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f, sweepAngle = 360f * (animatedPct / 100f), useCenter = false,
                    style = Stroke(width = 4f, cap = StrokeCap.Round)
                )
            }
            Text(label, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }
        if (subLabel != null) {
            Text(subLabel, color = Color.White, style = SubLabelTextStyle)
        }
    }
}

@Composable
fun DataTextRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(68.dp)) {
        Text(label, color = Color.White, style = DataTextStyle)
        Text(value, color = Color.White, style = DataTextStyle)
    }
}

@Composable
fun CoreRow(usage: Float, freq: Int) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(68.dp).padding(start = 14.dp, end = 0.dp)) {
        Text("${usage.toInt()}%", color = Color.LightGray, style = CoreTextStyle)
        Text("${freq}M", color = Color.LightGray, style = CoreTextStyle)
    }
}
