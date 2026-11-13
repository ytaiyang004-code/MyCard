package com.mycard.wallet

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mycard.wallet.data.Preferences
import com.mycard.wallet.security.Crypto
import com.mycard.wallet.ui.Copy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Activity recreation/navigation in a JVM sandbox; empty card data never requires a device key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val password = "lifecycle-test-password"

    @Before fun prepareTestPreferences() = runBlocking {
        val preferences = Preferences(RuntimeEnvironment.getApplication())
        preferences.boolean("privacyLock", false)
        preferences.boolean("animations", false)
    }

    @After fun resetTestLock(): Unit = runBlocking {
        Preferences(RuntimeEnvironment.getApplication()).boolean("privacyLock", false)
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(20_000) {
        ShadowLooper.idleMainLooper(); condition()
    }

    private fun settings() {
        compose.onNodeWithContentDescription(Copy.settings).performClick()
        compose.onNodeWithText(Copy.theme).assertIsDisplayed()
    }

    private fun emptyArchive(): ByteArray {
        val manifest = JSONObject().put("version", 2).put("cards", JSONArray()).toString().toByteArray()
        return ByteArrayOutputStream().also { raw ->
            DataOutputStream(Crypto.backupSink(raw, password.toCharArray())).use {
                it.writeInt(manifest.size); it.write(manifest); it.writeInt(0)
            }
        }.toByteArray()
    }

    private fun operationSurvives(restoring: Boolean, recreate: Boolean, finishWhileAway: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        waitFor { compose.onAllNodesWithText(Copy.collection).fetchSemanticsNodes().isNotEmpty() }
        settings()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val uri = Uri.parse("content://mycard-test/lifecycle/${UUID.randomUUID()}")
        if (restoring) {
            val stream = object : ByteArrayInputStream(emptyArchive()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Timed out waiting for recreation" }
                    return super.read(bytes, offset, length)
                }
            }
            shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { opens.incrementAndGet(); stream }
        } else {
            val stream = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Timed out waiting for recreation" }
                    super.write(bytes, offset, length)
                }
            }
            shadowOf(app.contentResolver).registerOutputStreamSupplier(uri) { opens.incrementAndGet(); stream }
        }
        // Exercise the same ViewModel entry point as SettingsScreen. Password-dialog IME/layout
        // emulation is outside this lifecycle suite; Activity, NavHost and result UI remain real.
        val vm = ViewModelProvider(compose.activity)[WalletViewModel::class.java]
        compose.runOnIdle {
            val id = vm.requestBackupDocument(if (restoring) BackupAction.RESTORE else BackupAction.EXPORT)!!
            vm.backupDocumentSelected(id, uri)
            assertTrue(vm.startBackup(id, password.toCharArray()))
        }
        try {
            waitFor { entered.count == 0L }
            if (recreate) compose.activityRule.scenario.recreate()
            else {
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText(Copy.collection).assertIsDisplayed()
                if (finishWhileAway) {
                    release.countDown()
                    waitFor { vm.backupOperation.value.step == BackupStep.SUCCEEDED }
                }
                settings()
            }
            compose.onNodeWithText(Copy.theme).assertIsDisplayed()
            if (!finishWhileAway) compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
            assertEquals("page changes must not restart the operation", 1, opens.get())
        } finally { release.countDown() }
        val success = if (restoring) "已恢复 0 张卡片。" else "加密备份已保存。"
        waitFor { compose.onAllNodesWithText(success).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(success).assertIsDisplayed()
        assertEquals(1, opens.get())
    }

    @Test fun exportSurvivesActivityRecreation() = operationSurvives(restoring = false, recreate = true)
    @Test fun restoreSurvivesActivityRecreation() = operationSurvives(restoring = true, recreate = true)
    @Test fun exportSurvivesLeavingAndReturningToSettings() = operationSurvives(restoring = false, recreate = false)
    @Test fun restoreSurvivesLeavingAndReturningToSettings() = operationSurvives(restoring = true, recreate = false)
    @Test fun resultCompletedAwayFromSettingsIsDisplayedOnReturn() = operationSurvives(restoring = false, recreate = false, finishWhileAway = true)

    @Test fun filePickerCancellationAfterRecreationDoesNotStartWork() {
        waitFor { compose.onAllNodesWithText(Copy.collection).fetchSemanticsNodes().isNotEmpty() }
        settings()
        compose.onNodeWithText(Copy.backup).performScrollTo().performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        val vm = ViewModelProvider(compose.activity)[WalletViewModel::class.java]
        assertEquals(BackupStep.SELECTING, vm.backupOperation.value.step)
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, Intent()) }
        waitFor { vm.backupOperation.value.step == BackupStep.IDLE }
        compose.onNodeWithText(Copy.backup).assertIsEnabled()
        assertTrue(runBlocking { vm.repository.all().isEmpty() })
    }

    @Test fun backgroundLockHidesResultsUntilUnlockWithoutCancellingTheOperation() {
        val app = RuntimeEnvironment.getApplication()
        waitFor { compose.onAllNodesWithText(Copy.collection).fetchSemanticsNodes().isNotEmpty() }
        val vm = ViewModelProvider(compose.activity)[WalletViewModel::class.java]
        runBlocking { Preferences(app).boolean("animations", false); Preferences(app).boolean("privacyLock", true) }
        compose.runOnIdle { vm.unlocked.value = true }
        waitFor { vm.settings.value?.privacyLock == true }
        settings()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://mycard-test/background/${UUID.randomUUID()}")
        shadowOf(app.contentResolver).registerOutputStream(uri, object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                super.write(bytes, offset, length)
            }
        })
        compose.runOnIdle {
            val id = vm.requestBackupDocument(BackupAction.EXPORT)!!
            vm.backupDocumentSelected(id, uri)
            vm.startBackup(id, password.toCharArray())
        }
        try {
            waitFor { entered.count == 0L }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(vm.unlocked.value)
        } finally { release.countDown() }
        waitFor { vm.backupOperation.value.step == BackupStep.SUCCEEDED }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(Copy.unlock).assertIsDisplayed()
        compose.onNodeWithText("加密备份已保存。").assertDoesNotExist()
        // Simulate only the successful biometric callback; no claim of hardware authentication coverage.
        compose.runOnIdle { vm.unlocked.value = true }
        compose.onNodeWithText("加密备份已保存。").assertIsDisplayed()
    }
}
