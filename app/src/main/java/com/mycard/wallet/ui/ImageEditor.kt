package com.mycard.wallet.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mycard.wallet.*
import com.mycard.wallet.images.*

@Composable
fun ImageImportSection(vm: WalletViewModel) {
    val draft by vm.draft.collectAsState()
    val bitmap = vm.importBitmap.value
    val working by vm.importWorking
    var pickerSession by rememberSaveable { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && pickerSession == vm.draftSession) vm.loadImage(uri)
        pickerSession = null
    }
    OutlinedButton({
        try {
            pickerSession = vm.draftSession
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        catch (_: Exception) { pickerSession = null; vm.runTask { vm.notices.send(Notice("无法打开系统图片选择器，请检查系统文件应用是否可用。")) } }
    },
        Modifier.fillMaxWidth(), enabled = !vm.busy.collectAsState().value && !working) {
        Text(if (draft?.imagePath != null || vm.draftImage.value != null) Copy.replace else Copy.import)
    }
    Text(Copy.imageHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (bitmap != null && LocalWalletVisible.current) {
        var transform by vm.importTransform
        var masks by vm.importMasks
        var masking by vm.importMaskMode
        var currentMask by remember(bitmap) { mutableStateOf<Mask?>(null) }
        var size by remember { mutableStateOf(IntSize(1, 1)) }
        Dialog(onDismissRequest = vm::releaseImport,
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("处理卡面", style = MaterialTheme.typography.headlineSmall)
                    Text(if (masking) "在卡面上拖动，画出要永久遮挡的区域。"
                        else "双指缩放，单指平移。画框固定为银行卡标准比例。", style = MaterialTheme.typography.bodyMedium)
                    Canvas(Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).onSizeChanged { size = it }
                        .pointerInput(masking, bitmap, masks.size, working) {
                            if (working) return@pointerInput
                            if (masking) detectDragGestures(
                                onDragStart = { start -> currentMask = Mask(start.x / size.width, start.y / size.height, start.x / size.width, start.y / size.height) },
                                onDrag = { change, _ -> change.consume()
                                    currentMask = currentMask?.copy(right = change.position.x / size.width, bottom = change.position.y / size.height) },
                                onDragEnd = { currentMask?.normalized()?.let { if (it.right - it.left > .005f && it.bottom - it.top > .005f) masks = masks + it }; currentMask = null },
                                onDragCancel = { currentMask = null }
                            ) else detectTransformGestures { _, pan, zoom, _ ->
                                if (masks.isEmpty()) transform = ImageProcessor.clamp(bitmap, transform.copy(
                                    zoom = transform.zoom * zoom, panX = transform.panX + pan.x / size.width, panY = transform.panY + pan.y / size.height))
                            }
                        }) {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            val checkpoint = native.save()
                            native.clipRect(0f, 0f, this.size.width, this.size.height)
                            native.drawColor(android.graphics.Color.BLACK)
                            native.drawBitmap(bitmap, null, ImageProcessor.destination(bitmap, this.size.width, this.size.height, transform),
                                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                            val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
                            (masks + listOfNotNull(currentMask)).forEach { mask ->
                                val m = mask.normalized()
                                native.drawRect(m.left * this.size.width, m.top * this.size.height, m.right * this.size.width, m.bottom * this.size.height, paint)
                            }
                            native.restoreToCount(checkpoint)
                        }
                    }
                    if (!masking) {
                        Text("缩放 ${"%.1f".format(transform.zoom)}×")
                        Slider(transform.zoom, { if (masks.isEmpty()) transform = ImageProcessor.clamp(bitmap, transform.copy(zoom = it)) },
                            valueRange = 1f..6f, enabled = masks.isEmpty() && !working)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!masking, { masking = false }, enabled = !working, label = { Text("裁剪与缩放") })
                        FilterChip(masking, { masking = true }, enabled = !working, label = { Text("遮挡敏感区域") })
                    }
                    if (masks.isNotEmpty()) Text("已有遮挡时锁定裁剪位置。清除遮挡后可重新调整。保存后遮挡无法恢复。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(vm::trimImage, enabled = masks.isEmpty() && !working) { Text("去除白色外围") }
                        TextButton({ masks = emptyList(); currentMask = null }, enabled = !working) { Text("清除遮挡") }
                    }
                    Text("请遮挡原图中的完整卡号、姓名等敏感信息。输出不保留 EXIF；只有处理后的图片会加密保存。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    vm.importError.value?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(vm::acceptImage, Modifier.fillMaxWidth(), enabled = !working) {
                        if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("使用处理后的卡面")
                    }
                    TextButton(vm::releaseImport, Modifier.fillMaxWidth()) { Text(Copy.cancel) }
                }
            }
        }
    }
}
