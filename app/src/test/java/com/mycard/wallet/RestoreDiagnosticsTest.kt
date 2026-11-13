package com.mycard.wallet

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.mycard.wallet.data.*
import com.mycard.wallet.security.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.io.*
import java.security.InvalidKeyException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

/** No actual backups or Android device keys. Every injected failure uses synthetic private markers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoreDiagnosticsTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val password = "PRIVATE_PASSWORD_SYNTHETIC"
    private val secret = "PRIVATE_BANK_AND_NOTE_SYNTHETIC"
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var storageContext: Context
    private var sequence = 0
    @Before fun clearLog() { ShadowLog.clear() }
    @After fun closeModels() { stores.forEach { it.clear() }; ShadowLooper.idleMainLooper() }

    private fun repository(vault: VaultCipher = TestVault()): CardRepository {
        val root = File(app.cacheDir, "rd${sequence++}")
        storageContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(root, "db/$name").also { it.parentFile!!.mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "art").apply { mkdirs() }
        }
        return CardRepository(storageContext, vault)
    }
    private fun card(image: Boolean = false) = WalletCard(bank = secret, lastFour = "1234", notes = secret,
        imagePath = if (image) "${UUID.randomUUID()}.bin" else null)
    private fun uri() = Uri.parse("content://mycard-synthetic/PRIVATE_URI/${UUID.randomUUID()}")
    private fun input(stream: ByteArrayInputStream) = uri().also { shadowOf(app.contentResolver).registerInputStream(it, stream) }
    private fun input(bytes: ByteArray) = input(ByteArrayInputStream(bytes))
    private fun archive(cards: List<WalletCard> = listOf(card()), images: List<ByteArray> = emptyList(),
                        manifestOverride: String? = null, version: Int = 2): ByteArray {
        val json = (manifestOverride ?: JSONObject().put("version", version)
            .put("cards", JSONArray().apply { cards.forEach { put(CardJson.encode(it)) } }).toString()).toByteArray()
        return ByteArrayOutputStream().also { raw ->
            DataOutputStream(Crypto.backupSink(raw, password.toCharArray())).use { stream ->
                stream.writeInt(json.size); stream.write(json); stream.writeInt(images.size)
                images.forEachIndexed { index, bytes ->
                    stream.writeUTF(cards[index].imagePath!!); stream.writeInt(bytes.size); stream.write(bytes)
                }
            }
        }.toByteArray()
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(1586, 1000, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private suspend fun failure(repo: CardRepository, uri: Uri, stage: RestoreStage, kind: RestoreFailure,
                                passphrase: String = password): RestoreDiagnostic {
        val diagnostics = mutableListOf<RestoreDiagnostic>()
        val chars = passphrase.toCharArray()
        val outcome = runCatching { BackupManager(app, repo).restoreWithResult(uri, chars, diagnostics::add) }
        assertTrue("the synthetic failure must remain a failure", outcome.isFailure)
        val diagnostic = diagnostics.single()
        assertEquals(stage, diagnostic.stage)
        assertEquals(kind, diagnostic.failure)
        assertFalse(diagnostic.committed)
        assertEquals(outcome.exceptionOrNull()!!.javaClass.name, diagnostic.exceptionType)
        assertTrue(chars.all { it == '\u0000' })
        return diagnostic
    }
    private fun model(repo: CardRepository): WalletViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WalletViewModel(RuntimeEnvironment.getApplication(), repo) as T
        }
        return ViewModelProvider(store, factory)[WalletViewModel::class.java].also { vm -> waitFor { vm.cardsLoaded.value } }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            check(System.nanoTime() < deadline) { "Synthetic diagnostic operation timed out" }
            Thread.sleep(5)
        }
    }
    private fun start(vm: WalletViewModel, uri: Uri, passphrase: String = password) {
        val id = vm.requestBackupDocument(BackupAction.RESTORE)!!
        vm.backupDocumentSelected(id, uri)
        assertTrue(vm.startBackup(id, passphrase.toCharArray()))
        waitFor { vm.backupOperation.value.step != BackupStep.RUNNING }
    }
    private fun assertSafeLogs() {
        val logs = ShadowLog.getLogsForTag(RestoreDiagnostics.TAG)
        assertFalse(logs.isEmpty())
        logs.forEach { log ->
            assertNull("Throwable messages and stack traces must never enter the log", log.throwable)
            assertTrue(log.msg.matches(Regex("stage=[A-Z_]+ failure=[A-Z_]+ exception=[A-Za-z0-9_.$]+ committed=(true|false)")))
            listOf(password, secret, "PRIVATE_URI", "PRIVATE_CAUSE", "PRIVATE_SUPPRESSED").forEach { assertFalse(log.msg.contains(it)) }
        }
    }

    @Test fun fileOpenFailureIsFileAccess() { runBlocking {
        val target = uri()
        shadowOf(app.contentResolver).registerInputStreamSupplier(target) { throw FileNotFoundException(secret) }
        failure(repository(), target, RestoreStage.FILE_OPEN, RestoreFailure.FILE_ACCESS)
        assertSafeLogs()
    } }

    @Test fun malformedOrTruncatedHeaderIsFormatFailure() { runBlocking {
        val repo = repository()
        for (bytes in listOf(ByteArray(28), ByteArray(12))) {
            failure(repo, input(bytes), RestoreStage.HEADER, RestoreFailure.FORMAT)
        }
    } }

    @Test fun wrongPasswordAndDamagedAuthenticationShareAnHonestMessage() { runBlocking {
        val repo = repository()
        val valid = archive()
        val wrong = failure(repo, input(valid), RestoreStage.AUTH, RestoreFailure.AUTHENTICATION, "synthetic-wrong-password")
        val damaged = valid.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }
        val corrupt = failure(repo, input(damaged), RestoreStage.AUTH, RestoreFailure.AUTHENTICATION)
        assertEquals(wrong.failureMessage(), corrupt.failureMessage())
        val providerFailure = RestoreDiagnostics.record(RestoreStage.AUTH, BadPaddingException(secret), false)
        assertEquals(RestoreFailure.AUTHENTICATION, providerFailure.failure)
        assertEquals(wrong.failureMessage(), providerFailure.failureMessage())
        assertTrue(wrong.failureMessage().contains("密码不匹配或认证数据损坏"))
        assertTrue(repo.all().isEmpty())
    } }

    @Test fun lateTerminatorAuthenticationIsNotReportedAsImageOrDatabaseFailure() { runBlocking {
        val repo = repository()
        val valid = archive(listOf(card(true)), listOf(png()))
        val damaged = valid.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        failure(repo, input(damaged), RestoreStage.AUTH, RestoreFailure.AUTHENTICATION)
        assertTrue(repo.all().isEmpty())
        assertTrue(File(storageContext.noBackupFilesDir, "card_art").listFiles().isNullOrEmpty())
    } }

    @Test fun authFrameTruncationAndProviderReadFailureAreNotWrongPassword() { runBlocking {
        val repo = repository()
        failure(repo, input(archive().copyOf(28)), RestoreStage.AUTH, RestoreFailure.FORMAT)
        val broken = input(object : ByteArrayInputStream(archive()) {
            override fun read(): Int = if (pos >= 28) throw IOException(secret) else super.read()
        })
        failure(repo, broken, RestoreStage.AUTH, RestoreFailure.FILE_ACCESS)
    } }

    @Test fun invalidJsonReportsManifestStageWithoutLoggingJson() { runBlocking {
        failure(repository(), input(archive(manifestOverride = "{\"bank\":\"$secret\"")), RestoreStage.MANIFEST, RestoreFailure.FORMAT)
        assertSafeLogs()
    } }

    @Test fun invalidVersionAndCardFieldsReportValidationStage() { runBlocking {
        val repo = repository()
        failure(repo, input(archive(version = 99)), RestoreStage.VALIDATION, RestoreFailure.FORMAT)
        val missing = JSONObject().put("version", 2).put("cards", JSONArray().put(JSONObject().put("bank", secret))).toString()
        failure(repo, input(archive(manifestOverride = missing)), RestoreStage.VALIDATION, RestoreFailure.FORMAT)
    } }

    @Test fun invalidPngReportsImageStage() { runBlocking {
        failure(repository(), input(archive(listOf(card(true)), listOf(byteArrayOf(1, 2, 3)))), RestoreStage.IMAGE, RestoreFailure.FORMAT)
    } }

    @Test fun localImageEncryptionFailureReportsLocalWrite() { runBlocking {
        val delegate = TestVault()
        val repo = repository(object : VaultCipher {
            override fun encrypt(bytes: ByteArray, context: String): ByteArray {
                if (context.startsWith("image:")) throw InvalidKeyException(secret)
                return delegate.encrypt(bytes, context)
            }
            override fun decrypt(bytes: ByteArray, context: String) = delegate.decrypt(bytes, context)
        })
        failure(repo, input(archive(listOf(card(true)), listOf(png()))), RestoreStage.LOCAL_WRITE, RestoreFailure.LOCAL_WRITE)
        assertTrue(repo.all().isEmpty())
    } }

    @Test fun localAeadExceptionIsNotClassifiedAsBackupPasswordFailure() { runBlocking {
        val repo = repository(object : VaultCipher {
            override fun encrypt(bytes: ByteArray, context: String): ByteArray = throw AEADBadTagException(secret)
            override fun decrypt(bytes: ByteArray, context: String) = TestVault().decrypt(bytes, context)
        })
        val diagnostic = failure(repo, input(archive(listOf(card(true)), listOf(png()))), RestoreStage.LOCAL_WRITE, RestoreFailure.LOCAL_WRITE)
        assertFalse(diagnostic.failureMessage().contains("密码不匹配"))
    } }

    @Test fun sqliteAbortReportsDatabaseCommitAndRollsBack() { runBlocking {
        val repo = repository()
        val database = Room.databaseBuilder(storageContext, WalletDatabase::class.java, "wallet.db").build()
        try {
            withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER synthetic_abort BEFORE INSERT ON cards BEGIN SELECT RAISE(ABORT, '$secret'); END") }
            val diagnostic = failure(repo, input(archive()), RestoreStage.DB_COMMIT, RestoreFailure.LOCAL_WRITE)
            assertTrue(diagnostic.exceptionType.contains("SQLite"))
            assertTrue(repo.all().isEmpty())
            assertSafeLogs()
        } finally { database.close() }
    } }

    @Test fun localRecordEncryptionFailureIsNotFormatFailure() { runBlocking {
        val delegate = TestVault()
        val repo = repository(object : VaultCipher {
            override fun encrypt(bytes: ByteArray, context: String): ByteArray {
                if (context.startsWith("card:")) throw InvalidKeyException(secret)
                return delegate.encrypt(bytes, context)
            }
            override fun decrypt(bytes: ByteArray, context: String) = delegate.decrypt(bytes, context)
        })
        failure(repo, input(archive()), RestoreStage.DB_COMMIT, RestoreFailure.LOCAL_WRITE)
    } }

    @Test fun generationConflictHasItsOwnCategory() { runBlocking {
        val repo = repository()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val target = input(object : ByteArrayInputStream(archive()) {
            override fun read(): Int = super.read().also {
                if (it == -1) { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
            }
        })
        val restoring = async(Dispatchers.IO) { failure(repo, target, RestoreStage.DB_COMMIT, RestoreFailure.CONFLICT) }
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            repo.clear()
        } finally { release.countDown() }
        assertTrue(restoring.await().failureMessage().contains("卡包已清空"))
        assertTrue(repo.all().isEmpty())
    } }

    @Test fun cancellationKeepsItsStageAndCancellationSemantics() { runBlocking {
        val target = input(object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw CancellationException(secret)
        })
        val diagnostic = failure(repository(), target, RestoreStage.HEADER, RestoreFailure.CANCELLED)
        assertTrue(diagnostic.exceptionType.endsWith("CancellationException"))
    } }

    @Test fun committedCloseFailureRetainsSuccessAndDiagnostic() { runBlocking {
        val repo = repository()
        val target = input(object : ByteArrayInputStream(archive()) { override fun close() { throw IOException(secret) } })
        val result = BackupManager(app, repo).restoreWithResult(target, password.toCharArray())
        assertEquals(1, result.count)
        assertEquals(RestoreWarning.STREAM_CLOSE_FAILED, result.warning)
        assertEquals(RestoreStage.STREAM_CLOSE, result.diagnostic!!.stage)
        assertTrue(result.diagnostic!!.committed)
        assertEquals(1, repo.all().size)
        assertSafeLogs()
    } }

    @Test fun cleanupFailureIsLoggedWithoutReplacingThePrimaryImageFailure() { runBlocking {
        val repo = repository()
        repo.save(card())
        // Staged artwork is now released without reading the database, so the cleanup failure is
        // injected at the filesystem level instead of through a card decryption failure.
        repo.fileDeleter = { false }
        val target = input(archive(listOf(card(true), card(true)), listOf(png(), byteArrayOf(1, 2, 3))))
        failure(repo, target, RestoreStage.IMAGE, RestoreFailure.FORMAT)
        val logs = ShadowLog.getLogsForTag(RestoreDiagnostics.TAG)
        assertTrue(logs.any { it.msg.contains("stage=CLEANUP") })
        assertSafeLogs()
        assertEquals(1, repo.all().size)
    } }

    @Test fun exceptionMessagesCausesAndSuppressedValuesNeverReachLogs() {
        val exception = IOException("$secret $password content://PRIVATE_URI")
        exception.initCause(IllegalArgumentException("PRIVATE_CAUSE"))
        exception.addSuppressed(IllegalStateException("PRIVATE_SUPPRESSED"))
        val diagnostic = RestoreDiagnostics.record(RestoreStage.FILE_OPEN, exception, false)
        assertFalse(diagnostic.toString().contains(secret))
        assertSafeLogs()
    }

    @Test fun viewModelDisplaysAuthAndFormatDiagnosticsWithDifferentMessages() { runBlocking {
        val repo = repository()
        val vm = model(repo)
        start(vm, input(archive()), "synthetic-wrong-password")
        assertEquals(BackupStep.VALIDATION_FAILED, vm.backupOperation.value.step)
        assertTrue(vm.backupOperation.value.message!!.contains("密码不匹配或认证数据损坏"))
        assertTrue(vm.backupOperation.value.message!!.contains("stage=AUTH"))
        assertFalse(vm.backupOperation.value.diagnostic!!.committed)
        vm.dismissBackup(vm.backupOperation.value.id!!)
        start(vm, input(archive(version = 99)))
        assertTrue(vm.backupOperation.value.message!!.contains("备份格式或内容校验失败"))
        assertFalse(vm.backupOperation.value.message!!.contains("密码不匹配"))
        assertTrue(vm.backupOperation.value.message!!.contains("stage=VALIDATION"))
        assertFalse(vm.backupOperation.value.message!!.contains(secret))
        assertSafeLogs()
    } }

    @Test fun viewModelLocalWriteFailureIsNotShownAsPasswordOrFormatFailure() {
        val repo = repository(object : VaultCipher {
            override fun encrypt(bytes: ByteArray, context: String): ByteArray = throw InvalidKeyException(secret)
            override fun decrypt(bytes: ByteArray, context: String) = TestVault().decrypt(bytes, context)
        })
        val vm = model(repo)
        start(vm, input(archive()))
        assertEquals(BackupStep.FAILED, vm.backupOperation.value.step)
        assertTrue(vm.backupOperation.value.message!!.contains("本地加密写入或数据库提交失败"))
        assertTrue(vm.backupOperation.value.message!!.contains("stage=DB_COMMIT"))
        assertFalse(vm.backupOperation.value.message!!.contains(secret))
    }

    @Test fun viewModelCommittedCloseWarningNeverSaysUncommitted() {
        val repo = repository()
        val vm = model(repo)
        val target = input(object : ByteArrayInputStream(archive()) { override fun close() { throw IOException(secret) } })
        start(vm, target)
        assertEquals(BackupStep.WARNING, vm.backupOperation.value.step)
        assertTrue(vm.backupOperation.value.diagnostic!!.committed)
        assertTrue(vm.backupOperation.value.message!!.contains("数据已保存"))
        assertTrue(vm.backupOperation.value.message!!.contains("committed=true"))
        assertFalse(vm.backupOperation.value.message!!.contains("未提交"))
        assertSafeLogs()
    }
}
