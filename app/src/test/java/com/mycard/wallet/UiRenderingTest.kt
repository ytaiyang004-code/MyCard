package com.mycard.wallet

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.*
import com.mycard.wallet.ui.theme.MyCardTheme
import kotlinx.coroutines.runBlocking
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
class UiRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun preview(name: String) {
        val file = File("build/ui-previews/$name.png").apply { parentFile!!.mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun demo(index: Int) = WalletCard(bank = listOf("日常储蓄", "旅行备用", "城市生活", "私人珍藏", "远行计划", "灵感账户")[index],
        network = Network.entries[index], lastFour = "20${24 + index}", nickname = listOf("日常开支", "探索世界", "自由生活", "日常备用", "远行计划", "我的收藏")[index],
        tags = "私人收藏", position = index.toLong(), template = index, primary = TemplateCatalog.colors[index].first, secondary = TemplateCatalog.colors[index].second)
    @Test fun renderAllSixTemplatesAtStandardRatio() {
        var card by mutableStateOf(demo(0))
        compose.setContent { MyCardTheme { Surface { Box(Modifier.padding(20.dp)) { CardFace(card, WalletSettings(), null) } } } }
        (0..5).forEach { i ->
            compose.runOnIdle { card = demo(i) }
            compose.waitForIdle()
            compose.onAllNodesWithText(demo(i).bank).onFirst().assertIsDisplayed()
            preview("template-$i")
        }
    }
    @Test fun emptyHomeThenStackAndListHaveWorkingCallbacks() {
        val app = RuntimeEnvironment.getApplication()
        val vm = WalletViewModel(app, CardRepository(app, TestVault()))
        var cards by mutableStateOf<List<WalletCard>>(emptyList())
        var settings by mutableStateOf(WalletSettings(animations = false))
        var dark by mutableStateOf(true)
        var opened: String? = null
        compose.setContent { MyCardTheme(dark) {
            HomeScreen(vm, cards, settings, onAdd = {}, onOpen = { opened = it }, onSettings = {}, onReorder = {})
        } }
        compose.onNodeWithText(Copy.emptyTitle).assertIsDisplayed()
        preview("home-empty")
        compose.runOnIdle { cards = (0..5).map(::demo) }
        compose.waitForIdle()
        preview("home-stack-dark")
        // First tap expands a collapsed card, second opens details.
        compose.onNodeWithText("日常储蓄").performClick()
        assertNull(opened)
        compose.onNodeWithText("日常储蓄").performClick()
        assertEquals(cards.first().id, opened)
        compose.runOnIdle { settings = settings.copy(stacked = false); dark = false }
        compose.waitForIdle()
        preview("home-list-light")
        compose.onNodeWithContentDescription(Copy.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("没有匹配的卡")
        compose.onNodeWithText(Copy.noResults).assertIsDisplayed()
    }
    @Test fun editorSavesFourDigitsThroughRealRoomAndViewModel() {
        val app = RuntimeEnvironment.getApplication()
        val repo = CardRepository(app, TestVault())
        runBlocking { repo.clear() }
        val vm = WalletViewModel(app, repo)
        vm.beginEdit(null)
        var saved by mutableStateOf(false)
        compose.setContent { MyCardTheme {
            if (!saved) EditorScreen(vm, WalletSettings(animations = false)) { saved = true }
        } }
        preview("editor")
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().performTextInput("本地测试银行")
        compose.onNode(hasSetTextAction() and hasText(Copy.lastFour)).performScrollTo().performTextInput("12345678")
        compose.onNode(hasSetTextAction() and hasText(Copy.lastFour)).assertTextContains("1234")
        compose.onNodeWithText(Copy.save).assertIsEnabled().performClick()
        compose.waitUntil(15_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            saved
        }
        runBlocking { assertEquals("1234", repo.all().single().lastFour); repo.clear() }
    }
}
