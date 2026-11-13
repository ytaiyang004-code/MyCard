package com.mycard.wallet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.Copy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Adds/deletes only its own temporary cards. Never clears a preexisting wallet. */
@RunWith(AndroidJUnit4::class)
class DesignUpgradeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo by lazy { ViewModelProvider(compose.activity)[WalletViewModel::class.java].repository }
    private var original: List<WalletCard>? = null
    private var prefs: WalletSettings? = null
    private val temporaryIds = mutableListOf<String>()
    private val marker = "验收-${UUID.randomUUID().toString().take(6)}"
    private lateinit var cards: List<WalletCard>
    @Before fun prepare() {
        runBlocking {
            val settings = Preferences(context).flow.first()
            Assume.assumeFalse("Unlock a dedicated test installation before running", settings.privacyLock)
            prefs = settings
            original = repo.all()
            val start = original!!.maxOfOrNull { it.position }?.plus(1) ?: 0
            cards = (0..11).map { i -> WalletCard(bank = listOf("中国工商银行", "中国农业银行", "中国银行", "中国建设银行", "中国邮政储蓄银行", "交通银行", "招商银行")[i % 7],
                lastFour = (8100 + i).toString(), tags = if (i == 5) "$marker-single" else marker, position = start + i, template = 6 + i % 10,
                notes = "旅行与日常收藏", showNotes = false) }
            cards.forEach { temporaryIds += it.id; repo.save(it) }
            Preferences(context).boolean("animations", false)
            Preferences(context).boolean("stacked", true)
            Preferences(context).text("theme", ThemeMode.DARK.name)
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(Copy.collection).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(Copy.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(marker)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("wallet-card-${cards[0].id}").fetchSemanticsNodes().isNotEmpty()
        }
    }
    @After fun restore() {
        runBlocking {
            temporaryIds.forEach { repo.delete(it) }
            prefs?.let {
                Preferences(context).boolean("stacked", it.stacked)
                Preferences(context).boolean("animations", it.animations)
                Preferences(context).text("theme", it.theme.name)
                it.paletteOverride?.let { palette -> Preferences(context).text("palette", palette.name) }
                it.followSystem?.let { follow -> Preferences(context).boolean("followSystem", follow) }
                it.layoutOverride?.let { layout -> Preferences(context).layout(layout) }
                assertEquals(it, Preferences(context).flow.first())
            }
            original?.let { before -> assertEquals(before.sortedBy { it.id }, repo.all().sortedBy { it.id }) }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        File(context.cacheDir, "qa-$name.png").outputStream().use { bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap!!.recycle()
    }
    @Test fun materialStackMenuListAndSystemCaptureRemainInteractive() {
        capture("stack-dark")
        compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performTouchInput { swipeUp() }
        compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(8)
        compose.onNodeWithTag("wallet-card-${cards[8].id}").performClick()
        compose.onNodeWithTag("wallet-card-${cards[8].id}").performClick()
        compose.onNodeWithText(Copy.details).assertIsDisplayed()
        compose.onNodeWithContentDescription(Copy.back).performClick()
        compose.onNodeWithTag("home-layout-LIST").performClick()
        compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(0)
        runBlocking { Preferences(context).text("theme", ThemeMode.LIGHT.name) }
        capture("list-light")
        compose.onNodeWithTag("wallet-card-${cards[0].id}").performClick()
        compose.onNodeWithText(Copy.details).assertIsDisplayed()
    }
    @Test fun firstMiddleLastAndSingleStackCardsSelectBeforeOpening() {
        runBlocking { Preferences(context).boolean("animations", true) }
        for (index in listOf(0, 6, 11)) {
            compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(index)
            val node = compose.onNodeWithTag("wallet-card-${cards[index].id}")
            node.assertIsNotSelected().performClick().assertIsSelected()
            compose.onNodeWithText(Copy.details).assertDoesNotExist()
            compose.waitForIdle()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= viewport.top - 1 && bounds.bottom <= viewport.bottom + 1)
            capture("patch-expanded-$index")
            node.performClick()
            compose.onNodeWithText(Copy.details).assertIsDisplayed()
            compose.onNodeWithContentDescription(Copy.back).performClick()
        }
        // A separate filter creates a genuine single-card result and clears selection via ALL.
        compose.onNode(hasSetTextAction()).performTextReplacement("$marker-single")
        compose.onNodeWithTag("wallet-card-${cards[0].id}").assertDoesNotExist()
        val node = compose.onNodeWithTag("wallet-card-${cards[5].id}")
        node.assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText(Copy.details).assertDoesNotExist()
        node.performClick()
        compose.onNodeWithText(Copy.details).assertIsDisplayed()
    }
    @Test fun paletteNotesWordmarkAndLogoPositionPersistThroughActivityRecreation() {
        compose.onNodeWithTag("wallet-card-${cards[0].id}").performClick()
        compose.onNodeWithTag("wallet-card-${cards[0].id}").performClick()
        compose.onNodeWithText(Copy.edit).performScrollTo().performClick()
        compose.onAllNodesWithText("调色").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("color-board").performScrollTo().performTouchInput {
            swipe(Offset(width * .25f, height * .75f), Offset(width * .8f, height * .25f))
        }
        capture("palette")
        compose.onNodeWithText("完成").performScrollTo().performClick()
        compose.onNodeWithContentDescription(Copy.showWordmark).performScrollTo().performClick()
        val logoOption = compose.onNodeWithContentDescription("${Copy.logoPosition}：右上")
        // The options sit in a horizontal scroll row inside the vertical form. Reveal both ancestors.
        logoOption.onParent().performScrollTo()
        logoOption.performScrollTo().assertIsDisplayed().performClick()
        capture("logo-selection")
        compose.runOnIdle {
            assertEquals("Logo selection must take effect before editing notes", LogoPosition.TOP_RIGHT,
                ViewModelProvider(compose.activity)[WalletViewModel::class.java].draft.value?.logoPosition)
        }
        compose.onNodeWithContentDescription(Copy.showNotes).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Notes toggle must preserve logo selection", LogoPosition.TOP_RIGHT,
                ViewModelProvider(compose.activity)[WalletViewModel::class.java].draft.value?.logoPosition)
        }
        compose.onNodeWithText(Copy.save).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(Copy.details).fetchSemanticsNodes().isNotEmpty() }
        val saved = runBlocking { repo.all().first { it.id == cards[0].id } }
        assertTrue(saved.showNotes); assertFalse(saved.showWordmark)
        assertEquals(LogoPosition.TOP_RIGHT, saved.logoPosition)
        assertNotEquals(cards[0].primary, saved.primary)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(Copy.details).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("旅行与日常收藏").onFirst().assertExists()
        capture("saved-details")
    }
}
