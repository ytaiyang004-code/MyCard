package com.mycard.wallet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.*
import com.mycard.wallet.ui.theme.MyCardTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    // Menu interactions persist preferences; restore them so later classes see the stored defaults.
    private lateinit var original: WalletSettings
    @Before fun snapshotPreferences() { original = runBlocking { Preferences(RuntimeEnvironment.getApplication()).flow.first() } }
    @After fun restorePreferences(): Unit = runBlocking {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.text("theme", original.theme.name); prefs.boolean("stacked", original.stacked)
        prefs.boolean("tailBadge", original.tailBadge); prefs.boolean("animations", original.animations)
        prefs.boolean("privacyLock", original.privacyLock); prefs.text("sort", original.sort.name)
        original.paletteOverride?.let { prefs.text("palette", it.name) }
        original.followSystem?.let { prefs.boolean("followSystem", it) }
        original.layoutOverride?.let { prefs.layout(it) }
    }
    private fun snapshot(name: String, target: android.view.View = compose.activity.window.decorView): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = target
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/ui-previews/v${BuildConfig.VERSION_NAME}/$name.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }
    @Test fun renderSixteenThemesWithRealBrandsNotesAndAllLogoPositions() {
        var card by mutableStateOf(WalletCard(bank = "中国工商银行", lastFour = "2026"))
        compose.setContent { MyCardTheme { Surface { Box(Modifier.padding(20.dp)) { CardFace(card, WalletSettings(), null) } } } }
        (0..15).forEach { id ->
            val colors = TemplateCatalog.colors[id]
            compose.runOnIdle { card = card.copy(template = id, primary = colors.first, secondary = colors.second,
                bank = BankPresets[id % 7], network = Network.entries[id % 5], notes = "只用于日常与旅行，这是很长的备注文字，应限制两行并自动省略。",
                showNotes = true, logoPosition = LogoPosition.entries[id % 3], amexLayout = id % 5 == 4) }
            compose.onNodeWithText(card.bank).assertIsDisplayed()
            snapshot("theme-$id").recycle()
        }
        compose.runOnIdle { card = card.copy(showWordmark = false, showNotes = false) }
        compose.onNodeWithText("MyCard").assertDoesNotExist()
        compose.onNodeWithText(card.notes).assertDoesNotExist()
        compose.onNodeWithText("PRIVATE COLLECTION").assertDoesNotExist()
    }
    @Test fun visualPaletteDragAndHueSlidersChangeBothColorsIndependently() {
        var primary by mutableLongStateOf(0xFF304F46)
        var secondary by mutableLongStateOf(0xFF7B9B80)
        compose.setContent { MyCardTheme { Surface { Column(Modifier.padding(20.dp)) {
            ColorSelector(Copy.primary, primary, { primary = it }) { CardFace(WalletCard(primary = primary, secondary = secondary), WalletSettings(), null) }
            ColorSelector(Copy.secondary, secondary, { secondary = it }) { CardFace(WalletCard(primary = primary, secondary = secondary), WalletSettings(), null) }
        } } } }
        compose.onAllNodesWithText("调色").onFirst().performClick()
        val original = primary
        compose.onNodeWithTag("color-board").performScrollTo().performTouchInput { swipe(Offset(width * .2f, height * .8f), Offset(width * .8f, height * .2f)) }
        assertNotEquals(original, primary)
        assertEquals(0xFF7B9B80, secondary)
        compose.onNodeWithContentDescription("色相").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(240f) }
        compose.onNodeWithText("完成").performScrollTo().performClick()
        compose.onAllNodesWithText("调色").onLast().performClick()
        compose.onNodeWithTag("color-board").performScrollTo().performTouchInput { click(Offset(width * .7f, height * .3f)) }
        assertNotEquals(0xFF7B9B80, secondary)
        snapshot("color-palette", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView).recycle()
    }
    @Test fun stackCornersRevealUnderlyingCardWithoutGapAndListRemainsSeparate() {
        val app = RuntimeEnvironment.getApplication()
        val vm = WalletViewModel(app, CardRepository(app, TestVault()))
        val first = WalletCard(bank = "上层银行", lastFour = "1111", primary = 0xFFFF0000, gradient = false, texture = false, position = 0)
        val second = WalletCard(bank = "下层银行", lastFour = "2222", primary = 0xFF0000FF, gradient = false, texture = false, position = 1)
        var settings by mutableStateOf(WalletSettings(animations = false))
        compose.setContent { MyCardTheme { HomeScreen(vm, listOf(first, second), settings, {}, {}, {}, {}) } }
        val a = compose.onNodeWithTag("wallet-card-${first.id}").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("wallet-card-${second.id}").fetchSemanticsNode().boundsInRoot
        assertEquals(a.bottom, b.top, .5f)
        val bitmap = snapshot("stack-corners")
        assertEquals(android.graphics.Color.RED, bitmap.getPixel((b.left + 4).toInt(), (b.top + 4).toInt()))
        bitmap.recycle()
        compose.runOnIdle { settings = settings.copy(stacked = false) }
        val c = compose.onNodeWithTag("wallet-card-${first.id}").fetchSemanticsNode().boundsInRoot
        val d = compose.onNodeWithTag("wallet-card-${second.id}").fetchSemanticsNode().boundsInRoot
        assertTrue(d.top > c.bottom)
    }
    @Test fun expandingAStackedCardKeepsAdvanceHitAreaAndOpenBehaviour() {
        val app = RuntimeEnvironment.getApplication()
        val vm = WalletViewModel(app, CardRepository(app, TestVault()))
        val first = WalletCard(bank = "上层银行", lastFour = "1111", position = 0)
        val second = WalletCard(bank = "下层银行", lastFour = "2222", position = 1)
        var opened: String? = null
        compose.setContent { MyCardTheme {
            HomeScreen(vm, listOf(first, second), WalletSettings(), {}, { opened = it }, {}, {})
        } }
        // First tap selects and expands the card; wait for the spring to settle before measuring.
        compose.onNodeWithText("上层银行").performClick()
        compose.waitForIdle()
        val expanded = compose.onNodeWithTag("wallet-card-${first.id}").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("wallet-card-${second.id}").fetchSemanticsNode().boundsInRoot
        assertTrue("advance ${expanded.height} must equal the painted card height ${expanded.width / CARD_RATIO}",
            kotlin.math.abs(expanded.height - expanded.width / CARD_RATIO) < 2f)
        assertEquals(expanded.bottom, next.top, .5f)
        // Semantics unchanged: the next tap on the same card opens the detail view.
        compose.onNodeWithText("上层银行").performClick()
        assertEquals(first.id, opened)
    }

    @Test fun layoutSwitchMarksCurrentModeAndPersistsSelection() {
        val app = RuntimeEnvironment.getApplication()
        val vm = WalletViewModel(app, CardRepository(app, TestVault()))
        compose.setContent {
            val settings by vm.settings.collectAsState()
            settings?.let { MyCardTheme { HomeScreen(vm, emptyList(), it, {}, {}, {}, {}) } }
        }
        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); vm.settings.value != null }
        compose.onNodeWithTag("home-layout-switch").assertIsDisplayed()
        CardLayout.entries.forEach { compose.onNodeWithTag("home-layout-${it.name}").assertIsDisplayed() }

        val initial = requireNotNull(vm.settings.value?.layout)
        compose.onNodeWithTag("home-layout-${initial.name}").assertIsSelected()
        val target = if (initial == CardLayout.LIST) CardLayout.ALBUM else CardLayout.LIST
        compose.onNodeWithTag("home-layout-${target.name}").performClick()
        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); vm.settings.value?.layout == target }
        compose.onNodeWithTag("home-layout-${target.name}").assertIsSelected()
        assertEquals(target == CardLayout.STACK, runBlocking { Preferences(app).flow.first().stacked })

        // Sorting is its own entry now and still persists independently.
        compose.onNodeWithContentDescription(Copy.sorting).performClick()
        compose.onNodeWithContentDescription(Copy.currentSort).assertExists()
        compose.onNodeWithText(SortOrder.NEWEST.label).performClick()
        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); vm.settings.value?.sort == SortOrder.NEWEST }
        assertEquals(SortOrder.NEWEST, runBlocking { Preferences(app).flow.first().sort })
    }
    // Exact bank-alias matching only existed to pick a bundled official emblem. The public edition
    // bundles no trademark artwork, so the matcher and this test are gone; brand names render as
    // text (see BRAND_NOTICE.md).
}
