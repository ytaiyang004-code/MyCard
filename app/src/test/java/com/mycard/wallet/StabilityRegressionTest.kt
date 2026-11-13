package com.mycard.wallet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.*
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** View.draw is a rendering regression, not an emulation of HyperOS screenshot gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StabilityRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000) {
        ShadowLooper.idleMainLooper(); condition()
    }
    private fun launch(cards: List<WalletCard> = emptyList()): WalletViewModel {
        val app = RuntimeEnvironment.getApplication()
        val repo = CardRepository(app, TestVault())
        runBlocking {
            repo.clear(); cards.forEach { repo.save(it) }
            Preferences(app).boolean("animations", false)
            Preferences(app).boolean("privacyLock", false)
            // An upgrade must ignore the formerly enabled capture restriction.
            Preferences(app).boolean("protectPreview", true)
        }
        val vm = WalletViewModel(app, repo)
        compose.setContent { WalletApp(vm, authenticate = { vm.unlocked.value = true }, onLockedBack = {}) }
        waitFor { vm.settings.value != null && vm.cardsLoaded.value }
        compose.waitForIdle()
        return vm
    }
    private fun render(view: android.view.View = compose.activity.window.decorView) {
        compose.runOnIdle {
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image)); image.recycle()
        }
    }
    @Test fun duplicateAndStaleCompletionCannotPopHomeOrAnotherEditor() {
        var finish: (() -> Unit)? = null
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, "home") {
                composable("home") { Button({ nav.navigate("editor") }) { Text("enter") } }
                composable("editor") { entry ->
                    finish = { nav.returnFrom(entry) }
                    Text("editor")
                }
            }
        }
        compose.onNodeWithText("enter").performClick()
        compose.onNodeWithText("editor").assertIsDisplayed()
        val old = finish!!
        compose.runOnIdle { old(); old() }
        compose.onNodeWithText("enter").assertIsDisplayed()
        compose.onNodeWithText("enter").performClick()
        compose.runOnIdle { old() }
        compose.onNodeWithText("editor").assertIsDisplayed()
        compose.runOnIdle { finish!!() }
        compose.onNodeWithText("enter").assertIsDisplayed()
    }
    @Test fun renderedHomeStillScrollsAndOpensCardsInBothLayoutsAfterBackground() {
        val cards = (0..29).map { WalletCard(bank = "测试银行 $it", lastFour = "1234", position = it.toLong()) }
        val vm = launch(cards)
        listOf(true, false).forEach { stacked ->
            compose.runOnIdle { vm.boolean("stacked", stacked) }
            waitFor { vm.settings.value?.stacked == stacked }
            render()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performTouchInput { swipeUp() }
            compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(20)
            compose.onNodeWithText("测试银行 20").performClick()
            if (stacked) compose.onNodeWithText("测试银行 20").performClick()
            compose.onNodeWithText(Copy.details).assertIsDisplayed()
            compose.onNodeWithContentDescription(Copy.back).performClick()
            compose.onNodeWithText(Copy.collection).assertIsDisplayed()
        }
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }
    @Test fun captureEditBackgroundSaveAndDiscardLeaveUsableHome() {
        val vm = launch()
        compose.onNodeWithText(Copy.add).performClick()
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().performTextInput("保留银行")
        render()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // transient pause
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNode(hasSetTextAction() and hasText(Copy.lastFour)).performScrollTo().performTextInput("987654")
        compose.onNode(hasSetTextAction() and hasText(Copy.lastFour)).assertTextContains("9876")
        compose.runOnIdle {
            val info = android.view.inputmethod.EditorInfo()
            assertNotNull(compose.activity.window.decorView.findFocus().onCreateInputConnection(info))
            assertEquals(android.text.InputType.TYPE_CLASS_NUMBER, info.inputType and android.text.InputType.TYPE_MASK_CLASS)
            assertNotEquals(android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                info.inputType and android.text.InputType.TYPE_MASK_VARIATION)
        }
        compose.onNodeWithText(Copy.save).performClick()
        waitFor { vm.draft.value == null && !vm.busy.value }
        compose.onNodeWithText(Copy.collection).assertIsDisplayed()
        runBlocking { assertEquals("保留银行", vm.repository.all().single().bank) }
        compose.onNodeWithContentDescription(Copy.add).performClick()
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().performTextInput("放弃的卡")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(Copy.discard).performClick()
        compose.onNodeWithText(Copy.collection).assertIsDisplayed()
        compose.onNodeWithContentDescription(Copy.settings).performClick()
        compose.onNodeWithText(Copy.theme).assertIsDisplayed()
        compose.onNodeWithText("保护最近任务预览").assertDoesNotExist()
        assertEquals(1, runBlocking { vm.repository.all().size })
    }
    @Test fun lockKeepsEditorAndDialogStateAndUnlockRestoresInput() {
        val vm = launch()
        compose.onNodeWithText(Copy.add).performClick()
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().performTextInput("未保存草稿")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(Copy.unsaved).assertIsDisplayed()
        compose.runOnIdle { vm.unlocked.value = true; vm.boolean("privacyLock", true) }
        waitFor { vm.settings.value?.privacyLock == true }
        compose.runOnIdle { vm.onHostStopped(false) }
        compose.onNodeWithText(Copy.unlock).assertIsDisplayed()
        compose.onNodeWithText(Copy.unsaved).assertDoesNotExist()
        compose.onNodeWithText(Copy.unlock).performClick()
        compose.onNodeWithText(Copy.unsaved).assertIsDisplayed()
        compose.onNodeWithText(Copy.cancel).performClick()
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).assertTextContains("未保存草稿")
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performTextInput("继续")
        assertEquals("未保存草稿继续", vm.draft.value?.bank)
    }
    @Test fun imageWindowAllowsCaptureAndCancelWhileWorkingThenInputStillWorks() {
        val vm = launch()
        compose.onNodeWithText(Copy.add).performClick()
        compose.onNodeWithText(Copy.realFace).performClick()
        compose.runOnIdle {
            vm.importBitmap.value = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        }
        compose.onNodeWithText("处理卡面").assertIsDisplayed()
        val dialog = ShadowDialog.getLatestDialog()
        assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        render(dialog.window!!.decorView)
        compose.runOnIdle { vm.importWorking.value = true }
        compose.onNodeWithText(Copy.cancel).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("处理卡面").assertDoesNotExist()
        assertFalse(vm.importWorking.value)
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().performTextInput("截图后仍可输入")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(Copy.discard).performClick()
        compose.onNodeWithText(Copy.emptyTitle).assertIsDisplayed()
    }
    @Test fun imageErrorAndLockRoundTripKeepBackAndCropStateUsable() {
        val vm = launch()
        compose.onNodeWithText(Copy.add).performClick()
        compose.onNodeWithText(Copy.realFace).performClick()
        val mask = com.mycard.wallet.images.Mask(.1f, .1f, .2f, .2f)
        compose.runOnIdle {
            vm.importBitmap.value = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            vm.trimImage() // Too small to trim: exercise the actual asynchronous failure path.
        }
        waitFor { !vm.importWorking.value && vm.importError.value != null }
        compose.runOnIdle {
            vm.importMasks.value = listOf(mask)
            vm.unlocked.value = true
            vm.boolean("privacyLock", true)
        }
        waitFor { vm.settings.value?.privacyLock == true }
        compose.runOnIdle { vm.onHostStopped(false) }
        compose.onNodeWithText(Copy.unlock).assertIsDisplayed()
        compose.onNodeWithText("处理卡面").assertDoesNotExist()
        compose.onNodeWithText(Copy.unlock).performClick()
        compose.onNodeWithText("处理卡面").assertIsDisplayed()
        assertEquals(listOf(mask), vm.importMasks.value)
        compose.runOnIdle {
            (ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("处理卡面").assertDoesNotExist()
        assertNull(vm.importBitmap.value)
        assertFalse(vm.importWorking.value)
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().performTextInput("错误后可以继续")
    }
}
