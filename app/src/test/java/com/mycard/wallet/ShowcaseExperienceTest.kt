package com.mycard.wallet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.*
import com.mycard.wallet.ui.theme.MyCardTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlinx.coroutines.flow.first

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShowcaseExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var original: WalletSettings
    @Before fun snapshotPreferences() { original = kotlinx.coroutines.runBlocking { com.mycard.wallet.data.Preferences(RuntimeEnvironment.getApplication()).flow.first() } }
    @After fun restorePreferences(): Unit = kotlinx.coroutines.runBlocking {
        val prefs=com.mycard.wallet.data.Preferences(RuntimeEnvironment.getApplication())
        prefs.text("theme",original.theme.name); prefs.boolean("stacked",original.stacked)
        prefs.boolean("tailBadge",original.tailBadge); prefs.boolean("animations",original.animations)
        prefs.boolean("privacyLock",original.privacyLock); prefs.text("sort",original.sort.name)
        original.paletteOverride?.let { prefs.text("palette",it.name) }; original.followSystem?.let { prefs.boolean("followSystem",it) }
        original.layoutOverride?.let { prefs.layout(it) }
    }

    private val cards=(0..9).map { WalletCard(bank=if(it%2==0) "中信银行" else "中国银行", nickname="藏品 $it",lastFour=(9300+it).toString(),
        network=if(it%2==0) Network.VISA else Network.UNIONPAY, template=6+it, primary=TemplateCatalog.colors[6+it].first,secondary=TemplateCatalog.colors[6+it].second, position=it.toLong()) }
    private fun selectOpenAndBoundaries(layout: CardLayout) {
        var opened: String?=null
        compose.setContent { MyCardTheme { CardShowcase(cards,WalletSettings(layoutOverride=layout),null,null,{}, { opened=it }) } }
        compose.onNodeWithContentDescription(Copy.previousCard).assertIsNotEnabled()
        compose.onNodeWithTag("wallet-pager").performTouchInput { swipeWithVelocity(androidx.compose.ui.geometry.Offset(width*.85f,centerY), androidx.compose.ui.geometry.Offset(width*.25f,centerY),endVelocity=0f,durationMillis=500) }
        compose.onNodeWithTag("wallet-card-${cards[1].id}").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(cards[1].id,opened) }
        compose.onNodeWithTag("wallet-pager").performScrollToIndex(9)
        compose.onNodeWithTag("wallet-card-${cards.last().id}").assertIsSelected()
        compose.onNodeWithContentDescription(Copy.nextCard).assertIsNotEnabled()
        compose.onNodeWithTag("wallet-pager").performTouchInput { swipeWithVelocity(androidx.compose.ui.geometry.Offset(width*.85f,centerY), androidx.compose.ui.geometry.Offset(width*.25f,centerY),endVelocity=0f,durationMillis=500) }
        compose.onNodeWithTag("wallet-card-${cards.last().id}").assertIsSelected()
    }
    @Test fun albumSwipeClickAndEdgesDoNotWrapUnexpectedly()=selectOpenAndBoundaries(CardLayout.ALBUM)
    @Test fun reorderUpdateAndDeleteKeepAnExistingCurrentIdentity() {
        var data by mutableStateOf(cards)
        var current by mutableStateOf<String?>(null)
        compose.setContent { MyCardTheme { CardShowcase(data,WalletSettings(layoutOverride=CardLayout.ALBUM),null,current,{current=it},{}) } }
        compose.onNodeWithTag("wallet-pager").performScrollToIndex(4)
        compose.onNodeWithTag("wallet-card-${cards[4].id}").assertIsSelected()
        compose.runOnIdle { assertEquals(cards[4].id,current); data=cards.reversed().map { it.copy(notes="更新备注") } }
        compose.onNodeWithTag("wallet-card-${cards[4].id}").assertIsSelected()
        compose.runOnIdle { data=data.filterNot{it.id==cards[4].id} }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(data.any{it.id==current}); assertNotEquals(cards[4].id,current) }
    }
    @Test fun pagerPositionSurvivesSavedStateRestorationAndSettingsChange() {
        val restore=StateRestorationTester(compose)
        var tailBadge by mutableStateOf(false)
        restore.setContent {
            var current by rememberSaveable { mutableStateOf<String?>(null) }
            MyCardTheme { CardShowcase(cards,WalletSettings(layoutOverride=CardLayout.ALBUM,tailBadge=tailBadge),null,current,{current=it},{}) }
        }
        compose.onNodeWithTag("wallet-pager").performScrollToIndex(6)
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("wallet-card-${cards[6].id}").assertIsSelected()
        compose.runOnIdle { tailBadge=true }
        compose.onNodeWithTag("wallet-card-${cards[6].id}").assertIsSelected()
    }
    @Test fun singleCardHasTwoDisabledBoundariesAndWorksWithoutEffects() {
        var opened: String?=null
        compose.setContent { MyCardTheme { CardShowcase(cards.take(1),WalletSettings(layoutOverride=CardLayout.ALBUM,animations=false),null,null,{}, { opened=it }) } }
        compose.onNodeWithContentDescription(Copy.previousCard).assertIsNotEnabled()
        compose.onNodeWithContentDescription(Copy.nextCard).assertIsNotEnabled()
        compose.onNodeWithTag("wallet-card-${cards[0].id}").performClick()
        assertEquals(cards[0].id,opened)
    }
    @Test fun allLayoutsKeepActionsAndFilteringAtNarrowWidthWithLargeText() {
        val app=RuntimeEnvironment.getApplication()
        val vm=WalletViewModel(app,CardRepository(app,TestVault()))
        var settings by mutableStateOf(WalletSettings(animations=false))
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                MyCardTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { HomeScreen(vm,cards,settings,{},{},{},{}) } }
            }
        }
        CardLayout.entries.forEach { layout ->
            compose.runOnIdle { settings=settings.copy(layoutOverride=layout) }
            compose.onNodeWithContentDescription(Copy.add).assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            CardLayout.entries.forEach { candidate ->
                compose.onNodeWithTag("home-layout-${candidate.name}").assertIsDisplayed()
            }
            compose.onNodeWithTag("home-layout-${layout.name}").assertIsSelected()
            compose.runOnIdle {
                val view=compose.activity.window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                File("build/ui-previews/v1.2.0/home-${layout.name}-320.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        }
        compose.onNodeWithContentDescription(Copy.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("9309")
        compose.onNodeWithTag("wallet-card-${cards.last().id}").assertIsSelected()
        compose.onNode(hasSetTextAction()).performTextReplacement("不存在的机构")
        compose.onNodeWithText(Copy.noResults).assertIsDisplayed()
    }
}
