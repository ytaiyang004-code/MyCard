package com.mycard.wallet

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.mycard.wallet.data.*
import com.mycard.wallet.images.CardImageCache
import com.mycard.wallet.security.VaultCipher
import com.mycard.wallet.security.VaultKeyUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyStoreException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A wallet that still holds ciphertext must never encrypt under a replacement key: ordinary
 * encryption resolves an existing key only and fails otherwise. Every ordinary write entry is
 * checked inside the repository lock, an empty wallet keeps its first-run initialisation through
 * the vault's explicit initialisation path, and no restore may mix key generations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyAvailabilityWriteGuardTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private var sequence = 0

    private data class Sandbox(val context: ContextWrapper, val artwork: File)

    private fun sandbox(): Sandbox {
        val root = File(app.cacheDir, "key${sequence++}")
        val databases = File(root, "databases").apply { mkdirs() }
        val artwork = File(root, "no-backup").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(databases, name)
            override fun getNoBackupFilesDir() = artwork
        }
        return Sandbox(context, artwork)
    }

    /** Test-only key. [keyPresent] stands in for the Android Keystore alias lookup. */
    private class ScriptedVault : VaultCipher {
        private val delegate = TestVault()
        @Volatile var keyPresent = true
        @Volatile var keyFailure: Exception? = null
        @Volatile var initializationFailure: Exception? = null
        @Volatile var createKeyOnInitialize = true
        val initializations = AtomicInteger(0)
        val encryptions = AtomicInteger(0)

        override fun encrypt(bytes: ByteArray, context: String): ByteArray {
            // Ordinary encryption never creates a key; a missing one fails like DeviceVault.
            if (!keyPresent) throw VaultKeyUnavailableException()
            encryptions.incrementAndGet()
            return delegate.encrypt(bytes, context)
        }

        override fun decrypt(bytes: ByteArray, context: String): ByteArray {
            if (!keyPresent) throw VaultKeyUnavailableException()
            return delegate.decrypt(bytes, context)
        }

        override fun hasKey(): Boolean {
            keyFailure?.let { throw it }
            return keyPresent
        }

        override fun initializeKey(): Boolean {
            initializationFailure?.let { throw it }
            if (keyPresent) return true
            if (!createKeyOnInitialize) return false
            initializations.incrementAndGet()
            keyPresent = true
            return true
        }
    }

    /** Raw stored rows, used to prove ciphertext is byte-identical after refused writes. */
    private suspend fun rawRows(box: Sandbox): List<EncryptedCard> {
        val database = Room.databaseBuilder(box.context, WalletDatabase::class.java, "wallet.db").build()
        return try { database.cards().all() } finally { database.close() }
    }

    private fun artworkFiles(box: Sandbox): List<String> =
        File(box.artwork, "card_art").listFiles()?.map { it.name }.orEmpty()

    private fun assertKeyUnavailable(failure: Throwable?) {
        val error = failure ?: throw AssertionError("expected a key failure but the operation succeeded")
        assertTrue("expected WalletUnavailableException, got $error", error is WalletUnavailableException)
        assertEquals(WalletUnavailableException.Reason.KEY_UNAVAILABLE,
            (error as WalletUnavailableException).reason)
        assertNotEquals("a key fault must not be classified as one damaged record",
            DamagedRecordException::class.java.name, error.javaClass.name)
    }

    private suspend fun assertCiphertext(box: Sandbox, expected: Map<String, ByteArray>) {
        val actual = rawRows(box).associate { it.id to it.payload }
        assertEquals("no record may be added or removed", expected.keys, actual.keys)
        expected.forEach { (id, payload) ->
            assertArrayEquals("row $id must stay byte-identical", payload, actual.getValue(id))
        }
    }

    @After fun clearImageCache() = CardImageCache.clear()

    // A. A wallet that already holds ciphertext refuses a new card, an edit and a delete request.
    @Test fun ordinaryWritesAreRejectedWhenTheKeyIsMissing() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234"))
        val stored = repo.readAll().cards.single()
        val encryptionsBefore = vault.encryptions.get()
        vault.keyPresent = false

        assertKeyUnavailable(runCatching { repo.save(WalletCard(bank = "新增卡", lastFour = "5678")) }.exceptionOrNull())
        assertKeyUnavailable(runCatching { repo.update(stored.copy(nickname = "改名")) }.exceptionOrNull())
        assertKeyUnavailable(runCatching { repo.deleteForUndo(stored) }.exceptionOrNull())

        assertEquals("a refused write must not encrypt", encryptionsBefore, vault.encryptions.get())
        assertEquals(listOf(stored.id), rawRows(box).map { it.id })
    }

    // B. A refused image save commits neither a row nor staged artwork.
    @Test fun imageWriteIsRejectedWithoutLeavingArtwork() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234"))
        val before = rawRows(box).single().payload.copyOf()
        val encryptionsBefore = vault.encryptions.get()
        vault.keyPresent = false

        val card = WalletCard(bank = "带图卡", lastFour = "5678", faceMode = FaceMode.IMAGE)
        assertKeyUnavailable(runCatching { repo.saveWithImage(card, byteArrayOf(1, 2, 3), null) }.exceptionOrNull())
        assertKeyUnavailable(runCatching { repo.writeImage(byteArrayOf(4, 5, 6)) }.exceptionOrNull())

        assertTrue("a refused image write must not leave a staged file", artworkFiles(box).isEmpty())
        assertEquals(1, rawRows(box).size)
        assertArrayEquals(before, rawRows(box).single().payload)
        assertEquals(encryptionsBefore, vault.encryptions.get())
    }

    // C. Existing ciphertext survives every refused write byte for byte.
    @Test fun refusedWritesLeaveExistingCiphertextUntouched() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        listOf("1111", "2222", "3333").forEach { repo.save(WalletCard(bank = "既有$it", lastFour = it)) }
        val stored = repo.readAll().cards
        val before = rawRows(box).associate { it.id to it.payload.copyOf() }
        vault.keyPresent = false

        assertKeyUnavailable(runCatching { repo.save(WalletCard(bank = "新增卡", lastFour = "4444")) }.exceptionOrNull())
        assertKeyUnavailable(runCatching { repo.update(stored[0].copy(notes = "改写")) }.exceptionOrNull())
        assertKeyUnavailable(runCatching {
            repo.saveWithImage(stored[1].copy(notes = "改写"), byteArrayOf(1), stored[1])
        }.exceptionOrNull())
        assertKeyUnavailable(runCatching { repo.reorder(listOf(stored[2].id)) }.exceptionOrNull())

        assertCiphertext(box, before)
    }

    // D. After a refused write the wallet still reports one global key fault, not partial damage.
    @Test fun refusedWriteStillReportsTheGlobalKeyFault() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val path = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "有图卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = path))
        repo.save(WalletCard(bank = "无图卡", lastFour = "2222"))
        vault.keyPresent = false

        assertKeyUnavailable(runCatching { repo.save(WalletCard(bank = "新增卡", lastFour = "3333")) }.exceptionOrNull())
        assertKeyUnavailable(runCatching { repo.readAll() }.exceptionOrNull())
        assertKeyUnavailable(runCatching { repo.all() }.exceptionOrNull())
        assertKeyUnavailable(runCatching { withTimeout(15_000) { repo.cards.first() } }.exceptionOrNull())
        assertKeyUnavailable(runCatching { repo.image(path) }.exceptionOrNull())
    }

    // E. An empty wallet still creates its first key, card and artwork.
    @Test fun emptyWalletStillCreatesItsFirstKeyAndCard() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        vault.keyPresent = false
        vault.createKeyOnInitialize = true

        repo.save(WalletCard(bank = "首卡", lastFour = "1234"))
        assertEquals("initialisation must happen exactly once", 1, vault.initializations.get())
        assertEquals("initialisation must encrypt once", 1, vault.encryptions.get())
        assertTrue("the first write creates the key", vault.keyPresent)
        assertEquals(1, repo.readAll().cards.size)

        val withImage = WalletCard(bank = "首图卡", lastFour = "5678", faceMode = FaceMode.IMAGE)
        repo.saveWithImage(withImage, byteArrayOf(1, 2, 3), null)
        assertEquals(2, repo.readAll().cards.size)
        val name = artworkFiles(box).single()
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.image(name))
    }

    // F. With an available key, ordinary behaviour is unchanged.
    @Test fun availableKeyKeepsNormalCrud() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val first = WalletCard(bank = "甲卡", lastFour = "1111")
        val second = WalletCard(bank = "乙卡", lastFour = "2222")
        repo.save(first)
        repo.save(second)
        assertEquals(2, repo.all().size)

        repo.update(repo.all().first { it.id == first.id }.copy(nickname = "改名"))
        assertEquals("改名", repo.all().first { it.id == first.id }.nickname)

        val path = repo.writeImage(byteArrayOf(7, 7, 7))
        val stored = repo.all().first { it.id == second.id }
        repo.saveWithImage(stored.copy(faceMode = FaceMode.IMAGE, imagePath = path), null, stored)
        assertArrayEquals(byteArrayOf(7, 7, 7), repo.image(path))

        val token = repo.deleteForUndo(repo.all().first { it.id == second.id })!!
        assertTrue(repo.undo(token))
        repo.finishDelete(token)
        assertEquals(2, repo.all().size)

        repo.reorder(listOf(second.id, first.id))
        assertEquals(0L, repo.all().first { it.id == second.id }.position)
        assertEquals(1L, repo.all().first { it.id == first.id }.position)
    }

    // G. A key check that throws blocks the write and keeps its cause.
    @Test fun failingKeyCheckBlocksTheWrite() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234"))
        val before = rawRows(box).associate { it.id to it.payload.copyOf() }
        val encryptionsBefore = vault.encryptions.get()
        vault.keyFailure = KeyStoreException("synthetic check failure")

        val failure = runCatching { repo.save(WalletCard(bank = "新增卡", lastFour = "5678")) }.exceptionOrNull()
        assertKeyUnavailable(failure)
        assertTrue("the cause must survive", (failure as WalletUnavailableException).cause is KeyStoreException)
        assertEquals(encryptionsBefore, vault.encryptions.get())
        assertCiphertext(box, before)
    }

    // H. Cancellation raised by the key check propagates instead of being wrapped or swallowed.
    @Test fun cancellationFromTheKeyCheckPropagates() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234"))
        val before = rawRows(box).associate { it.id to it.payload.copyOf() }
        val encryptionsBefore = vault.encryptions.get()
        vault.keyFailure = CancellationException("synthetic cancellation")

        val failure = runCatching { repo.save(WalletCard(bank = "新增卡", lastFour = "5678")) }.exceptionOrNull()
        assertTrue("cancellation must not be wrapped, got $failure", failure is CancellationException)
        assertEquals(encryptionsBefore, vault.encryptions.get())
        assertCiphertext(box, before)
    }

    // Undo re-encrypts the card, so it is guarded too while other records keep the wallet non-empty.
    @Test fun undoCannotReEncryptUnderAReplacementKey() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "保留卡", lastFour = "1111"))
        repo.save(WalletCard(bank = "待删卡", lastFour = "2222"))
        val target = repo.all().first { it.bank == "待删卡" }
        val token = repo.deleteForUndo(target)!!
        val afterDelete = rawRows(box).associate { it.id to it.payload.copyOf() }
        vault.keyPresent = false

        assertKeyUnavailable(runCatching { repo.undo(token) }.exceptionOrNull())
        assertCiphertext(box, afterDelete)

        vault.keyPresent = true
        assertTrue("a returning key must still allow the pending undo", repo.undo(token))
        assertEquals(2, repo.all().size)
    }
}
