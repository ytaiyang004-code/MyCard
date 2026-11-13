package com.mycard.wallet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import com.mycard.wallet.data.WalletCard
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.ui.theme.cardOutlinePath

@Composable
internal fun LegacyTemplateBackground(card: WalletCard, modifier: Modifier) {
    Canvas(modifier) {
        val main = Color(card.primary)
        val secondary = Color(card.secondary)
        if (card.template == 0 || !card.gradient) drawRect(main)
        else drawRect(Brush.linearGradient(listOf(main, secondary), Offset.Zero, Offset(size.width, size.height * 1.4f)))
        if (card.texture) {
            when (card.template) {
                1 -> {
                    drawLine(Color.White.copy(alpha = .09f), Offset(size.width * .7f, 0f), Offset(size.width * .25f, size.height), 1.5f)
                    drawLine(Color.White.copy(alpha = .08f), Offset(size.width * .9f, 0f), Offset(size.width * .45f, size.height), 1.5f)
                }
                2 -> repeat(7) { i ->
                    drawCircle(Color.White.copy(alpha = .035f + i * .003f), size.width * (.25f + i * .12f),
                        Offset(size.width * 1.05f, size.height * 1.2f), style = Stroke(1.5f))
                }
                3 -> repeat(150) { i ->
                    val y = size.height * i / 150f
                    drawLine(Color.White.copy(alpha = if (i % 3 == 0) .07f else .025f), Offset(0f, y), Offset(size.width, y - 18f), .8f)
                }
                4 -> {
                    drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = .16f), Color.Transparent),
                        Offset(size.width * .8f, 0f), size.width), topLeft = Offset.Zero, size = size)
                    repeat(5) { i -> drawLine(Color.White.copy(alpha = .045f), Offset(size.width * (.4f + i * .14f), 0f),
                        Offset(size.width * (.15f + i * .14f), size.height), 30f) }
                }
                5 -> {
                    val p = Path().apply { moveTo(size.width * .45f, 0f); lineTo(size.width, 0f); lineTo(size.width * .78f, size.height); close() }
                    drawPath(p, Color.White.copy(alpha = .08f))
                    val p2 = Path().apply { moveTo(size.width * .8f, 0f); lineTo(size.width, size.height * .4f); lineTo(size.width, size.height); lineTo(size.width * .5f, size.height); close() }
                    drawPath(p2, Color.Black.copy(alpha = .13f))
                }
            }
        }
        drawPath(cardOutlinePath(size, WalletDesign.cardRadius.toPx()),
            Brush.linearGradient(listOf(WalletDesign.cardEdgeLight, Color.Transparent, WalletDesign.cardEdgeShade),
                Offset.Zero, Offset(size.width, size.height)), style = Stroke(WalletDesign.cardStrokeWidth.toPx()))
    }
}
