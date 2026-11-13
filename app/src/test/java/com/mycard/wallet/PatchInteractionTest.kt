package com.mycard.wallet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.*
import com.mycard.wallet.ui.theme.MyCardTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PatchInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<String>()
    private fun home(count: Int, animations: Boolean = false, stacked: Boolean = true): List<WalletCard> {
        val app = RuntimeEnvironment.getApplication()
        val vm = WalletViewModel(app, CardRepository(app, TestVault()))
        val cards = (0 until count).map { WalletCard(bank = "测试银行$it", position = it.toLong()) }
        compose.setContent { MyCardTheme { HomeScreen(vm, cards, WalletSettings(stacked = stacked, animations = animations), {}, { opened += it }, {}, {}) } }
        return cards
    }
    private fun selectThenOpen(cards: List<WalletCard>, index: Int) {
        compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(index)
        val node = compose.onNodeWithTag("wallet-card-${cards[index].id}")
        node.assertIsNotSelected().performClick().assertIsSelected()
        compose.runOnIdle { assertTrue("First tap must select, never navigate", opened.isEmpty()) }
        node.performClick()
        compose.runOnIdle { assertEquals(listOf(cards[index].id), opened) }
    }
    @Test fun firstCardSelectsBeforeOpening() = selectThenOpen(home(9), 0)
    @Test fun middleCardSelectsBeforeOpening() = selectThenOpen(home(9), 4)
    @Test fun lastCardSelectsBeforeOpening() = selectThenOpen(home(9), 8)
    @Test fun singleCardSelectsBeforeOpening() = selectThenOpen(home(1), 0)
    @Test fun selectingLastAndReturningToFirstKeepsOneSelectionAndScrollsFullCardIntoView() {
        val cards = home(20, animations = true)
        for (index in listOf(0, 10, 19, 0)) {
            compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(index)
            val node = compose.onNodeWithTag("wallet-card-${cards[index].id}")
            node.performClick().assertIsSelected()
            compose.waitForIdle()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).fetchSemanticsNode().boundsInRoot
            assertTrue("Selected card top must be visible: $bounds in $viewport", bounds.top >= viewport.top - 1)
            assertTrue("Selected card bottom must be visible: $bounds in $viewport", bounds.bottom <= viewport.bottom + 1)
            assertTrue(opened.isEmpty())
        }
    }
    /** The semantics bounds are clipped by the viewport; size is the layout advance. */
    private fun assertCardFullyVisible(cards: List<WalletCard>, index: Int) {
        compose.waitForIdle()
        val node = compose.onNodeWithTag("wallet-card-${cards[index].id}").fetchSemanticsNode()
        val viewport = compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).fetchSemanticsNode().boundsInRoot
        // A clipped node can still report a visible strip; the layout advance proves that the
        // animation/snap really reached the full face instead of stopping between 48dp and a card.
        val expectedHeight = node.size.width / CARD_RATIO
        assertEquals("Card ${cards[index].id} is not fully expanded: ${node.size.height}px vs ${expectedHeight}px",
            expectedHeight, node.size.height.toFloat(), 2f)
        assertTrue("Card ${cards[index].id} top ${node.boundsInRoot} must be inside $viewport",
            node.boundsInRoot.top >= viewport.top - 1f)
        assertTrue("Card ${cards[index].id} bottom ${node.boundsInRoot} must be inside $viewport",
            node.boundsInRoot.bottom <= viewport.bottom + 1f)
        assertEquals("Card ${cards[index].id} is still clipped by the viewport",
            node.size.height.toFloat(), node.boundsInRoot.height, 1f)
    }
    private fun assertCardCollapsed(cards: List<WalletCard>, index: Int) {
        val node = compose.onNodeWithTag("wallet-card-${cards[index].id}").fetchSemanticsNode()
        val fullHeight = node.size.width / CARD_RATIO
        assertTrue("Card ${cards[index].id} must be collapsed: ${node.size.height}px vs ${fullHeight}px",
            node.size.height < fullHeight - 2f)
    }
    /** Taps while the frame clock is frozen so a selection change cannot settle before the next tap. */
    private fun tapWithoutFrames(cards: List<WalletCard>, vararg indices: Int) {
        compose.mainClock.autoAdvance = false
        try {
            indices.forEach { compose.onNodeWithTag("wallet-card-${cards[it].id}").performClick() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }
    private fun swipeLastCardPartlyBelowViewport(cards: List<WalletCard>) {
        val list = compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list"))
        list.performScrollToIndex(cards.lastIndex)
        compose.waitForIdle()
        list.performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 200f, durationMillis = 200)
        }
        compose.waitForIdle()
    }
    private fun partiallyRevealedLastCardScrollsFullyIntoView(animations: Boolean) {
        val cards = home(12, animations = animations)
        swipeLastCardPartlyBelowViewport(cards)
        val last = compose.onNodeWithTag("wallet-card-${cards.last().id}")
        val initiallyClipped = last.fetchSemanticsNode()
        assertTrue("Precondition: the last card must start partly below the viewport",
            initiallyClipped.boundsInRoot.height < initiallyClipped.size.height - 1f)
        last.performClick().assertIsSelected()
        assertCardFullyVisible(cards, cards.lastIndex)
        compose.runOnIdle { assertTrue("Selecting must not navigate", opened.isEmpty()) }
    }
    private fun expandingSecondLastCardScrollsFullyIntoView(animations: Boolean) {
        val cards = home(12, animations = animations)
        swipeLastCardPartlyBelowViewport(cards)
        val index = cards.lastIndex - 1
        compose.onNodeWithTag("wallet-card-${cards[index].id}").performClick().assertIsSelected()
        assertCardFullyVisible(cards, index)
        compose.runOnIdle { assertTrue("Selecting must not navigate", opened.isEmpty()) }
    }
    @Test fun partiallyRevealedLastCardScrollsFullyIntoViewWithAnimations() =
        partiallyRevealedLastCardScrollsFullyIntoView(true)
    @Test fun partiallyRevealedLastCardScrollsFullyIntoViewWithoutAnimations() =
        partiallyRevealedLastCardScrollsFullyIntoView(false)
    @Test fun expandingSecondLastCardScrollsFullyIntoViewWithAnimations() =
        expandingSecondLastCardScrollsFullyIntoView(true)
    @Test fun expandingSecondLastCardScrollsFullyIntoViewWithoutAnimations() =
        expandingSecondLastCardScrollsFullyIntoView(false)
    private fun expandingCardAboveViewportScrollsItsTopIntoView(animations: Boolean) {
        val cards = home(12, animations = animations)
        val list = compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list"))
        // A slow drag avoids a fling, so the first card is left deliberately clipped at the top.
        list.performTouchInput { swipeUp(startY = centerY, endY = centerY - 100f, durationMillis = 1_000) }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("wallet-card-${cards.first().id}")
        val initiallyClipped = node.fetchSemanticsNode()
        assertTrue("Precondition: the tapped card must start partly above the viewport: ${initiallyClipped.boundsInRoot} vs ${initiallyClipped.size}",
            initiallyClipped.boundsInRoot.height < initiallyClipped.size.height - 1f)
        node.performClick().assertIsSelected()
        assertCardFullyVisible(cards, 0)
    }
    @Test fun expandingCardAboveViewportScrollsItsTopIntoViewWithAnimations() =
        expandingCardAboveViewportScrollsItsTopIntoView(true)
    @Test fun expandingCardAboveViewportScrollsItsTopIntoViewWithoutAnimations() =
        expandingCardAboveViewportScrollsItsTopIntoView(false)
    @Test fun switchingFromExpandedSecondLastToLastKeepsLastFullyVisible() {
        val cards = home(9, animations = false)
        swipeLastCardPartlyBelowViewport(cards)
        val secondLast = cards.lastIndex - 1
        compose.onNodeWithTag("wallet-card-${cards[secondLast].id}").performClick().assertIsSelected()
        assertCardFullyVisible(cards, secondLast)
        // No wait between the two taps: the last card must win even while the list is still settling.
        compose.onNodeWithTag("wallet-card-${cards.last().id}").performClick().assertIsSelected()
        assertCardFullyVisible(cards, cards.lastIndex)
        assertCardCollapsed(cards, secondLast)
    }
    @Test fun switchingCardsWhilePreviousExpansionIsAnimatingLeavesOnlyNewCardExpanded() {
        val cards = home(9, animations = true)
        val first = cards[5]
        val next = cards[6]
        val firstNode = compose.onNodeWithTag("wallet-card-${first.id}")
        val nextNode = compose.onNodeWithTag("wallet-card-${next.id}")
        val collapsed = firstNode.fetchSemanticsNode().size.height
        compose.mainClock.autoAdvance = false
        try {
            firstNode.performClick()
            var midFlight = collapsed
            for (frame in 0 until 20) {
                compose.mainClock.advanceTimeByFrame()
                midFlight = firstNode.fetchSemanticsNode().size.height
                if (midFlight > collapsed + 2) break
            }
            firstNode.assertIsSelected()
            val full = firstNode.fetchSemanticsNode().size.width / CARD_RATIO
            assertTrue("First card must be expanding, not settled: $midFlight between $collapsed and $full",
                midFlight > collapsed + 2 && midFlight < full - 2)
            // The next card is tapped while the first spring is still moving. It must remain a
            // composable target at that instant, otherwise this would not exercise cancellation.
            nextNode.assertExists().performClick()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertCardFullyVisible(cards, 6)
        assertCardCollapsed(cards, 5)
        firstNode.assertIsNotSelected()
        nextNode.assertIsSelected()
    }
    @Test fun rapidSelectionSwitchesEndOnTheFullyVisibleFinalCard() {
        val cards = home(9, animations = false)
        swipeLastCardPartlyBelowViewport(cards)
        // Frozen frames keep the neighbouring cards composed while selection changes several times.
        tapWithoutFrames(cards, 7, 8, 7, 8, 7, 8)
        compose.onNodeWithTag("wallet-card-${cards.last().id}").assertIsSelected()
        assertCardFullyVisible(cards, cards.lastIndex)
    }
    @Test fun stackListStackAfterScrollingListRestoresRecycledSelectedCard() {
        val all = (0 until 12).map { WalletCard(bank = "测试银行$it", position = it.toLong()) }
        var settings by mutableStateOf(WalletSettings(stacked = true, animations = false))
        val app = RuntimeEnvironment.getApplication()
        val vm = WalletViewModel(app, CardRepository(app, TestVault()))
        compose.setContent { MyCardTheme {
            HomeScreen(vm, all, settings, {}, {}, {}, {})
        } }
        val selected = 0
        compose.onNodeWithTag("wallet-card-${all[selected].id}").performClick().assertIsSelected()
        assertCardFullyVisible(all, selected)
        compose.runOnIdle { settings = settings.copy(layoutOverride = CardLayout.LIST) }
        compose.waitForIdle()
        // Move the expanded list far away from the selected card. Returning to STACK must not
        // depend on the selected item having stayed composed while it was outside the viewport.
        compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(all.lastIndex)
        compose.waitForIdle()
        // Prove the selected item is actually recycled, so the assertion below cannot pass through
        // the per-item placement/visibility effect.
        compose.onNodeWithTag("wallet-card-${all[selected].id}").assertDoesNotExist()
        compose.runOnIdle { settings = settings.copy(layoutOverride = CardLayout.STACK) }
        compose.waitForIdle()
        compose.onNodeWithTag("wallet-card-${all[selected].id}").assertIsSelected()
        assertCardFullyVisible(all, selected)
    }
    @Test fun deletingSelectedLastCardExpandsThePromotedLastCard() {
        val all = (0 until 12).map { WalletCard(bank = "测试银行$it", position = it.toLong()) }
        var cards by mutableStateOf(all)
        val app = RuntimeEnvironment.getApplication()
        val vm = WalletViewModel(app, CardRepository(app, TestVault()))
        compose.setContent { MyCardTheme {
            HomeScreen(vm, cards, WalletSettings(stacked = true, animations = false), {}, {}, {}, {})
        } }
        val list = compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list"))
        list.performScrollToIndex(all.lastIndex)
        compose.waitForIdle()
        compose.onNodeWithTag("wallet-card-${all.last().id}").performClick().assertIsSelected()
        compose.waitForIdle()
        compose.runOnIdle { cards = all.dropLast(1) }
        compose.waitForIdle()
        val promoted = compose.onNodeWithTag("wallet-card-${all[all.lastIndex - 1].id}").fetchSemanticsNode()
        assertEquals("The promoted last card must keep the full card advance",
            promoted.size.width / CARD_RATIO, promoted.size.height.toFloat(), 2f)
    }
    @Test fun fullListStillOpensLastCardOnFirstTap() {
        val cards = home(9, stacked = false)
        compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(8)
        compose.onNodeWithTag("wallet-card-${cards.last().id}").performClick()
        compose.runOnIdle { assertEquals(listOf(cards.last().id), opened) }
    }
    private fun snapshot(name: String): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/ui-previews/v1.1.1/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        return bitmap
    }
    @Test fun brandRowsHaveNoAddedWhitePlateOnDarkOrLightSurfaces() {
        var dark by mutableStateOf(true)
        val banks = listOf("中国工商银行", "中国农业银行", "中国银行", "中国建设银行", "中国邮政储蓄银行", "交通银行", "招商银行")
        compose.setContent { MyCardTheme {
            Surface(color = if (dark) Color(0xFF243546) else Color(0xFFF3E9DB)) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    banks.forEachIndexed { i, bank -> BankIdentity(bank, if(dark) Color.White else Color.Black, Modifier.testTag("bank-$i")) }
                    Network.entries.take(5).forEach { network ->
                        NetworkIdentity(WalletCard(network = network), if(dark) Color.White else Color.Black,
                            Modifier.size(96.dp, 40.dp).testTag("network-${network.name}"))
                    }
                }
            }
        } }
        listOf(true, false).forEach { mode ->
            compose.runOnIdle { dark = mode }
            val bitmap = snapshot(if (mode) "brands-dark" else "brands-light")
            val background = if(mode) 0xFF243546.toInt() else 0xFFF3E9DB.toInt()
            // Text-only identities must expose the card behind them, not an app plate.
            (0..5).forEach { i ->
                val b = compose.onNodeWithTag("bank-$i").fetchSemanticsNode().boundsInRoot
                assertEquals("Bank $i has an added plate", background, bitmap.getPixel(b.left.toInt(), b.top.toInt()))
            }
            val visa = compose.onNodeWithTag("network-VISA").fetchSemanticsNode().boundsInRoot
            assertEquals(background, bitmap.getPixel(visa.left.toInt(), visa.top.toInt()))
            bitmap.recycle()
        }
    }
    // The UnionPay vector test was removed from the public edition: the official UnionPay artwork
    // is a third-party trademark and is not redistributed here. Card networks render as text; see
    // BRAND_NOTICE.md.
}
