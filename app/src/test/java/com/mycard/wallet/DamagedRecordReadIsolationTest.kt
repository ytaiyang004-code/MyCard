package com.mycard.wallet

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.mycard.wallet.data.*
import com.mycard.wallet.images.CardImageCache
import com.mycard.wallet.security.BackupManager
import com.mycard.wallet.security.VaultCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStoreException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException

/**
 * One unreadable record must not hide the rest of the wallet, and must never cause its ciphertext
 * or its (unknowable) artwork to be deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DamagedRecordReadIsolationTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private var sequence = 0
    private val stores = mutableListOf<ViewModelStore>()

    private data class Sandbox(val context: ContextWrapper, val artwork: File)

    private fun sandbox(): Sandbox {
        val root = File(app.cacheDir, "iso${sequence++}")
        val databases = File(root, "databases").apply { mkdirs() }
        val artwork = File(root, "no-backup").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(databases, name)
            override fun getNoBackupFilesDir() = artwork
        }
        return Sandbox(context, artwork)
    }

    /** Test-only key. Damage is scripted per AAD so no database tampering is needed. */
    private class ScriptedVault : VaultCipher {
        private val delegate = TestVault()
        @Volatile var failCardAuth: Set<String> = emptySet()
        @Volatile var keyFailureOnDecrypt: Exception? = null
        @Volatile var keyFailureOnCheck: Exception? = null
        @Volatile var keyPresent: Boolean = true
        val encryptions = AtomicInteger(0)

        override fun encrypt(bytes: ByteArray, context: String): ByteArray {
            encryptions.incrementAndGet()
            return delegate.encrypt(bytes, context)
        }

        override fun decrypt(bytes: ByteArray, context: String): ByteArray {
            keyFailureOnDecrypt?.let { throw it }
            if (context in failCardAuth) throw AEADBadTagException("synthetic authentication failure")
            return delegate.decrypt(bytes, context)
        }

        override fun hasKey(): Boolean {
            keyFailureOnCheck?.let { throw it }
            return keyPresent
        }
    }

    private suspend fun seed(repo: CardRepository): List<WalletCard> {
        val cards = listOf(
            WalletCard(bank = "正常甲", lastFour = "1111"),
            WalletCard(bank = "损坏乙", lastFour = "2222"),
            WalletCard(bank = "正常丙", lastFour = "3333")
        )
        cards.forEach { repo.save(it) }
        return cards
    }

    /** Raw stored rows, used to prove ciphertext is byte-identical after failed attempts. */
    private suspend fun rawRows(box: Sandbox): List<EncryptedCard> {
        val database = Room.databaseBuilder(box.context, WalletDatabase::class.java, "wallet.db").build()
        return try { database.cards().all() } finally { database.close() }
    }

    private fun model(repo: CardRepository): WalletViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WalletViewModel(RuntimeEnvironment.getApplication(), repo) as T
        }
        return ViewModelProvider(store, factory)[WalletViewModel::class.java]
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            check(System.nanoTime() < deadline) { "Condition was not reached" }
            Thread.sleep(5)
        }
    }

    @After fun closeModels() {
        stores.forEach { it.clear() }
        ShadowLooper.idleMainLooper()
        CardImageCache.clear()
    }

    // 1. Two healthy records and one damaged record: the healthy ones stay readable.
    @Test fun healthyCardsSurviveOneDamagedRecord() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val seeded = seed(repo)
        vault.failCardAuth = setOf("card:${seeded[1].id}")

        val result = repo.readAll()
        assertEquals(listOf(seeded[0].id, seeded[2].id), result.cards.map { it.id })
        assertEquals(1, result.damaged.size)
        assertEquals(seeded[1].id, result.damaged.single().id)
        assertEquals(DamageStage.DECRYPT, result.damaged.single().stage)
        assertEquals(AEADBadTagException::class.java.name, result.damaged.single().exceptionType)
        assertFalse(result.complete)

        val streamed = withTimeout(15_000) { repo.cards.first() }
        assertEquals(2, streamed.cards.size)
        assertEquals(1, streamed.damaged.size)
    }

    // 2. The damaged ciphertext is neither rewritten nor deleted by reads or rejected writes.
    @Test fun damagedCiphertextIsNeverRewrittenOrDeleted() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val seeded = seed(repo)
        val before = rawRows(box).single { it.id == seeded[1].id }.payload.copyOf()
        vault.failCardAuth = setOf("card:${seeded[1].id}")
        val stale = seeded[1].copy(updatedAt = seeded[1].updatedAt + 1)

        assertTrue(runCatching { repo.update(stale) }.exceptionOrNull() is DamagedRecordException)
        assertTrue(runCatching { repo.deleteForUndo(stale) }.exceptionOrNull() is DamagedRecordException)
        assertTrue(runCatching { repo.save(stale) }.exceptionOrNull() is DamagedRecordException)
        assertTrue(runCatching { repo.reorder(listOf(seeded[0].id)) }.exceptionOrNull() is IncompleteWalletException)

        val rows = rawRows(box)
        assertEquals(3, rows.size)
        assertArrayEquals(before, rows.single { it.id == seeded[1].id }.payload)
    }

    // 3. Strict all() must fail instead of returning an incomplete wallet.
    @Test fun strictAllRefusesToReturnAPartialWallet() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val seeded = seed(repo)
        vault.failCardAuth = setOf("card:${seeded[1].id}")

        val failure = runCatching { repo.all() }.exceptionOrNull()
        assertTrue("all() must not silently drop a record", failure is IncompleteWalletException)
        assertEquals(1, (failure as IncompleteWalletException).damagedCount)
    }

    // 4. Every record failing authentication is a global fault, never an empty wallet.
    @Test fun allRecordsFailingAuthenticationIsNotAnEmptyWallet() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val seeded = seed(repo)
        vault.failCardAuth = seeded.map { "card:${it.id}" }.toSet()

        val failure = runCatching { repo.readAll() }.exceptionOrNull()
        assertTrue(failure is WalletUnavailableException)
        assertEquals(WalletUnavailableException.Reason.ALL_RECORDS_UNREADABLE,
            (failure as WalletUnavailableException).reason)
    }

    // 5. A key-store fault is global, keeps its cause, and is not swallowed by the flow either.
    @Test fun keyStoreFailureIsGlobalAndNotSwallowed() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        seed(repo)
        vault.keyFailureOnDecrypt = KeyStoreException("synthetic key-store failure")

        val failure = runCatching { repo.readAll() }.exceptionOrNull()
        assertTrue(failure is WalletUnavailableException)
        val unavailable = failure as WalletUnavailableException
        assertEquals(WalletUnavailableException.Reason.KEY_UNAVAILABLE, unavailable.reason)
        assertTrue("the cause must survive", unavailable.cause is KeyStoreException)

        val streamed = runCatching { withTimeout(15_000) { repo.cards.first() } }.exceptionOrNull()
        assertTrue(streamed is WalletUnavailableException)
    }

    // 5b. A missing key fails as a whole and never causes a replacement key to be generated.
    @Test fun missingKeyNeverGeneratesAReplacementWhileReading() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        seed(repo)
        val encryptionsBefore = vault.encryptions.get()
        vault.keyPresent = false

        val failure = runCatching { repo.readAll() }.exceptionOrNull()
        assertTrue(failure is WalletUnavailableException)
        assertEquals(WalletUnavailableException.Reason.KEY_UNAVAILABLE,
            (failure as WalletUnavailableException).reason)
        assertEquals("reading must not encrypt", encryptionsBefore, vault.encryptions.get())
    }

    // 5c. A failing key check is not treated as "present".
    @Test fun failingKeyCheckIsNotTreatedAsPresent() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        seed(repo)
        vault.keyFailureOnCheck = KeyStoreException("synthetic check failure")

        val failure = runCatching { repo.readAll() }.exceptionOrNull()
        assertTrue(failure is WalletUnavailableException)
        assertEquals(WalletUnavailableException.Reason.KEY_UNAVAILABLE,
            (failure as WalletUnavailableException).reason)
    }

    // 6. With an unreadable record present, no image whose ownership is unknowable is deleted.
    @Test fun artworkIsNeverDeletedWhileARecordIsUnreadable() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val path = repo.writeImage(byteArrayOf(1, 2, 3, 4))
        val keeper = WalletCard(bank = "有图卡", lastFour = "4444", faceMode = FaceMode.IMAGE, imagePath = path)
        val broken = WalletCard(bank = "损坏卡", lastFour = "5555")
        repo.save(keeper)
        repo.save(broken)
        val file = File(box.artwork, "card_art/$path")
        assertTrue(file.isFile)
        vault.failCardAuth = setOf("card:${broken.id}")

        repo.collectOrphans()
        assertTrue("orphan sweep must pause while ownership is unknowable", file.isFile)
        repo.discardIfUnused(path)
        assertTrue("discard must keep a possibly referenced file", file.isFile)

        val stored = repo.readAll().cards.single { it.id == keeper.id }
        val token = repo.deleteForUndo(stored)!!
        repo.finishDelete(token)
        assertTrue("finishDelete must stay conservative", file.isFile)
        assertNull("the in-memory copy is still dropped", CardImageCache.peek(path))
    }

    // 7. Committed artwork survives discard; a staged, never committed file is still cleaned.
    @Test fun committedArtworkSurvivesDiscardAndStagedArtworkIsCleaned() = runBlocking {
        val box = sandbox()
        val repo = CardRepository(box.context, ScriptedVault())
        val committed = repo.writeImage(byteArrayOf(7, 7, 7))
        repo.save(WalletCard(bank = "已提交", lastFour = "6666", faceMode = FaceMode.IMAGE, imagePath = committed))
        repo.discardIfUnused(committed)
        assertTrue("a referenced file must never be deleted",
            File(box.artwork, "card_art/$committed").isFile)

        val staged = repo.writeImage(byteArrayOf(9, 9, 9))
        assertTrue(File(box.artwork, "card_art/$staged").isFile)
        repo.discardIfUnused(staged)
        assertFalse("a staged file that was never committed is provably unused",
            File(box.artwork, "card_art/$staged").exists())
    }

    // 8. A healthy wallet keeps its previous behaviour.
    @Test fun healthyWalletKeepsPreviousBehaviour() = runBlocking {
        val box = sandbox()
        val repo = CardRepository(box.context, ScriptedVault())
        seed(repo)

        val result = repo.readAll()
        assertTrue(result.complete)
        assertEquals(3, result.cards.size)
        assertEquals(3, repo.all().size)
        assertEquals(3, withTimeout(15_000) { repo.cards.first() }.cards.size)

        val path = repo.writeImage(byteArrayOf(5))
        val card = WalletCard(bank = "待删除", lastFour = "7777", faceMode = FaceMode.IMAGE, imagePath = path)
        repo.save(card)
        val token = repo.deleteForUndo(repo.all().first { it.id == card.id })!!
        repo.collectOrphans()
        assertTrue("an undo window still protects the file", File(box.artwork, "card_art/$path").isFile)
        repo.finishDelete(token)
        assertFalse(File(box.artwork, "card_art/$path").exists())
    }

    // 9. The view model loads healthy cards and exposes the damaged count for the next phase.
    @Test fun viewModelLoadsHealthyCardsAndCountsDamage() {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val seeded = runBlocking { seed(repo) }
        vault.failCardAuth = setOf("card:${seeded[1].id}")

        val vm = model(repo)
        waitFor { vm.cardsLoaded.value }
        assertEquals(2, vm.cards.value.size)
        assertEquals(1, vm.damagedCount.value)
        assertNull(vm.error.value)
    }

    // 10. A global key fault is reported, not shown as an empty wallet.
    @Test fun viewModelReportsKeyFailureInsteadOfEmptyWallet() {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        runBlocking { seed(repo) }
        vault.keyPresent = false

        val vm = model(repo)
        waitFor { vm.error.value != null }
        assertTrue(vm.error.value!!.contains("加密密钥"))
        assertFalse(vm.cardsLoaded.value)
    }

    // 11. Export refuses to create a file when the wallet is incomplete.
    @Test fun exportRefusesToWriteWhileARecordIsUnreadable() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val seeded = seed(repo)
        vault.failCardAuth = setOf("card:${seeded[1].id}")

        val output = ByteArrayOutputStream()
        val uri = Uri.parse("content://com.mycard.test/export")
        shadowOf(app.contentResolver).registerOutputStream(uri, output)
        val failure = runCatching { BackupManager(app, repo).export(uri, "synthetic-password".toCharArray()) }
            .exceptionOrNull()
        assertTrue(failure is IncompleteWalletException)
        assertEquals("the target must not be written or truncated", 0, output.size())
        assertEquals(3, rawRows(box).size)
    }
}