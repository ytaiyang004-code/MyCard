package com.mycard.wallet.images

import android.content.ContentResolver
import android.graphics.*
import android.net.Uri
import com.mycard.wallet.ui.CARD_RATIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max

data class CropTransform(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)
data class Mask(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun normalized() = Mask(minOf(left, right).coerceIn(0f, 1f), minOf(top, bottom).coerceIn(0f, 1f),
        maxOf(left, right).coerceIn(0f, 1f), maxOf(top, bottom).coerceIn(0f, 1f))
}
object ImageProcessor {
    suspend fun decode(resolver: ContentResolver, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n == -1) break
                require(out.size() + n <= 24 * 1024 * 1024) { "Image too large" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: error("Cannot open image")
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                require(info.mimeType in listOf("image/jpeg", "image/png", "image/webp"))
                require(info.size.width.toLong() * info.size.height <= 100_000_000L)
                val scale = minOf(1f, 3072f / max(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } finally { bytes.fill(0) }
    }
    fun destination(bitmap: Bitmap, width: Float, height: Float, transform: CropTransform): RectF {
        val scale = max(width / bitmap.width, height / bitmap.height) * transform.zoom.coerceIn(1f, 6f)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        val x = ((width - w) / 2 + transform.panX * width).coerceIn(width - w, 0f)
        val y = ((height - h) / 2 + transform.panY * height).coerceIn(height - h, 0f)
        return RectF(x, y, x + w, y + h)
    }
    fun clamp(bitmap: Bitmap, t: CropTransform): CropTransform {
        val rect = destination(bitmap, CARD_RATIO, 1f, t)
        return t.copy(zoom = t.zoom.coerceIn(1f, 6f), panX = (rect.left - (CARD_RATIO - rect.width()) / 2) / CARD_RATIO,
            panY = rect.top - (1f - rect.height()) / 2)
    }
    /** Fresh bitmap + PNG encoding strips metadata and permanently burns black mask pixels. */
    suspend fun render(bitmap: Bitmap, transform: CropTransform, masks: List<Mask>): ByteArray = withContext(Dispatchers.Default) {
        val output = Bitmap.createBitmap(1586, 1000, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, destination(bitmap, 1586f, 1000f, transform), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
            masks.forEach { mask -> val m = mask.normalized()
                canvas.drawRect(kotlin.math.floor(m.left * 1586), kotlin.math.floor(m.top * 1000),
                    kotlin.math.ceil(m.right * 1586), kotlin.math.ceil(m.bottom * 1000), paint)
            }
            ByteArrayOutputStream().use { out -> output.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        } finally { output.recycle() }
    }
    suspend fun trimWhitespace(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        fun blank(color: Int) = Color.alpha(color) < 20 || (Color.red(color) > 242 && Color.green(color) > 242 && Color.blue(color) > 242)
        fun emptyRow(y: Int): Boolean = (0 until bitmap.width step maxOf(1, bitmap.width / 300)).all { blank(bitmap.getPixel(it, y)) }
        fun emptyCol(x: Int, top: Int, bottom: Int): Boolean =
            (top..bottom step maxOf(1, (bottom - top) / 300)).all { blank(bitmap.getPixel(x, it)) }
        var top = 0; var bottom = bitmap.height - 1; var left = 0; var right = bitmap.width - 1
        while (top < bottom && emptyRow(top)) top++
        while (bottom > top && emptyRow(bottom)) bottom--
        while (left < right && emptyCol(left, top, bottom)) left++
        while (right > left && emptyCol(right, top, bottom)) right--
        require(right - left >= 32 && bottom - top >= 20)
        Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }
}
