package com.mycard.wallet

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mycard.wallet.data.CardRepository
import com.mycard.wallet.ui.Copy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on an unlocked dedicated test device. This class does not delete or save any card. */
@RunWith(AndroidJUnit4::class)
class CaptureStabilityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun surfaceCaptureThenBackgroundRecreateEditAndBackPreservesStoredCards() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(Copy.collection).fetchSemanticsNodes().isNotEmpty() }
        val repo = CardRepository(compose.activity)
        val before = runBlocking { repo.all().sortedBy { it.id } }
        fun capture() {
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull("System surface capture must succeed", bitmap)
            bitmap!!.recycle()
        }
        capture()
        if (before.isNotEmpty()) {
            compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performTouchInput { swipeUp(); swipeDown() }
        }
        compose.onNodeWithContentDescription(Copy.add).performClick()
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().performTextInput("截图回归临时草稿")
        capture()
        compose.onNode(hasSetTextAction() and hasText(Copy.lastFour)).performScrollTo().performTextInput("1234")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().assertTextContains("截图回归临时草稿")
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performTextInput("恢复")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(Copy.discard).performClick()
        compose.onNodeWithText(Copy.collection).assertIsDisplayed()
        assertEquals(before, runBlocking { repo.all().sortedBy { it.id } })
    }
}
