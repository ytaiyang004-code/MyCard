package com.mycard.wallet

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import android.net.Uri
import com.mycard.wallet.data.Preferences
import com.mycard.wallet.ui.Copy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Activity recreation; the sandbox wallet is empty, so no device Keystore is accessed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupRepeatLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val ids = mutableSetOf<String>()
    @Before fun prepare(): Unit = runBlocking {
        Preferences(RuntimeEnvironment.getApplication()).boolean("privacyLock", false)
        Preferences(RuntimeEnvironment.getApplication()).boolean("animations", false)
    }
    @After fun resetLock(): Unit = runBlocking { Preferences(RuntimeEnvironment.getApplication()).boolean("privacyLock", false) }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(20_000) { ShadowLooper.idleMainLooper(); condition() }

    private fun operation(vm: WalletViewModel, action: BackupAction, bytes: ByteArray? = null): ByteArray {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val output = ByteArrayOutputStream()
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val uri = Uri.parse("content://mycard-synthetic/lifecycle/${UUID.randomUUID()}")
        if (action == BackupAction.EXPORT) {
            shadowOf(resolver).registerOutputStreamSupplier(uri) {
                opens.incrementAndGet()
                object : ByteArrayOutputStream() {
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        entered.countDown(); check(release.await(20, TimeUnit.SECONDS))
                        output.write(buffer, offset, length)
                    }
                    override fun write(value: Int) { output.write(value) }
                }
            }
        } else {
            shadowOf(resolver).registerInputStreamSupplier(uri) {
                opens.incrementAndGet()
                object : ByteArrayInputStream(bytes!!) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        entered.countDown(); check(release.await(20, TimeUnit.SECONDS))
                        return super.read(buffer, offset, length)
                    }
                }
            }
        }
        val chars = "synthetic-lifecycle-2026".toCharArray()
        compose.runOnIdle {
            val id = vm.requestBackupDocument(action)!!
            assertTrue(ids.add(id))
            vm.backupDocumentSelected(id, uri)
            assertTrue(vm.startBackup(id, chars))
        }
        try {
            waitFor { entered.count == 0L }
            if (action == BackupAction.RESTORE) assertTrue("password must remain intact before header/KDF", chars.any { it != '\u0000' })
            compose.activityRule.scenario.recreate()
            assertSame(vm, ViewModelProvider(compose.activity)[WalletViewModel::class.java])
            assertEquals(BackupStep.RUNNING, vm.backupOperation.value.step)
            assertEquals(1, opens.get())
        } finally { release.countDown() }
        waitFor { vm.backupOperation.value.step == BackupStep.SUCCEEDED }
        assertTrue(chars.all { it == '\u0000' })
        assertEquals(1, opens.get())
        val message = if (action == BackupAction.EXPORT) "加密备份已保存。" else "已恢复 0 张卡片。"
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        waitFor { vm.backupOperation.value.step == BackupStep.IDLE }
        return output.toByteArray()
    }

    @Test fun twoExportsAndTwoRestoresSurviveRecreationWithoutReusingState() {
        waitFor { compose.onAllNodesWithText(Copy.collection).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(Copy.settings).performClick()
        val vm = ViewModelProvider(compose.activity)[WalletViewModel::class.java]
        assertTrue(runBlocking { vm.repository.all().isEmpty() })
        val first = operation(vm, BackupAction.EXPORT)
        val second = operation(vm, BackupAction.EXPORT)
        assertFalse(first.contentEquals(second))
        for (backup in listOf(first, second)) {
            runBlocking { vm.repository.clear() }
            operation(vm, BackupAction.RESTORE, backup)
        }
        assertEquals(4, ids.size)
    }
}
