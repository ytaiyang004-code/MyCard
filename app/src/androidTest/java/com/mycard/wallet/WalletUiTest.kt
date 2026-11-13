package com.mycard.wallet

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.Copy
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun addEditSearchDeleteUndoAndRecreate() {
        runBlocking {
            CardRepository(compose.activity).clear()
            val preferences = Preferences(compose.activity)
            preferences.boolean("privacyLock", false)
            preferences.boolean("stacked", false)
            preferences.boolean("animations", false)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Copy.emptyTitle).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(Copy.add).performClick()
        compose.onNode(hasSetTextAction() and hasText(Copy.bank)).performScrollTo().performTextInput("界面测试银行")
        compose.onNode(hasSetTextAction() and hasText(Copy.lastFour)).performScrollTo().performTextInput("123456")
        compose.onNode(hasSetTextAction() and hasText(Copy.lastFour)).assertTextContains("1234")
        compose.onNodeWithText(Copy.save).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Copy.collection).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("界面测试银行").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("界面测试银行").performClick()
        compose.onNodeWithText(Copy.edit).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(Copy.nickname)).performScrollTo().performTextInput("旅行备用")
        compose.onNodeWithText(Copy.save).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Copy.details).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(Copy.back).performClick()
        compose.onNodeWithContentDescription(Copy.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("不存在的银行")
        compose.onNodeWithText(Copy.noResults).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithText("界面测试银行").performClick()
        compose.onNodeWithText(Copy.delete).performScrollTo().performClick()
        compose.onAllNodesWithText(Copy.delete).onLast().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Copy.undo).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(Copy.undo).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("界面测试银行").fetchSemanticsNodes().isNotEmpty() }
    }
}
