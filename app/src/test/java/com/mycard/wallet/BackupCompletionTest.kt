package com.mycard.wallet

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.mycard.wallet.data.CardJson
import com.mycard.wallet.data.CardRepository
import com.mycard.wallet.data.WalletCard
import com.mycard.wallet.security.BackupManager
import com.mycard.wallet.security.Crypto
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only synthetic cards, a software test key and isolated Robolectric directories are used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCompletionTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val stores = mutableListOf<ViewModelStore>()
    @After fun clearModels() { stores.forEach { it.clear() }; ShadowLooper.idleMainLooper() }

    private fun model(repository: CardRepository): WalletViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WalletViewModel(RuntimeEnvironment.getApplication(), repository) as T
        }
        return ViewModelProvider(store, factory)[WalletViewModel::class.java].also { vm ->
            waitFor { vm.cardsLoaded.value }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            check(System.nanoTime() < deadline) { "Timed out waiting for the ViewModel operation" }
            Thread.sleep(5)
        }
    }

    private fun startRestore(vm: WalletViewModel, uri: Uri, password: CharArray = "completion-test-password".toCharArray()): String {
        val id = vm.requestBackupDocument(BackupAction.RESTORE)!!
        vm.backupDocumentSelected(id, uri)
        assertTrue(vm.startBackup(id, password))
        return id
    }
    private fun repository(): CardRepository {
        val root = File(app.cacheDir, "bc")
        val database = File(root, "db").apply { mkdirs() }
        val artwork = File(root, "art").apply { mkdirs() }
        return CardRepository(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(database, name)
            override fun getNoBackupFilesDir() = artwork
        }, TestVault())
    }

    private fun archive(card: WalletCard): ByteArray {
        val manifest = JSONObject().put("version", 2).put("cards", JSONArray().put(CardJson.encode(card)))
            .toString().toByteArray()
        return ByteArrayOutputStream().also { raw ->
            DataOutputStream(Crypto.backupSink(raw, "completion-test-password".toCharArray())).use {
                it.writeInt(manifest.size); it.write(manifest); it.writeInt(0)
            }
        }.toByteArray()
    }

    private fun input(stream: ByteArrayInputStream): Uri {
        val uri = Uri.parse("content://mycard-test/completion/${UUID.randomUUID()}")
        shadowOf(app.contentResolver).registerInputStream(uri, stream)
        return uri
    }

    @Test fun committedRestoreIsNotReportedAsFailureWhenClosingTheStreamFails() = runBlocking {
        val repository = repository()
        val incoming = WalletCard(bank = "Synthetic restored card", lastFour = "1234")
        val uri = input(object : ByteArrayInputStream(archive(incoming)) {
            override fun close() { throw IOException("Synthetic close failure") }
        })
        val password = "completion-test-password".toCharArray()
        val result = runCatching { BackupManager(app, repository).restore(uri, password) }
        assertEquals("the transaction has already committed", incoming, repository.all().single())
        assertTrue("a close failure must not turn committed data into a failed restore: $result", result.isSuccess)
        assertEquals(1, result.getOrThrow())
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun wrongPasswordWithACloseFailureDoesNotCommit() = runBlocking {
        val repository = repository()
        repository.save(WalletCard(bank = "Existing synthetic card", lastFour = "5678"))
        val before = repository.all()
        val incoming = WalletCard(bank = "Must not be restored", lastFour = "1234")
        val uri = input(object : ByteArrayInputStream(archive(incoming)) {
            override fun close() { throw IOException("Synthetic close failure") }
        })
        val password = "incorrect-test-password".toCharArray()
        assertTrue(runCatching { BackupManager(app, repository).restore(uri, password) }.isFailure)
        assertEquals(before, repository.all())
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun corruptTerminatorDoesNotCommit() = runBlocking {
        val repository = repository()
        repository.save(WalletCard(bank = "Existing synthetic card", lastFour = "5678"))
        val before = repository.all()
        val bytes = archive(WalletCard(bank = "Must not be restored", lastFour = "1234"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val uri = input(ByteArrayInputStream(bytes))
        assertTrue(runCatching { BackupManager(app, repository).restore(uri, "completion-test-password".toCharArray()) }.isFailure)
        assertEquals(before, repository.all())
    }

    @Test fun cancellationAfterVerificationButBeforeCommitDoesNotChangeCards() = runBlocking {
        val repository = repository()
        repository.save(WalletCard(bank = "Existing synthetic card", lastFour = "5678"))
        val before = repository.all()
        lateinit var operation: Job
        val uri = input(object : ByteArrayInputStream(archive(WalletCard(bank = "Must not be restored", lastFour = "1234"))) {
            override fun read(): Int = super.read().also { if (it == -1) operation.cancel() }
        })
        val password = "completion-test-password".toCharArray()
        operation = async(start = CoroutineStart.LAZY) { BackupManager(app, repository).restore(uri, password) }
        operation.start()
        operation.join()
        assertEquals("cancellation before the commit boundary must leave existing cards intact", before, repository.all())
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun duplicateRequestsAndCallbacksCannotRestartAnOperation() {
        val vm = model(repository())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val uri = Uri.parse("content://mycard-test/state/${UUID.randomUUID()}")
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                super.write(bytes, offset, length)
            }
        }
        shadowOf(app.contentResolver).registerOutputStreamSupplier(uri) { opens.incrementAndGet(); output }
        val id = vm.requestBackupDocument(BackupAction.EXPORT)!!
        vm.backupDocumentSelected(id, uri)
        val password = "completion-test-password".toCharArray()
        assertTrue(vm.startBackup(id, password))
        try {
            waitFor { entered.count == 0L }
            assertNull(vm.requestBackupDocument(BackupAction.RESTORE))
            vm.backupDocumentSelected(id, uri)
            val rejected = "another-test-password".toCharArray()
            assertFalse(vm.startBackup(id, rejected))
            assertTrue(rejected.all { it == '\u0000' })
            vm.dismissBackup(id)
            assertEquals(BackupStep.RUNNING, vm.backupOperation.value.step)
        } finally { release.countDown() }
        waitFor { vm.backupOperation.value.step == BackupStep.SUCCEEDED }
        assertEquals(1, opens.get())
        assertTrue(password.all { it == '\u0000' })
        vm.dismissBackup(id)
        assertEquals(BackupStep.IDLE, vm.backupOperation.value.step)
        assertNotEquals(id, vm.requestBackupDocument(BackupAction.EXPORT))
    }

    @Test fun cancelledSelectionAndStaleCallbacksNeverStartWork() {
        val vm = model(repository())
        val first = vm.requestBackupDocument(BackupAction.RESTORE)!!
        vm.backupDocumentSelected(first, null)
        assertEquals(BackupStep.IDLE, vm.backupOperation.value.step)
        val next = vm.requestBackupDocument(BackupAction.EXPORT)!!
        vm.backupDocumentSelected(first, Uri.parse("content://mycard-test/stale"))
        val password = "completion-test-password".toCharArray()
        assertFalse(vm.startBackup(first, password))
        assertTrue(password.all { it == '\u0000' })
        assertEquals(next, vm.backupOperation.value.id)
        assertEquals(BackupStep.SELECTING, vm.backupOperation.value.step)
    }

    @Test fun committedCloseFailureIsShownAsSuccessWithWarning() = runBlocking {
        val repository = repository()
        val vm = model(repository)
        val incoming = WalletCard(bank = "Restored with warning", lastFour = "1234")
        val uri = input(object : ByteArrayInputStream(archive(incoming)) {
            override fun close() { throw IOException("Synthetic close failure") }
        })
        startRestore(vm, uri)
        waitFor { vm.backupOperation.value.step == BackupStep.WARNING }
        assertEquals(incoming, repository.all().single())
        assertTrue(vm.backupOperation.value.message!!.contains("数据已保存"))
        assertFalse(vm.backupOperation.value.message!!.contains("未改变"))
    }

    @Test fun interruptionAfterCommitDoesNotBecomeACancelledRestore() = runBlocking {
        val repository = repository()
        val vm = model(repository)
        val incoming = WalletCard(bank = "Restored before interruption", lastFour = "1234")
        val uri = input(object : ByteArrayInputStream(archive(incoming)) {
            override fun close() { throw CancellationException("Synthetic interruption after commit") }
        })
        startRestore(vm, uri)
        waitFor { vm.backupOperation.value.step == BackupStep.WARNING }
        assertEquals(incoming, repository.all().single())
        assertTrue(vm.backupOperation.value.message!!.contains("结束阶段被中断"))
    }

    @Test fun failedVerificationReportsFailureWithoutChangingExistingCards() = runBlocking {
        val repository = repository()
        repository.save(WalletCard(bank = "Existing", lastFour = "5678"))
        val before = repository.all()
        val vm = model(repository)
        val uri = input(ByteArrayInputStream(archive(WalletCard(bank = "Rejected", lastFour = "1234"))))
        val password = "incorrect-test-password".toCharArray()
        startRestore(vm, uri, password)
        waitFor { vm.backupOperation.value.step == BackupStep.VALIDATION_FAILED }
        assertEquals(before, repository.all())
        assertTrue(vm.backupOperation.value.message!!.contains("验证失败"))
        assertTrue(vm.backupOperation.value.message!!.contains("未提交"))
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun cancellationBeforeCommitHasItsOwnResultAndCanBeAcknowledged() = runBlocking {
        val repository = repository()
        val vm = model(repository)
        val uri = input(object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw CancellationException("Synthetic cancellation")
        })
        val id = startRestore(vm, uri)
        waitFor { vm.backupOperation.value.step == BackupStep.CANCELLED }
        assertTrue(repository.all().isEmpty())
        assertTrue(vm.backupOperation.value.message!!.contains("未提交"))
        vm.dismissBackup(id)
        assertEquals(BackupStep.IDLE, vm.backupOperation.value.step)
    }

    @Test fun providerReadFailureIsNotMisreportedAsAnInvalidPassword() {
        val vm = model(repository())
        val uri = input(object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw IOException("Synthetic read failure")
        })
        startRestore(vm, uri)
        waitFor { vm.backupOperation.value.step == BackupStep.FAILED }
        assertTrue(vm.backupOperation.value.message!!.contains("无法读取备份"))
        assertFalse(vm.backupOperation.value.message!!.contains("密码错误"))
    }

    @Test fun cancellationBeforeLaunchStillWipesTheTransferredPassword() {
        val vm = model(repository())
        val id = vm.requestBackupDocument(BackupAction.EXPORT)!!
        vm.backupDocumentSelected(id, Uri.parse("content://mycard-test/never-opened"))
        stores.single().clear()
        val password = "completion-test-password".toCharArray()
        vm.startBackup(id, password)
        waitFor { vm.backupOperation.value.step == BackupStep.CANCELLED }
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun passwordIsClearedBeforeReadingArchiveContents() = runBlocking {
        val repository = repository()
        val password = "completion-test-password".toCharArray()
        var checked = false
        val uri = input(object : ByteArrayInputStream(archive(WalletCard(bank = "Synthetic", lastFour = "1234"))) {
            override fun read(): Int {
                if (pos >= 28) {
                    checked = true
                    assertTrue("password is no longer needed after the encrypted stream is initialized", password.all { it == '\u0000' })
                }
                return super.read()
            }
        })
        assertEquals(1, BackupManager(app, repository).restoreWithResult(uri, password).count)
        assertTrue(checked)
    }
}
