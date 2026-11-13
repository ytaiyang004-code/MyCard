package com.mycard.wallet

import com.mycard.wallet.ui.AUTO_SCROLL_MAX_FRAME_NANOS
import com.mycard.wallet.ui.autoScrollStep
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * The reorder auto-scroll is defined in pixels per second and scaled by the real frame interval, so
 * the same drag travels the same distance on a 60Hz and a 120Hz screen and a stalled frame cannot
 * produce a jump.
 */
class ReorderScrollTest {
    private val perSecond = 1440f
    private val frame60 = 16_666_667L
    private val frame120 = 8_333_333L

    @Test fun oneSecondOfDraggingTravelsTheSameDistanceAt60And120Hz() {
        val sixty = autoScrollStep(1f, perSecond, frame60) * 60
        val hundredTwenty = autoScrollStep(1f, perSecond, frame120) * 120
        assertEquals(perSecond, sixty, 5f)
        assertEquals(sixty, hundredTwenty, 3f)
    }

    @Test fun aStalledFrameIsClampedInsteadOfJumping() {
        val normal = autoScrollStep(1f, perSecond, AUTO_SCROLL_MAX_FRAME_NANOS)
        val stalled = autoScrollStep(1f, perSecond, 500_000_000L)
        assertEquals(normal, stalled, .01f)
        assertTrue("a stall must stay well under one row", stalled < 80.dpInPixels())
    }

    @Test fun pullDirectionAndDistanceBothChangeTheStep() {
        assertEquals(0f, autoScrollStep(0f, perSecond, frame60), 0f)
        assertEquals(0f, autoScrollStep(1f, perSecond, 0L), 0f)
        assertTrue(autoScrollStep(-1f, perSecond, frame60) < 0f)
        val nearEdge = abs(autoScrollStep(.25f, perSecond, frame60))
        val atEdge = abs(autoScrollStep(1f, perSecond, frame60))
        assertTrue("closer to the edge must scroll faster", atEdge > nearEdge * 3f)
    }

    private fun Int.dpInPixels() = this * 3f
}
