package com.mycard.wallet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mycard.wallet.data.WalletCard
import com.mycard.wallet.data.WalletSettings
import com.mycard.wallet.ui.Copy
import com.mycard.wallet.ui.HomeScreen
import com.mycard.wallet.ui.theme.MyCardTheme
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI V2.1 static chrome. These assertions cover the new title/badge/search order, the fixed
 * bottom-right add action, the bottom-left settings action and the narrow-width fallback. They do
 * not restate the T5 stack geometry; [PatchInteractionTest] remains the owner of that contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLayoutV2Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun viewModel() = WalletViewModel(RuntimeEnvironment.getApplication(),
        com.mycard.wallet.data.CardRepository(RuntimeEnvironment.getApplication(), TestVault()))

    private fun cards(count: Int, archived: Int = 0) = (0 until count).map { i ->
        WalletCard(bank = "测试银行$i", lastFour = (1000 + i).toString(), position = i.toLong(),
            archived = i >= count - archived)
    }

    private fun render(
        list: List<WalletCard>,
        settings: WalletSettings = WalletSettings(animations = false),
        onAdd: () -> Unit = {},
        onOpen: (String) -> Unit = {},
        onSettings: () -> Unit = {}
    ) {
        val vm = viewModel()
        compose.setContent {
            MyCardTheme { HomeScreen(vm, list, settings, onAdd, onOpen, onSettings, {}) }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun visualBounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun rootBounds() = compose.onRoot().fetchSemanticsNode().boundsInRoot

    private fun dp(value: Float) = value * compose.density.density

    @Test fun titleBadgeAndSearchReadLeftToRightAndSearchSitsAtTheRightInset() {
        render(cards(2))
        val root = rootBounds()
        val title = bounds("home-title")
        val badge = bounds("home-count-badge")
        val search = compose.onNodeWithContentDescription(Copy.search).fetchSemanticsNode().boundsInRoot

        assertTrue("title must start at the page inset: $title vs $root", title.left >= root.left + dp(20f) - 1f)
        assertTrue("badge must follow the title: $title -> $badge", badge.left >= title.right - .5f)
        assertTrue("badge must stay attached to the title", badge.left - title.right <= dp(10f))
        assertTrue("badge must not overlap search: $badge -> $search", badge.right <= search.left + .5f)
        assertTrue("search must sit on the right half", search.center.x > root.center.x)
        assertEquals("search keeps the 20dp page inset", 20f, (root.right - search.right) / compose.density.density, 1.5f)
    }

    @Test fun countBadgeKeepsTwoDigitTextAndIgnoresArchivedCards() {
        render(cards(3, archived = 1))
        compose.onNodeWithText("02").assertIsDisplayed()
    }

    @Test fun countBadgeShowsZeroWhenEverythingIsArchived() {
        render(cards(2, archived = 2))
        compose.onNodeWithText("00").assertIsDisplayed()
    }

    @Test fun addFabAndSettingsSitInBottomCornersWith48dpTouchTargets() {
        render(cards(2))
        val root = rootBounds()
        val fab = bounds("home-add-fab")
        val settings = bounds("home-settings")

        compose.onNodeWithTag("home-add-fab").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("home-settings").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertTrue("add action must sit on the right half: $fab", fab.center.x > root.center.x)
        assertTrue("settings must sit on the left half: $settings", settings.center.x < root.center.x)
        assertEquals("add action keeps the 12dp end inset", 12f,
            (root.right - fab.right) / compose.density.density, 1.5f)
        assertEquals("settings keeps the 12dp start inset", 12f,
            (settings.left - root.left) / compose.density.density, 1.5f)

        val fabBottom = (root.bottom - fab.bottom) / compose.density.density
        val settingsBottom = (root.bottom - settings.bottom) / compose.density.density
        assertTrue("add action should sit about 144dp above the bottom (before nav insets): $fabBottom", fabBottom in 140f..200f)
        assertTrue("settings should sit near the bottom (before nav insets): $settingsBottom", settingsBottom in 12f..70f)
        assertTrue("settings must stay above the add action", settings.top > fab.bottom)
        assertFalse("the two bottom actions must not overlap", fab.overlaps(settings))
    }

    @Test fun bothBottomActionsInvokeTheirExistingCallbacksAndCardsStayClickable() {
        var adds = 0
        var settingsOpened = 0
        val list = cards(2)
        render(list, onAdd = { adds++ }, onSettings = { settingsOpened++ })

        compose.onNodeWithTag("home-add-fab").performClick()
        compose.runOnIdle { assertEquals(1, adds) }
        compose.onNodeWithTag("home-settings").performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened) }

        compose.onNodeWithTag("wallet-card-${list.first().id}").performClick().assertIsSelected()
    }

    @Test fun narrowScreenWithLargeFontKeepsHeaderAndBottomActionsOnScreen() {
        val vm = viewModel()
        val list = cards(2)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MyCardTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        HomeScreen(vm, list, WalletSettings(animations = false), {}, {}, {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = rootBounds()
        val title = bounds("home-title")
        val badge = bounds("home-count-badge")
        val search = compose.onNodeWithContentDescription(Copy.search).fetchSemanticsNode().boundsInRoot
        val fab = bounds("home-add-fab")
        val settings = bounds("home-settings")

        assertTrue(title.left >= root.left - .5f && title.right <= badge.left + .5f)
        assertTrue("badge must stay visible next to a long title", badge.right <= search.left + .5f)
        assertTrue("search must stay inside the 320dp window: $search", search.right <= root.right + .5f)
        listOf(title, badge, search, fab, settings).forEach {
            assertTrue("$it must stay inside $root", it.left >= root.left - .5f && it.right <= root.right + .5f)
        }
        compose.onNodeWithText(Copy.collection).assertIsDisplayed()
        compose.onNodeWithText("02").assertIsDisplayed()
        compose.onNodeWithTag("home-add-fab").assertIsDisplayed()
        compose.onNodeWithTag("home-settings").assertIsDisplayed()
    }

    @Test fun filterBarFiltersTheCardsThroughTheExistingCardRules() {
        val visa = WalletCard(bank = "Visa 银行", lastFour = "1111", network = com.mycard.wallet.data.Network.VISA, position = 0)
        val unionPay = WalletCard(bank = "银联银行", lastFour = "2222", position = 1)
        render(listOf(visa, unionPay))

        compose.onNodeWithTag("home-filter-VISA").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("wallet-card-${visa.id}").assertExists()
        compose.onNodeWithTag("wallet-card-${unionPay.id}").assertDoesNotExist()
    }

    @Test fun pressingAddButtonScalesOnlyTheVisualCircleAndKeepsTouchTarget() {
        var adds = 0
        render(cards(2), settings = WalletSettings(animations = true), onAdd = { adds++ })
        val touchBefore = bounds("home-add-fab")
        val visualBefore = visualBounds("home-add-fab-visual")

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("home-add-fab").performTouchInput { down(center) }
            repeat(6) { compose.mainClock.advanceTimeByFrame() }
            val touchPressed = bounds("home-add-fab")
            val visualPressed = visualBounds("home-add-fab-visual")
            assertEquals("touch target must not scale", touchBefore.width, touchPressed.width, .5f)
            assertTrue("visual circle must shrink while pressed: $visualBefore -> $visualPressed",
                visualPressed.width < visualBefore.width - 1f)

            compose.onNodeWithTag("home-add-fab").performTouchInput { up() }
            repeat(20) { compose.mainClock.advanceTimeByFrame() }
            val visualReleased = visualBounds("home-add-fab-visual")
            assertTrue("visual circle must return to normal: $visualReleased",
                visualReleased.width >= visualBefore.width - 1f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, adds) }
    }

    @Test fun pressScaleDoesNotReplayAStaleTweenAfterAnimationToggle() {
        var settings by mutableStateOf(WalletSettings(animations = true))
        val vm = viewModel()
        val list = cards(2)
        compose.setContent { MyCardTheme { HomeScreen(vm, list, settings, {}, {}, {}, {}) } }
        compose.waitForIdle()
        val normal = visualBounds("home-add-fab-visual")
        val target = normal.width * .96f

        compose.mainClock.autoAdvance = false
        try {
            // Phase 1: press and advance until the visual shows measurable shrinkage (≥0.2px).
            // The LaunchedEffect-based Animatable needs ~4 frames to start; animateFloatAsState
            // starts in ~3. Cap at 6 to keep the tween well below completion.
            compose.onNodeWithTag("home-add-fab").performTouchInput { down(center) }
            var midWidth = normal.width
            for (frame in 0 until 6) {
                compose.mainClock.advanceTimeByFrame()
                midWidth = visualBounds("home-add-fab-visual").width
                if (midWidth < normal.width - .2f) break
            }
            assertTrue("press must be mid-animation (width=$midWidth, normal=${normal.width})",
                midWidth > target + .2f && midWidth < normal.width - .2f)

            // Phase 2: disable animations and advance 2 frames.
            // The bypass path snaps the visual to targetScale immediately.
            compose.activity.runOnUiThread { settings = settings.copy(animations = false) }
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            assertEquals("bypass must snap to target", target,
                visualBounds("home-add-fab-visual").width, .5f)

            // Phase 3: re-enable animations while the tween is still mid-flight.
            // A stale animateFloatAsState tween exposes residual motion; the Animatable
            // implementation uses snapTo so the value is already at target.
            compose.activity.runOnUiThread { settings = settings.copy(animations = true) }
            compose.mainClock.advanceTimeByFrame()
            val frameA = visualBounds("home-add-fab-visual").width
            compose.mainClock.advanceTimeByFrame()
            val frameB = visualBounds("home-add-fab-visual").width

            assertEquals("first frame after re-enable must be at target", target, frameA, .5f)
            assertEquals("second frame after re-enable must be at target", target, frameB, .5f)
            assertTrue("no residual animation between consecutive frames (delta=${abs(frameA - frameB)})",
                abs(frameA - frameB) < .1f)

            compose.onNodeWithTag("home-add-fab").performTouchInput { up() }
            repeat(12) { compose.mainClock.advanceTimeByFrame() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun settingsButtonUsesTheSamePressTarget() {
        var opened = 0
        var settings by mutableStateOf(WalletSettings(animations = true))
        val vm = viewModel()
        val list = cards(2)
        compose.setContent {
            MyCardTheme { HomeScreen(vm, list, settings, {}, {}, { opened++ }, {}) }
        }
        compose.waitForIdle()
        val touchBefore = bounds("home-settings")
        val visualBefore = visualBounds("home-settings-visual")
        val target = visualBefore.width * .96f

        compose.mainClock.autoAdvance = false
        try {
            // Phase 1: press and advance until the visual shows measurable shrinkage (≥0.2px).
            compose.onNodeWithTag("home-settings").performTouchInput { down(center) }
            var midWidth = visualBefore.width
            for (frame in 0 until 6) {
                compose.mainClock.advanceTimeByFrame()
                midWidth = visualBounds("home-settings-visual").width
                if (midWidth < visualBefore.width - .2f) break
            }
            assertEquals("touch target must not scale", touchBefore.width, bounds("home-settings").width, .5f)
            assertTrue("settings visual must shrink while pressed (width=$midWidth, normal=${visualBefore.width})",
                midWidth > target + .2f && midWidth < visualBefore.width - .2f)

            // Phase 2: disable animations and advance 2 frames.
            compose.activity.runOnUiThread { settings = settings.copy(animations = false) }
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            assertEquals("settings must snap to pressed target", target,
                visualBounds("home-settings-visual").width, .5f)

            // Phase 3: re-enable animations while the tween is still mid-flight.
            compose.activity.runOnUiThread { settings = settings.copy(animations = true) }
            compose.mainClock.advanceTimeByFrame()
            val frameA = visualBounds("home-settings-visual").width
            compose.mainClock.advanceTimeByFrame()
            val frameB = visualBounds("home-settings-visual").width

            assertEquals("first frame after re-enable must be at target", target, frameA, .5f)
            assertEquals("second frame after re-enable must be at target", target, frameB, .5f)
            assertTrue("no residual animation between consecutive frames (delta=${abs(frameA - frameB)})",
                abs(frameA - frameB) < .1f)

            compose.onNodeWithTag("home-settings").performTouchInput { up() }
            repeat(12) { compose.mainClock.advanceTimeByFrame() }
            assertTrue("settings visual must return to normal",
                visualBounds("home-settings-visual").width >= visualBefore.width - 1f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun expandingSearchKeepsTheSelectedStackCardFullyVisible() {
        val list = cards(5)
        render(list)
        val selected = compose.onNodeWithTag("wallet-card-${list.first().id}")
        selected.performClick().assertIsSelected()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(Copy.search).performClick()
        compose.waitForIdle()

        selected.assertIsSelected()
        val card = selected.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).fetchSemanticsNode().boundsInRoot
        assertTrue("selected card top must stay visible after search expands: $card in $viewport",
            card.top >= viewport.top - 1f)
        assertTrue("selected card bottom must stay visible after search expands: $card in $viewport",
            card.bottom <= viewport.bottom + 1f)
    }

    @Test fun searchButtonTogglesTheFieldAndCloseClearsIt() {
        render(cards(2))
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithContentDescription(Copy.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("招商")
        compose.onNode(hasSetTextAction()).assertTextContains("招商")

        compose.onNodeWithContentDescription(Copy.cancel).performClick()
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(Copy.collection).assertIsDisplayed()
    }
}
