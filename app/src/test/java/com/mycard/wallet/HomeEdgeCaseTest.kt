package com.mycard.wallet

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.Color
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

/**
 * Worst-case home data: the longest bank name the schema accepts, a draft without its four digits,
 * a bank and a network with no bundled artwork, a pure-white face, a large font on a narrow screen,
 * and single / many cards in every arrangement. Values follow the real rules in [CardRules], so a
 * break here is one a real card can produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeEdgeCaseTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun walletViewModel() = WalletViewModel(RuntimeEnvironment.getApplication(),
        CardRepository(RuntimeEnvironment.getApplication(), TestVault()))

    private fun renderFace(card: WalletCard) {
        compose.setContent { MyCardTheme { Surface { Box(Modifier.padding(20.dp)) {
            CardFace(card, WalletSettings(animations = false), null, Modifier.testTag("face"))
        } } } }
        compose.waitForIdle()
    }

    private fun artworkBounds() = compose.onNodeWithTag("face").fetchSemanticsNode().boundsInRoot

    @Test fun homeTitleIsMyCardsInEveryStateAndArrangement() {
        assertEquals("我的卡片", Copy.collection)
        val card = WalletCard(bank = "招商银行", lastFour = "1234", position = 0)
        val vm = walletViewModel()
        var cards by mutableStateOf(listOf(card))
        var settings by mutableStateOf(WalletSettings(animations = false))
        compose.setContent { MyCardTheme { HomeScreen(vm, cards, settings, {}, {}, {}, {}) } }

        compose.onNodeWithText("我的卡片").assertIsDisplayed()
        compose.onNodeWithText(Copy.collection).assertIsDisplayed()
        compose.onNodeWithText("01").assertIsDisplayed()

        compose.runOnIdle { cards = emptyList() }
        compose.waitForIdle()
        compose.onNodeWithText(Copy.collection).assertIsDisplayed()
        compose.onNodeWithText("00").assertIsDisplayed()
        compose.onNodeWithText(Copy.emptyTitle).assertIsDisplayed()

        compose.runOnIdle { cards = listOf(card) }
        compose.onNodeWithContentDescription(Copy.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("不存在的机构")
        compose.waitForIdle()
        compose.onNodeWithText(Copy.collection).assertIsDisplayed()
        compose.onNodeWithText(Copy.noResults).assertIsDisplayed()

        CardLayout.entries.forEach { layout ->
            compose.runOnIdle { settings = settings.copy(layoutOverride = layout, animations = false) }
            compose.waitForIdle()
            compose.onNodeWithText(Copy.collection).assertIsDisplayed()
        }
    }

    @Test fun homeTitleSurvivesNarrowWidthAndLargeFontInEveryArrangement() {
        val vm = walletViewModel()
        val cards = (0 until 6).map { i -> WalletCard(bank = BankPresets[i], nickname = "我的卡片 $i",
            lastFour = (1100 + i).toString(), position = i.toLong(), template = i,
            primary = TemplateCatalog.colors[i].first, secondary = TemplateCatalog.colors[i].second) }
        var settings by mutableStateOf(WalletSettings(animations = false))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MyCardTheme { Box(Modifier.width(320.dp).fillMaxHeight()) {
                    HomeScreen(vm, cards, settings, {}, {}, {}, {})
                } }
            }
        }
        CardLayout.entries.forEach { layout ->
            compose.runOnIdle { settings = settings.copy(layoutOverride = layout) }
            compose.waitForIdle()
            compose.onNodeWithText(Copy.collection).assertIsDisplayed()
            compose.onNodeWithText(cards.size.toString().padStart(2, '0')).assertIsDisplayed()
        }
    }

    @Test fun longestAllowedBankNameStaysInsideTheCardArtwork() {
        val name = "银".repeat(80)
        val card = WalletCard(bank = name, lastFour = "1234", nickname = "很长的昵称".repeat(16),
            tags = "标签".repeat(80), notes = "备注".repeat(500), template = 8,
            primary = TemplateCatalog.colors[8].first, secondary = TemplateCatalog.colors[8].second)
        assertTrue("80 characters is the accepted bank-name limit", CardRules.valid(card))
        assertFalse("81 characters must stay invalid", CardRules.valid(card.copy(bank = name + "行")))

        renderFace(card)
        val artwork = artworkBounds()
        val label = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        assertEquals(CARD_RATIO, artwork.width / artwork.height, .02f)
        assertTrue("bank name must not escape the artwork horizontally",
            label.left >= artwork.left - .5f && label.right <= artwork.right + .5f)
        assertTrue("bank name must not escape the artwork vertically",
            label.top >= artwork.top - .5f && label.bottom <= artwork.bottom + .5f)
    }

    @Test fun missingTailShowsThePlaceholderAndIsNotPersistable() {
        val draft = WalletCard(bank = "招商银行", lastFour = "")
        assertFalse("a card without its four digits stays a draft", CardRules.valid(draft))
        assertEquals("", CardRules.digits(""))
        renderFace(draft)
        compose.onNodeWithText("••••  0000").assertIsDisplayed()
        val artwork = artworkBounds()
        assertEquals(CARD_RATIO, artwork.width / artwork.height, .02f)
    }

    @Test fun customBankAndNetworkNamesRenderAsText() {
        val bank = "某不存在的发卡机构"
        var card by mutableStateOf(WalletCard(bank = bank, lastFour = "4321", network = Network.OTHER,
            customNetwork = "某卡组织", type = CardType.VIRTUAL))
        compose.setContent { MyCardTheme { Surface { Box(Modifier.padding(20.dp)) {
            CardFace(card, WalletSettings(animations = false), null, Modifier.testTag("face"))
        } } } }
        compose.onNodeWithText(bank).assertIsDisplayed()
        compose.onNodeWithText("某卡组织").assertIsDisplayed()
        compose.onNodeWithText(CardType.VIRTUAL.label).assertIsDisplayed()

        compose.runOnIdle { card = card.copy(network = Network.DISCOVER, customNetwork = "") }
        compose.waitForIdle()
        compose.onNodeWithText(Network.DISCOVER.label).assertIsDisplayed()
    }

    @Test fun everyTemplateKeepsCardTextReadableInEveryZone() {
        var scrimZones = 0
        var total = 0
        for (index in TemplateCatalog.colors.indices) {
            val (primary, secondary) = TemplateCatalog.colors[index]
            for (gradient in listOf(true, false)) for (zone in CardTextZone.entries) {
                total++
                val backgrounds = cardZoneBackgrounds(primary, secondary, gradient, zone)
                val ink = cardInkFor(backgrounds)
                val alpha = inkAlphaFor(backgrounds, ink, zone.preferredAlpha)
                val scrim = scrimAlphaFor(backgrounds, ink)
                if (scrim > 0f) scrimZones++
                val effective = if (scrim > 0f) backgrounds.map { blend(it, scrimColorFor(ink), scrim) } else backgrounds
                val worst = worstContrast(ink, effective, alpha)
                assertTrue("template $index gradient=$gradient zone=$zone inkAlpha=$alpha scrim=$scrim worst=$worst",
                    worst >= CARD_TEXT_CONTRAST)
            }
        }
        println("card zones needing a scrim: $scrimZones / $total")
        // Extremes need no help, and the two ink anchors stay the documented colours.
        assertEquals(CardInkDark, cardInkFor(listOf(Color.White)))
        assertEquals(CardInkLight, cardInkFor(listOf(Color.Black)))
        assertEquals(0f, scrimAlphaFor(listOf(Color.White), CardInkDark), .001f)
        assertEquals(0f, scrimAlphaFor(listOf(Color.Black), CardInkLight), .001f)
        // A zone that crosses a mid grey cannot be fixed by ink choice alone.
        assertTrue(scrimAlphaFor(listOf(Color(0xFF9E9E9E), Color(0xFF6E6E6E)), CardInkDark) > 0f)

        renderFace(WalletCard(bank = "纯白卡", lastFour = "1234", template = 14,
            primary = 0xFFFFFFFF, secondary = 0xFFFFFFFF, gradient = false))
        compose.onNodeWithText("纯白卡").assertIsDisplayed()
    }

    @Test fun singleAndManyCardsStayStableInEveryArrangement() {
        val many = (0 until 40).map { i -> WalletCard(bank = BankPresets[i % 7], nickname = "卡片 $i",
            lastFour = (9500 + i).toString(), position = i.toLong(), template = i % 16,
            primary = TemplateCatalog.colors[i % 16].first, secondary = TemplateCatalog.colors[i % 16].second) }
        val vm = walletViewModel()
        var cards by mutableStateOf(many.take(1))
        var settings by mutableStateOf(WalletSettings(animations = false))
        compose.setContent { MyCardTheme { HomeScreen(vm, cards, settings, {}, {}, {}, {}) } }

        listOf(1, 40).forEach { size ->
            CardLayout.entries.forEach { layout ->
                // A 40-page pager never reaches idle under Robolectric: the frame clock keeps
                // advancing. The device test GlobalExperienceInstrumentedTest owns 40 cards in the
                // pager arrangement, so here the pager keeps a proven multi-page count while the
                // long list still runs at 40 rows.
                val pager = layout == CardLayout.ALBUM
                val shown = if (pager) many.take(minOf(size, 10)) else many.take(size)
                compose.runOnIdle { cards = shown; settings = settings.copy(layoutOverride = layout, animations = false) }
                compose.waitForIdle()
                if (pager) {
                    compose.onNodeWithTag("wallet-pager").assertExists()
                    compose.onNodeWithText("1 / ${shown.size}").assertIsDisplayed()
                } else {
                    compose.onNodeWithTag("wallet-card-${shown.first().id}").assertExists()
                }
                compose.onNodeWithText(Copy.collection).assertIsDisplayed()
                compose.onNodeWithTag("home-layout-switch").assertIsDisplayed()
                compose.onNodeWithTag("home-layout-${layout.name}").assertIsSelected()
            }
        }
    }
}
