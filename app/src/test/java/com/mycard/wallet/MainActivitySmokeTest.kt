package com.mycard.wallet

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mycard.wallet.ui.Copy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivitySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualActivityLaunchNavigationAndCaptureAllowed() {
        compose.waitUntil(15_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodesWithText(Copy.emptyTitle).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        compose.onNodeWithContentDescription(Copy.settings).performClick()
        compose.onNodeWithText(Copy.theme).assertIsDisplayed()
        compose.onNodeWithText(com.mycard.wallet.data.AppPalette.WHITE.label).performClick()
        compose.onNodeWithContentDescription(Copy.back).performClick()
        compose.onNodeWithText(Copy.add).performClick()
        compose.onNodeWithText(Copy.realFace).assertIsDisplayed()
        compose.onNodeWithContentDescription(Copy.back).performClick()
        compose.onNodeWithText(Copy.emptyTitle).assertIsDisplayed()
    }
    @Test fun editorSurvivesActivityRecreationAndBackgroundWithBackWorking() {
        compose.waitUntil(15_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodesWithText(Copy.emptyTitle).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(Copy.add).performClick()
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().performTextInput("重建前的草稿")
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().assertTextContains("重建前的草稿")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performTextInput("恢复")
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(Copy.discard).performClick()
        compose.onNodeWithText(Copy.emptyTitle).assertIsDisplayed()
    }
    @Test fun restoredEditorWithoutMemoryDraftFallsBackToHome() {
        compose.waitUntil(15_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodesWithText(Copy.emptyTitle).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(Copy.add).performClick()
        compose.runOnIdle { ViewModelProvider(compose.activity)[WalletViewModel::class.java].discardDraft() }
        compose.onNodeWithText(Copy.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(Copy.add).performClick()
        compose.onNodeWithText(Copy.realFace).assertIsDisplayed()
    }
    @Test fun filterSelectionSurvivesActivityRecreation() {
        compose.waitUntil(15_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodesWithText(Copy.emptyTitle).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("home-filter-UNIONPAY").performClick()
        compose.onNodeWithTag("home-filter-UNIONPAY").assertIsSelected()

        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodesWithTag("home-filter-UNIONPAY").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("home-filter-UNIONPAY").assertIsSelected()
    }
}
