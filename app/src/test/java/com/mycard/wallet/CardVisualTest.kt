package com.mycard.wallet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mycard.wallet.ui.theme.WalletDesign
import com.mycard.wallet.ui.theme.cardOutlinePath
import com.mycard.wallet.ui.theme.continuousCornerSample
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Geometry of the card outline. These tests keep the continuous corner honest: they check the
 * superellipse formula, keep the path inside the card bounds and measure how much more of the
 * corner box the outline covers compared with the previous circular corner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun continuousCornerFollowsTheSuperellipseAndCutsCloserThanACircle() {
        val radius = 22f
        val (x, y) = continuousCornerSample(PI.toFloat() / 4f, radius)
        val expected = radius * 2f.pow(-1f / 4f)
        assertEquals(expected, x, .05f)
        assertEquals(expected, y, .05f)
        val circle = radius / sqrt(2f)
        assertTrue("continuous corner must reach further towards the corner than an arc", x > circle + 2f)
    }

    @Test fun cardOutlineStaysInBoundsAndClosesOnItself() {
        val size = Size(300f, 189f)
        val path = cardOutlinePath(size, 22f)
        val bounds = path.getBounds()
        assertTrue(bounds.left >= -.01f && bounds.top >= -.01f)
        assertTrue(bounds.right <= size.width + .01f && bounds.bottom <= size.height + .01f)
        val measure = PathMeasure().apply { setPath(path, false) }
        val start = measure.getPosition(0f)
        val end = measure.getPosition(measure.length)
        assertEquals(start.x, end.x, .5f)
        assertEquals(start.y, end.y, .5f)
    }

    @Test fun continuousCornerCoversMoreOfTheCornerBoxThanARoundedCorner() {
        val card = WalletDesign.cardRadius * 4
        compose.setContent { Surface(color = Color.White) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(card).testTag("rounded").clip(RoundedCornerShape(WalletDesign.cardRadius)).background(Color(0xFFB10A32)))
                Box(Modifier.size(card).testTag("continuous").clip(WalletDesign.cardShape).background(Color(0xFFB10A32)))
            }
        } }
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            File("build/ui-previews/phase1-corners.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val radiusPx = with(compose.density) { WalletDesign.cardRadius.toPx() }
        fun cornerFill(tag: String): Int {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            var inside = 0
            for (y in bounds.top.toInt() until (bounds.top + radiusPx).toInt()) {
                for (x in bounds.left.toInt() until (bounds.left + radiusPx).toInt()) {
                    if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) inside++
                }
            }
            return inside
        }
        val rounded = cornerFill("rounded")
        val continuous = cornerFill("continuous")
        println("corner box fill: rounded=$rounded continuous=$continuous (radius ${radiusPx}px)")
        assertTrue("continuous corners must fill the corner box more fully than arcs ($continuous vs $rounded)", continuous > rounded)
    }
}
