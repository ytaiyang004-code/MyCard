package com.mycard.wallet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mycard.wallet.data.CardFilter
import com.mycard.wallet.data.CardLayout
import com.mycard.wallet.data.SortOrder
import com.mycard.wallet.data.WalletSettings
import com.mycard.wallet.ui.Copy
import com.mycard.wallet.ui.HomeTopBar
import com.mycard.wallet.ui.rememberFilterPillColors
import com.mycard.wallet.ui.theme.MyCardTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V2.2 header contract: a centered radio-style layout switch, an independent sort entry and enough
 * room at 320dp with 1.5x text. Card geometry and scroll behaviour are covered by
 * [HomeLayoutV2Test] and [PatchInteractionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTopBarV2Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var layout by mutableStateOf(CardLayout.STACK)
    private var sort by mutableStateOf(SortOrder.CUSTOM)
    private var filter by mutableStateOf(CardFilter.ALL)
    private var query by mutableStateOf("")
    private var searching by mutableStateOf(false)
    private var animations by mutableStateOf(true)

    private fun render(narrow: Boolean = false, largeFont: Boolean = false, searchOpen: Boolean = false,
                       fontScale: Float? = null, animationsEnabled: Boolean = true) {
        searching = searchOpen
        animations = animationsEnabled
        compose.setContent {
            val density = LocalDensity.current
            val scale = fontScale ?: if (largeFont) 1.5f else density.fontScale
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MyCardTheme {
                    Box(if (narrow) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        HomeTopBar(
                            activeCount = 2,
                            query = query,
                            onQueryChange = { query = it },
                            searching = searching,
                            onSearchToggle = { searching = !searching },
                            onSearchClose = { query = ""; searching = false },
                            filter = filter,
                            onFilter = { filter = it },
                            settings = WalletSettings(layoutOverride = layout, sort = sort, animations = animations),
                            reorderEnabled = true,
                            onLayout = { layout = it },
                            onSort = { sort = it },
                            onReorder = {}
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun rootBounds() = compose.onRoot().fetchSemanticsNode().boundsInRoot
    private fun dp(value: Float) = value * compose.density.density

    private fun probeColor(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString()

    @Composable
    private fun ColorProbe(selected: Boolean, animations: Boolean) {
        val colors = rememberFilterPillColors(selected, animations)
        Column {
            Text(colors.background.toString(), Modifier.testTag("filter-bg"))
            Text(colors.content.toString(), Modifier.testTag("filter-fg"))
            Text(colors.targetBackground.toString(), Modifier.testTag("filter-bg-target"))
            Text(colors.targetContent.toString(), Modifier.testTag("filter-fg-target"))
        }
    }

    @Test fun longestFilterFitsAtNarrowWidthWithDoubleFont() {
        filter = CardFilter.MASTERCARD
        render(narrow = true, fontScale = 2f)
        val root = rootBounds()
        val bar = bounds("home-filter-bar")
        val item = bounds("home-filter-MASTERCARD")
        compose.onNodeWithTag("home-filter-MASTERCARD").assertIsSelected()
        val density = compose.density.density
        println("V2.2 320dp@2.0 filter widths: bar=${bar.width / density}dp, item=${item.width / density}dp")
        assertTrue("bar must never exceed the parent width", bar.width <= root.width - dp(40f) + 1f)
        assertTrue("longest selected filter must fit inside the bar: $item in $bar",
            item.left >= bar.left - 1f && item.right <= bar.right + 1f)
    }

    @Test fun rapidLateSelectionsEndOnTheLastFilterAndKeepItVisible() {
        render(narrow = true, fontScale = 2f)
        val list = compose.onNodeWithTag("home-filter-list")
        list.performScrollToIndex(CardFilter.MASTERCARD.ordinal)
        compose.onNodeWithTag("home-filter-MASTERCARD").performClick()
        list.performScrollToIndex(CardFilter.ARCHIVED.ordinal)
        compose.onNodeWithTag("home-filter-ARCHIVED").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(CardFilter.ARCHIVED, filter) }
        compose.onNodeWithTag("home-filter-ARCHIVED").assertIsSelected()
        val bar = bounds("home-filter-bar")
        val item = bounds("home-filter-ARCHIVED")
        assertTrue("final filter must be fully visible: $item in $bar",
            item.left >= bar.left - 1f && item.right <= bar.right + 1f)
    }

    @Test fun layoutIndicatorJumpsImmediatelyWhenAnimationsAreOff() {
        render(animationsEnabled = false)
        val album = bounds("home-layout-ALBUM")
        compose.onNodeWithTag("home-layout-ALBUM").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(CardLayout.ALBUM, layout) }
        val indicator = bounds("home-layout-indicator")
        assertEquals("disabled animations must snap to the target", album.left, indicator.left, 1f)
    }

    @Test fun layoutIndicatorSlidesAndRapidSwitchesLandOnTheLatestSelection() {
        render()
        val initial = bounds("home-layout-indicator")
        val list = bounds("home-layout-LIST")
        val album = bounds("home-layout-ALBUM")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("home-layout-LIST").performClick()
            var mid = initial
            for (frame in 0 until 20) {
                compose.mainClock.advanceTimeByFrame()
                mid = bounds("home-layout-indicator")
                if (mid.left > initial.left + 1f && mid.left < list.left - 1f) break
            }
            assertTrue("indicator must be between STACK and LIST while sliding: initial=$initial mid=$mid list=$list",
                mid.left > initial.left + 1f && mid.left < list.left - 1f)

            compose.onNodeWithTag("home-layout-ALBUM").performClick()
            repeat(30) { compose.mainClock.advanceTimeByFrame() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(CardLayout.ALBUM, layout) }
        val final = bounds("home-layout-indicator")
        assertEquals("rapid switch must finish on ALBUM", album.left, final.left, 1f)
    }

    @Test fun filterColorAnimatesAndReachesTargetWhenAnimationsAreOn() {
        var probeSelected by mutableStateOf(false)
        compose.setContent { MyCardTheme { ColorProbe(probeSelected, true) } }
        compose.waitForIdle()
        val beforeBg = probeColor("filter-bg")
        val beforeFg = probeColor("filter-fg")
        compose.mainClock.autoAdvance = false
        try {
            compose.activity.runOnUiThread { probeSelected = true }
            var midBg = beforeBg
            var midFg = beforeFg
            for (frame in 0 until 20) {
                compose.mainClock.advanceTimeByFrame()
                midBg = probeColor("filter-bg")
                midFg = probeColor("filter-fg")
                if (midBg != beforeBg && midFg != beforeFg) break
            }
            val targetBg = probeColor("filter-bg-target")
            val targetFg = probeColor("filter-fg-target")
            repeat(20) { compose.mainClock.advanceTimeByFrame() }
            val endBg = probeColor("filter-bg")
            val endFg = probeColor("filter-fg")
            assertTrue("probe selection must change", probeSelected)
            assertNotEquals("background must start changing: before=$beforeBg mid=$midBg", beforeBg, midBg)
            assertNotEquals("content must start changing: before=$beforeFg mid=$midFg", beforeFg, midFg)
            assertNotEquals("background must still be transitioning: mid=$midBg end=$endBg", midBg, endBg)
            assertNotEquals("content must still be transitioning: mid=$midFg end=$endFg", midFg, endFg)
            assertEquals("background must finish at target", targetBg, endBg)
            assertEquals("content must finish at target", targetFg, endFg)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun filterColorSnapsImmediatelyWhenAnimationsAreOff() {
        var probeSelected by mutableStateOf(false)
        compose.setContent { MyCardTheme { ColorProbe(probeSelected, false) } }
        compose.waitForIdle()
        val beforeBg = probeColor("filter-bg")
        val beforeFg = probeColor("filter-fg")
        compose.mainClock.autoAdvance = false
        try {
            compose.activity.runOnUiThread { probeSelected = true }
            var immediateBg = beforeBg
            var immediateFg = beforeFg
            for (frame in 0 until 20) {
                compose.mainClock.advanceTimeByFrame()
                immediateBg = probeColor("filter-bg")
                immediateFg = probeColor("filter-fg")
                if (immediateBg != beforeBg && immediateFg != beforeFg) break
            }
            val targetBg = probeColor("filter-bg-target")
            val targetFg = probeColor("filter-fg-target")
            repeat(20) { compose.mainClock.advanceTimeByFrame() }
            assertTrue("probe selection must change", probeSelected)
            assertNotEquals("background must differ from unselected: before=$beforeBg immediate=$immediateBg", beforeBg, immediateBg)
            assertNotEquals("content must differ from unselected: before=$beforeFg immediate=$immediateFg", beforeFg, immediateFg)
            assertEquals("background must snap at target", targetBg, immediateBg)
            assertEquals("content must snap at target", targetFg, immediateFg)
            assertEquals("background must not keep transitioning", immediateBg, probeColor("filter-bg"))
            assertEquals("content must not keep transitioning", immediateFg, probeColor("filter-fg"))
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun filterColorSnapsWhenAnimationsTurnOffMidTransition() {
        var probeSelected by mutableStateOf(false)
        var probeAnimations by mutableStateOf(true)
        compose.setContent { MyCardTheme { ColorProbe(probeSelected, probeAnimations) } }
        compose.waitForIdle()
        val beforeBg = probeColor("filter-bg")
        val beforeFg = probeColor("filter-fg")
        compose.mainClock.autoAdvance = false
        try {
            compose.activity.runOnUiThread { probeSelected = true }
            var midBg = beforeBg
            var midFg = beforeFg
            for (frame in 0 until 20) {
                compose.mainClock.advanceTimeByFrame()
                midBg = probeColor("filter-bg")
                midFg = probeColor("filter-fg")
                if (midBg != beforeBg && midFg != beforeFg) break
            }
            val targetBg = probeColor("filter-bg-target")
            val targetFg = probeColor("filter-fg-target")
            assertNotEquals("background must be mid-transition", beforeBg, midBg)
            assertNotEquals("background must not already be at target", targetBg, midBg)
            assertNotEquals("content must be mid-transition", beforeFg, midFg)
            assertNotEquals("content must not already be at target", targetFg, midFg)

            // Switch animations off without changing the selected filter.
            compose.activity.runOnUiThread { probeAnimations = false }
            // Allow only the necessary recomposition frames, well below the 160ms tween.
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            val snapBg = probeColor("filter-bg")
            val snapFg = probeColor("filter-fg")
            assertEquals("background must snap immediately when animations turn off", targetBg, snapBg)
            assertEquals("content must snap immediately when animations turn off", targetFg, snapFg)
            repeat(20) { compose.mainClock.advanceTimeByFrame() }
            assertEquals("background must not retain a residual transition", snapBg, probeColor("filter-bg"))
            assertEquals("content must not retain a residual transition", snapFg, probeColor("filter-fg"))
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun filterColorResumesAnimationAfterAnimationsAreReenabled() {
        var probeSelected by mutableStateOf(false)
        var probeAnimations by mutableStateOf(false)
        compose.setContent { MyCardTheme { ColorProbe(probeSelected, probeAnimations) } }
        compose.waitForIdle()
        val unselectedBg = probeColor("filter-bg")
        val unselectedFg = probeColor("filter-fg")
        compose.mainClock.autoAdvance = false
        try {
            compose.activity.runOnUiThread { probeSelected = true }
            var selectedBg = unselectedBg
            var selectedFg = unselectedFg
            for (frame in 0 until 20) {
                compose.mainClock.advanceTimeByFrame()
                selectedBg = probeColor("filter-bg")
                selectedFg = probeColor("filter-fg")
                if (selectedBg != unselectedBg && selectedFg != unselectedFg) break
            }
            assertNotEquals("snap must reach selected background", unselectedBg, selectedBg)

            // Re-enable animation and deselect in the same state change.
            compose.activity.runOnUiThread {
                probeAnimations = true
                probeSelected = false
            }
            var midBg = selectedBg
            var midFg = selectedFg
            for (frame in 0 until 20) {
                compose.mainClock.advanceTimeByFrame()
                midBg = probeColor("filter-bg")
                midFg = probeColor("filter-fg")
                if (midBg != selectedBg && midBg != unselectedBg &&
                    midFg != selectedFg && midFg != unselectedFg) break
            }
            assertNotEquals("background must animate after re-enabling", selectedBg, midBg)
            assertNotEquals("background must not jump straight back", unselectedBg, midBg)
            assertNotEquals("content must animate after re-enabling", selectedFg, midFg)
            assertNotEquals("content must not jump straight back", unselectedFg, midFg)
            repeat(20) { compose.mainClock.advanceTimeByFrame() }
            assertEquals("background must finish unselected", unselectedBg, probeColor("filter-bg"))
            assertEquals("content must finish unselected", unselectedFg, probeColor("filter-fg"))
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun layoutSwitchExposesThreeSelectedRadioSegments() {
        render()
        compose.onNodeWithTag("home-layout-switch").assertIsDisplayed()
        CardLayout.entries.forEach { candidate ->
            compose.onNodeWithTag("home-layout-${candidate.name}")
                .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("home-layout-STACK").assertIsSelected()
        compose.onNodeWithTag("home-layout-LIST").assertIsNotSelected()

        compose.onNodeWithTag("home-layout-LIST").performClick()
        compose.runOnIdle { assertEquals(CardLayout.LIST, layout) }
        compose.onNodeWithTag("home-layout-LIST").assertIsSelected()
        compose.onNodeWithTag("home-layout-STACK").assertIsNotSelected()
        compose.onNodeWithContentDescription("${Copy.arrangement}：${CardLayout.LIST.label}")
            .assertIsSelected()

        compose.onNodeWithTag("home-layout-ALBUM").performClick()
        compose.runOnIdle { assertEquals(CardLayout.ALBUM, layout) }
        compose.onNodeWithTag("home-layout-ALBUM").assertIsSelected()
    }

    @Test fun sortEntryKeepsEveryOrderAndMarksTheCurrentOne() {
        render()
        compose.onNodeWithContentDescription(Copy.sorting).performClick()
        SortOrder.entries.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
        compose.onNodeWithContentDescription(Copy.currentSort).assertExists()

        compose.onNodeWithText(SortOrder.NEWEST.label).performClick()
        compose.runOnIdle { assertEquals(SortOrder.NEWEST, sort) }
        compose.onNodeWithContentDescription(Copy.sorting).performClick()
        compose.onNodeWithContentDescription(Copy.currentSort).assertExists()
    }

    @Test fun filterBarUsesOneCapsuleAndExposesTheSelectedFilter() {
        render()
        val root = rootBounds()
        val bar = bounds("home-filter-bar")
        assertTrue("filter container should be 48-52dp high", bar.height / compose.density.density in 48f..54f)
        assertEquals("filter bar must be 40% of the available row width",
            (root.width - dp(40f)) * .4f, bar.width, dp(1f))
        assertEquals("filter bar must be horizontally centered", root.center.x, bar.center.x, dp(1f))
        compose.onNodeWithTag("home-filter-ALL").assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("home-filter-VISA").assertIsNotSelected().assertHeightIsAtLeast(48.dp)

        compose.onNodeWithTag("home-filter-VISA").performClick()
        compose.runOnIdle { assertEquals(CardFilter.VISA, filter) }
        compose.onNodeWithTag("home-filter-VISA").assertIsSelected()
    }

    @Test fun restoredFilterScrollsIntoTheVisiblePartOfTheBar() {
        filter = CardFilter.ARCHIVED
        render(narrow = true, largeFont = true)
        val bar = bounds("home-filter-bar")
        val archived = bounds("home-filter-ARCHIVED")
        compose.onNodeWithTag("home-filter-ARCHIVED").assertIsSelected().assertHeightIsAtLeast(48.dp)
        assertTrue("selected filter must be inside the bar: $archived in $bar",
            archived.left >= bar.left - 1f && archived.right <= bar.right + 1f)
    }

    @Test fun narrowScreenWithLargeFontKeepsAllHeaderControlsSeparated() {
        render(narrow = true, largeFont = true)
        val root = rootBounds()
        val title = bounds("home-title")
        val badge = bounds("home-count-badge")
        val search = compose.onNodeWithContentDescription(Copy.search).fetchSemanticsNode().boundsInRoot
        val sort = bounds("home-sort")
        val switch = bounds("home-layout-switch")
        val reorder = bounds("home-reorder")
        val filterBar = bounds("home-filter-bar")

        assertTrue("title and badge must not overlap: $title -> $badge", title.right <= badge.left + .5f)
        assertTrue("badge and search must not overlap: $badge -> $search", badge.right <= search.left + .5f)
        assertTrue("search must stay inside the 320dp window: $search", search.right <= root.right - dp(20f) + .5f)
        assertTrue("sort and switch must not overlap: $sort -> $switch", sort.right <= switch.left + .5f)
        assertTrue("switch and reorder must not overlap: $switch -> $reorder", switch.right <= reorder.left + .5f)
        assertEquals("layout switch must stay centered", root.center.x, switch.center.x, dp(1f))
        assertEquals("filter bar must stay centered", root.center.x, filterBar.center.x, dp(1f))
        assertTrue("filter bar must widen enough for large text", filterBar.width >= dp(140f))
        listOf(title, badge, search, sort, switch, reorder, filterBar).forEach {
            assertTrue("$it must stay inside $root", it.left >= root.left - .5f && it.right <= root.right + .5f)
        }
        CardLayout.entries.forEach { compose.onNodeWithTag("home-layout-${it.name}").assertIsDisplayed() }
    }

    @Test fun expandedSearchKeepsSixDpGapAboveTheCategoryRow() {
        render(searchOpen = true)
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val bar = bounds("home-filter-bar")
        val gap = (bar.top - field.bottom) / compose.density.density
        assertTrue("search-to-filter container gap must stay between 5 and 8dp, was $gap", gap in 5f..8f)
    }
}
