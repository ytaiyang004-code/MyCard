package com.mycard.wallet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin
import kotlin.random.Random
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.ui.theme.cardOutlinePath

/** Geometry and brushes are cached per material/size, never regenerated during scroll frames. */
@Composable
internal fun MaterialBackground(card: com.mycard.wallet.data.WalletCard, modifier: Modifier) {
    if (card.template < 6) { LegacyTemplateBackground(card, modifier); return }
    Box(modifier.drawWithCache {
        val primary = Color(card.primary)
        val secondary = Color(card.secondary)
        val base = Brush.linearGradient(listOf(primary, if (card.template == 6) lerp(primary, secondary, .18f) else secondary),
            Offset.Zero, Offset(size.width, size.height * 1.3f))
        val glow = Brush.radialGradient(listOf(Color.White.copy(alpha = .20f), Color.Transparent),
            Offset(size.width * .72f, -size.height * .15f), size.width * .85f)
        val random = Random(2900 + card.template)
        val specks = if (card.texture && card.template in setOf(7, 12)) List(110) { Triple(Offset(random.nextFloat() * size.width, random.nextFloat() * size.height),
            random.nextFloat() * .85f + .25f, random.nextFloat() * .11f + .025f) } else emptyList()
        val ribbonCount = if (!card.texture) 0 else when(card.template) { 6 -> 2; 10, 13 -> 9; 15 -> 3; else -> 0 }
        val ribbons = List(ribbonCount) { index ->
            val y = size.height * (.10f + index * .105f)
            Path().apply {
                moveTo(-size.width * .15f, y)
                cubicTo(size.width * .20f, y - size.height * .50f, size.width * .45f, y + size.height * .70f, size.width * 1.15f, y - size.height * .08f)
            }
        }
        val sheen = Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .18f), Color.Transparent),
            Offset(size.width * .20f, 0f), Offset(size.width * .82f, size.height))
        // Edge light: the outline is the same path the card is clipped with, lit from the top-left.
        val edge = Brush.linearGradient(listOf(WalletDesign.cardEdgeLight, Color.Transparent, WalletDesign.cardEdgeShade),
            Offset.Zero, Offset(size.width, size.height))
        // A small repeated weave has a bounded number of strokes regardless of the display size.
        val carbon = if (card.template == 11) carbonBrush() else null
        val starGlow = if (card.template == 12 && card.texture) Brush.radialGradient(
            listOf(secondary.copy(alpha = .20f), Color.Transparent), Offset(size.width * .8f, size.height * .35f), size.width * .55f) else null
        onDrawBehind {
            if (card.gradient) drawRect(base) else drawRect(primary)
            if (card.texture) when (card.template) {
                6 -> { // Obsidian: a restrained gold rim with a single soft facet.
                    drawRect(glow, alpha = .35f)
                    ribbons.forEach { drawPath(it, secondary.copy(alpha = .25f), style = Stroke(1.1f)) }
                    drawLine(secondary.copy(alpha = .55f), Offset(size.width * .06f, size.height * .83f), Offset(size.width * .94f, size.height * .83f), .8f)
                }
                7 -> { drawRect(glow, alpha = .35f); specks.forEach { (p, r, a) -> drawCircle(Color.White.copy(alpha = a * .7f), r, p) } }
                8, 9 -> {
                    drawRect(sheen)
                    repeat(96) { i ->
                        val y = size.height * i / 96f
                        drawLine(Color.White.copy(alpha = if (i % 4 == 0) .105f else .035f), Offset(0f, y), Offset(size.width, y - size.height * .08f), .65f)
                    }
                    drawRect(glow, alpha = .65f)
                }
                10 -> ribbons.forEachIndexed { i, path ->
                    drawPath(path, (if (i % 2 == 0) secondary else Color(0xFFA7ACF5)).copy(alpha = .10f), style = Stroke(16f + i * 2f))
                    drawPath(path, Color.White.copy(alpha = .035f), style = Stroke(1.2f))
                }
                11 -> { carbon?.let { drawRect(it) }; drawRect(glow, alpha = .35f) }
                12 -> {
                    specks.forEach { (p, r, a) -> drawCircle(Color.White.copy(alpha = a * 2.5f), r * 1.2f, p) }
                    starGlow?.let { drawCircle(it, size.width * .55f, Offset(size.width * .8f, size.height * .35f)) }
                }
                13 -> ribbons.forEachIndexed { i, path ->
                    drawPath(path, Color.White.copy(alpha = .025f + .018f * sin(i.toFloat()).let { it * it }), style = Stroke(8f + i * 2))
                    drawPath(path, Color.Black.copy(alpha = .035f), style = Stroke(1f))
                }
                14 -> { drawRect(glow); drawRect(sheen, alpha = .35f) }
                15 -> { drawRect(glow); ribbons.forEach { drawPath(it, Color.White.copy(alpha = .025f), style = Stroke(35f)) } }
            }
            drawPath(cardOutlinePath(size, WalletDesign.cardRadius.toPx()), edge, style = Stroke(WalletDesign.cardStrokeWidth.toPx()))
        }
    })
}

private val carbonTile by lazy {
    val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint()
    for (row in 0..3) for (col in 0..3) {
        paint.color = if ((row + col) % 2 == 0) 0x183F4F59 else 0x26000000
        canvas.drawRect(col * 8f, row * 8f, col * 8f + 8, row * 8f + 8, paint)
        paint.color = 0x15FFFFFF
        repeat(3) { n ->
            if ((row + col) % 2 == 0) canvas.drawLine(col * 8f + n * 2 + 1, row * 8f, col * 8f + n * 2 + 1, row * 8f + 7, paint)
            else canvas.drawLine(col * 8f, row * 8f + n * 2 + 1, col * 8f + 7, row * 8f + n * 2 + 1, paint)
        }
    }
    bitmap.asImageBitmap()
}
private fun carbonBrush(): Brush = ShaderBrush(ImageShader(carbonTile, TileMode.Repeated, TileMode.Repeated))
