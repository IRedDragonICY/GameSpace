/*
* Copyright (C) 2026 IRedDragonICY
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.ireddragonicy.gamespace.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import com.ireddragonicy.gamespace.R
import com.ireddragonicy.gamespace.gamebar.readableOn

/**
 * Picker accent untuk seluruh app: dot Material You + preset + custom (HSV inline).
 *
 * [onCustomColor] dipanggil dengan persist=false setiap langkah drag (preview
 * live mulus) dan persist=true saat dilepas — pola yang sama dengan
 * TileRepository.setPanelCustomColor.
 */
@Composable
fun AppAccentPicker(
    mode: Int,
    customArgb: Int,
    onMode: (Int) -> Unit,
    onCustomColor: (Int, Boolean) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(mode == APP_COLOR_MODE_CUSTOM) }
    val context = LocalContext.current
    // Warna asli wallpaper — bukan primary theme aktif, supaya dot "M" tidak
    // ikut berubah saat preset sedang terpilih.
    val materialYouColor = remember { dynamicDarkColorScheme(context).primary }

    Column(Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            AccentDot(
                fill = materialYouColor,
                selected = mode == 0,
                label = "M",
                onClick = { pickerOpen = false; onMode(0) },
            )
            APP_ACCENT_PRESETS.forEachIndexed { index, color ->
                AccentDot(
                    fill = color,
                    selected = mode == index + 1,
                    onClick = { pickerOpen = false; onMode(index + 1) },
                )
            }
            CustomAccentDot(
                argb = customArgb,
                selected = mode == APP_COLOR_MODE_CUSTOM,
                onClick = {
                    onMode(APP_COLOR_MODE_CUSTOM)
                    pickerOpen = !pickerOpen
                },
            )
        }
        AnimatedVisibility(
            visible = pickerOpen && mode == APP_COLOR_MODE_CUSTOM,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(140)) + fadeOut(tween(140)),
        ) {
            AppCustomColorPicker(initialArgb = customArgb, onColor = onCustomColor)
        }
    }
}

@Composable
private fun AccentDot(
    fill: Color,
    selected: Boolean,
    label: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(fill)
            .border(
                width = if (selected) 2.dp else 0.5.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                text = label,
                color = readableOn(fill),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CustomAccentDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(
                        Color.Red, Color.Yellow, Color.Green,
                        Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
                    )
                )
            )
            .border(
                width = if (selected) 2.dp else 0.5.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(17.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(0.5.dp, Color.Black.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_color_vivid),
                contentDescription = null,
                tint = readableOn(Color(argb)),
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

@Composable
private fun AppCustomColorPicker(initialArgb: Int, onColor: (Int, Boolean) -> Unit) {
    val initHsv = remember { FloatArray(3).also { AndroidColor.colorToHSV(initialArgb, it) } }
    var h by remember { mutableFloatStateOf(initHsv[0]) }
    var s by remember { mutableFloatStateOf(initHsv[1]) }
    var v by remember { mutableFloatStateOf(initHsv[2]) }
    var hex by remember { mutableStateOf("#%06X".format(0xFFFFFF and initialArgb)) }

    fun apply(persist: Boolean) {
        val argb = AndroidColor.HSVToColor(floatArrayOf(h, s, v))
        hex = "#%06X".format(0xFFFFFF and argb)
        onColor(argb, persist)
    }

    val hueRainbow = remember {
        Brush.horizontalGradient(
            listOf(
                Color.Red, Color.Yellow, Color.Green,
                Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
            )
        )
    }
    val satBrush = Brush.horizontalGradient(
        listOf(
            Color(AndroidColor.HSVToColor(floatArrayOf(h, 0f, v))),
            Color(AndroidColor.HSVToColor(floatArrayOf(h, 1f, v))),
        )
    )
    val valBrush = Brush.horizontalGradient(
        listOf(
            Color(0xFF000000),
            Color(AndroidColor.HSVToColor(floatArrayOf(h, s, 1f))),
        )
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(AndroidColor.HSVToColor(floatArrayOf(h, s, v))))
                    .border(
                        0.5.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(8.dp),
                    ),
            )
            Spacer(modifier = Modifier.width(10.dp))
            BasicTextField(
                value = hex,
                onValueChange = { new ->
                    hex = new
                    runCatching {
                        AndroidColor.parseColor(if (new.startsWith("#")) new else "#$new")
                    }.getOrNull()?.let { c ->
                        val t = FloatArray(3)
                        AndroidColor.colorToHSV(c, t)
                        h = t[0]; s = t[1]; v = t[2]
                        onColor(c, true)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) { inner() }
                },
            )
        }
        AppGradientSlider(h, 0f..360f, hueRainbow, "H", { h = it; apply(false) }) { apply(true) }
        AppGradientSlider(s, 0f..1f, satBrush, "S", { s = it; apply(false) }) { apply(true) }
        AppGradientSlider(v, 0f..1f, valBrush, "V", { v = it; apply(false) }) { apply(true) }
    }
}

@Composable
private fun AppGradientSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    track: Brush,
    letter: String,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = letter,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(14.dp),
        )
        Box(modifier = Modifier.weight(1f).height(22.dp)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(vertical = 7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(track)
            )
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                onValueChangeFinished = onChangeFinished,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    thumbColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth().height(22.dp),
            )
        }
    }
}
