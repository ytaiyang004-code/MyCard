package com.mycard.wallet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ColorSelector(label: String, selected: Long, onSelect: (Long) -> Unit, preview: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TemplateCatalog.swatches.take(4).forEach { color ->
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(color)).clickable { onSelect(color) }
                .semantics { contentDescription = "$label 推荐颜色 ${String.format("%06X", color and 0xFFFFFF)}" }
                .border(if (color == selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape))
        }
        OutlinedButton({ open = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("调色") }
    }
    if (open && LocalWalletVisible.current) Dialog(onDismissRequest = { open = false }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().heightIn(max = 740.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                preview()
                ColorBoard(selected, onSelect)
                Text("推荐配色", style = MaterialTheme.typography.labelLarge)
                TemplateCatalog.swatches.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { color -> Box(Modifier.size(38.dp).clip(CircleShape).background(Color(color)).clickable { onSelect(color) }
                            .semantics { contentDescription = "推荐颜色 ${String.format("%06X", color and 0xFFFFFF)}" }) }
                    }
                }
                var advanced by remember { mutableStateOf(false) }
                TextButton({ advanced = !advanced }) { Text(if (advanced) "收起高级选项" else "高级：十六进制颜色") }
                if (advanced) {
                    var hex by remember(selected) { mutableStateOf(String.format("%06X", selected and 0xFFFFFF)) }
                    OutlinedTextField(hex, { value ->
                        hex = value.filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }.take(6)
                        if (hex.length == 6) onSelect(0xFF000000L or hex.toLong(16))
                    }, label = { Text("#RRGGBB") }, singleLine = true, isError = hex.length != 6)
                }
                TextButton({ open = false }, Modifier.fillMaxWidth()) { Text("完成") }
            }
        }
    }
}

@Composable
fun ColorBoard(selected: Long, onSelect: (Long) -> Unit) {
    // Hue is independent of RGB round trips so dragging through black/gray never resets hue to red.
    val initial = remember { ColorMath.fromArgb(selected) }
    var hue by remember { mutableFloatStateOf(initial.hue) }
    val rgbHsv = ColorMath.fromArgb(selected)
    LaunchedEffect(selected) { if (rgbHsv.saturation > .001f && rgbHsv.value > .001f) hue = rgbHsv.hue }
    val hsv = rgbHsv.copy(hue = hue)
    val onColor by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(hsv)
    fun choose(point: Offset, width: Float, height: Float) {
        onColor(ColorMath.toArgb(current.copy(saturation = (point.x / width).coerceIn(0f, 1f), value = (1 - point.y / height).coerceIn(0f, 1f))))
    }
    Canvas(Modifier.fillMaxWidth().height(166.dp).clip(RoundedCornerShape(12.dp)).testTag("color-board")
        .semantics { contentDescription = "颜色画板，横向调节饱和度，纵向调节明度" }
        .pointerInput(Unit) { detectTapGestures { choose(it, size.width.toFloat(), size.height.toFloat()) } }
        .pointerInput(Unit) { detectDragGestures(onDragStart = { choose(it, size.width.toFloat(), size.height.toFloat()) }) { change, _ ->
            change.consume(); choose(change.position, size.width.toFloat(), size.height.toFloat())
        } }) {
        drawRect(Color(ColorMath.toArgb(HsvColor(hue, 1f, 1f))))
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val marker = Offset(hsv.saturation * size.width, (1 - hsv.value) * size.height)
        drawCircle(Color.Black.copy(alpha = .7f), 8.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
    }
    Text("色相", style = MaterialTheme.typography.labelMedium)
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Canvas(Modifier.fillMaxWidth().height(20.dp).padding(top = 6.dp)) {
            drawRoundRect(Brush.horizontalGradient((0..6).map { Color(ColorMath.toArgb(HsvColor(it * 60f, 1f, 1f))) }),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        }
        Slider(hue, { hue = it; onSelect(ColorMath.toArgb(hsv.copy(hue = it))) }, valueRange = 0f..359.9f,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "色相" },
            colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
    }
    Text("饱和度 ${(hsv.saturation * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
    Slider(hsv.saturation, { onSelect(ColorMath.toArgb(hsv.copy(saturation = it))) },
        modifier = Modifier.semantics { contentDescription = "饱和度" })
    Text("明度 ${(hsv.value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
    Slider(hsv.value, { onSelect(ColorMath.toArgb(hsv.copy(value = it))) },
        modifier = Modifier.semantics { contentDescription = "明度" })
}
