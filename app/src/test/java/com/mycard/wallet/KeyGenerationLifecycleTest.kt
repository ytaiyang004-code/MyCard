package com.mycard.wallet

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import com.mycard.wallet.data.*
import com.mycard.wallet.images.CardImageCache
import com.mycard.wallet.security.BackupManager
import com.mycard.wallet.security.Crypto
import com.mycard.wallet.security.VaultCipher
import com.mycard.wallet.security.VaultKeyUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Key generations are real here: K1 and K2 are different AES-256-GCM keys, so any row sealed under
 * the wrong generation becomes unreadable. Losing K1 must never let ordinary encryption, reads or a
 * restore install K2; only an explicit first-run initialisation on a wallet with no ciphertext at
 * all may create a key.
 */
private fun aesKey(seed: Byte): SecretKey = SecretKeySpec(ByteArray(32) { seed }, "AES")

private const val PASSWORD = "generation-lifecycle"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyGenerationLifecycleTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private var sequence = 0

    private data class Sandbox(val context: ContextWrapper, val artwork: File)

    private fun sandbox(): Sandbox {
        val root = File(app.cacheDir, "gen${sequence++}")
        val databases = File(root, "databases").apply { mkdirs() }
        val artwork = File(root, "no-backup").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(databases, name)
            override fun getNoBackupFilesDir() = artwork
        }
        return Sandbox(context, artwork)
    }

    /**
     * Two independent generations. Ordinary encryption/decryption only ever use the live key and
     * throw when it is gone; only [initializeKey] may install K2, and it records that it happened.
     */
    private class GenerationalVault : VaultCipher {
        private val k1 = aesKey(1)
        private val k2 = aesKey(2)
        @Volatile private var live: SecretKey? = k1
        @Volatile var aliasPresent = true
        @Volatile var failNextEncrypt = false
        @Volatile var cancelInitialization = false
        @Volatile var initializationSucceeds = true
        val initializations = AtomicInteger(0)
        val encryptions = AtomicInteger(0)
        val decrypts = AtomicInteger(0)

        private fun liveKey(): SecretKey = live ?: throw VaultKeyUnavailableException()

        override fun encrypt(bytes: ByteArray, context: String): ByteArray {
            if (failNextEncrypt) { failNextEncrypt = false; throw VaultKeyUnavailableException() }
            encryptions.incrementAndGet()
            return Crypto.seal(bytes, liveKey(), context.toByteArray())
        }

        override fun decrypt(bytes: ByteArray, context: String): ByteArray {
            decrypts.incrementAndGet()
            return Crypto.open(bytes, liveKey(), context.toByteArray())
        }

        override fun hasKey(): Boolean = aliasPresent

        override fun initializeKey(): Boolean {
            if (cancelInitialization) throw CancellationException("synthetic initialisation cancellation")
            if (aliasPresent) return true
            if (!initializationSucceeds) return false
            initializations.incrementAndGet()
            live = k2
            aliasPresent = true
            return true
        }

        fun loseK1() { aliasPresent = false; live = null }
        fun restoreK1() { aliasPresent = true; live = k1 }
        fun openWithK1(bytes: ByteArray, context: String) = Crypto.open(bytes, k1, context.toByteArray())
    }

    /** A stale K1 handle that outlives the alias: decryption works, the key is still gone. */
    private class CachedHandleVault : VaultCipher {
        private val k1 = aesKey(7)
        @Volatile var aliasPresent = true
        val encryptions = AtomicInteger(0)
        val initializations = AtomicInteger(0)

        override fun encrypt(bytes: ByteArray, context: String): ByteArray {
            encryptions.incrementAndGet()
            return Crypto.seal(bytes, k1, context.toByteArray())
        }

        override fun decrypt(bytes: ByteArray, context: String) = Crypto.open(bytes, k1, context.toByteArray())
        override fun hasKey(): Boolean = aliasPresent

        /** Direct use of the cached handle, bypassing the repository's presence probe. */
        fun openStale(bytes: ByteArray, context: String) = Crypto.open(bytes, k1, context.toByteArray())

        /** Recorded only: the repository must refuse before it ever asks to initialise. */
        override fun initializeKey(): Boolean {
            initializations.incrementAndGet()
            return false
        }
    }

    /** Raw stored rows, used to prove ciphertext is byte-identical after refused operations. */
    private suspend fun rawRows(box: Sandbox): List<EncryptedCard> {
        val database = Room.databaseBuilder(box.context, WalletDatabase::class.java, "wallet.db").build()
        return try { database.cards().all() } finally { database.close() }
    }

    private fun artworkFiles(box: Sandbox): List<String> =
        File(box.artwork, "card_art").listFiles()?.map { it.name }?.sorted().orEmpty()

    private fun assertKeyFailure(failure: Throwable?) {
        val error = failure ?: throw AssertionError("expected a key failure but the operation succeeded")
        assertTrue("expected WalletUnavailableException, got $error", error is WalletUnavailableException)
        assertEquals(WalletUnavailableException.Reason.KEY_UNAVAILABLE,
            (error as WalletUnavailableException).reason)
    }

    private suspend fun assertCiphertext(box: Sandbox, expected: Map<String, ByteArray>) {
        val actual = rawRows(box).associate { it.id to it.payload }
        assertEquals("no record may be added or removed", expected.keys, actual.keys)
        expected.forEach { (id, payload) ->
            assertArrayEquals("row $id must stay byte-identical", payload, actual.getValue(id))
        }
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(1586, 1000, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun archive(cards: List<WalletCard>, images: Map<String, ByteArray> = emptyMap()): ByteArray {
        val manifest = JSONObject().put("version", 2).put("cards", JSONArray().apply {
            cards.forEach { put(CardJson.encode(it)) }
        }).toString().toByteArray()
        return ByteArrayOutputStream().also { raw ->
            DataOutputStream(Crypto.backupSink(raw, PASSWORD.toCharArray())).use { out ->
                out.writeInt(manifest.size); out.write(manifest)
                out.writeInt(images.size)
                images.forEach { (name, bytes) -> out.writeUTF(name); out.writeInt(bytes.size); out.write(bytes) }
            }
        }.toByteArray()
    }

    private fun input(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://mycard-test/gen/${UUID.randomUUID()}")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    // 1. K1 is gone: ordinary reads and writes never install K2, and the old rows stay K1.
    @Test fun missingK1NeverCreatesK2ForReadsOrWrites() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "甲卡", lastFour = "1111"))
        repo.save(WalletCard(bank = "乙卡", lastFour = "2222"))
        val stored = repo.readAll().cards
        val before = rawRows(box).associate { it.id to it.payload.copyOf() }
        val decryptionsBefore = vault.decrypts.get()
        vault.loseK1()

        assertKeyFailure(runCatching { repo.readAll() }.exceptionOrNull())
        assertKeyFailure(runCatching { withTimeout(15_000) { repo.cards.first() } }.exceptionOrNull())
        assertKeyFailure(runCatching { repo.save(WalletCard(bank = "新增卡", lastFour = "3333")) }.exceptionOrNull())
        assertKeyFailure(runCatching { repo.update(stored[0].copy(notes = "改写")) }.exceptionOrNull())
        assertKeyFailure(runCatching { repo.image("${UUID.randomUUID()}.bin") }.exceptionOrNull())

        assertEquals("no replacement key may be created", 0, vault.initializations.get())
        assertEquals("a missing key must be detected before decrypting", decryptionsBefore, vault.decrypts.get())
        assertCiphertext(box, before)
        before.forEach { (id, payload) ->
            assertNotNull("row $id must still be a K1 row", vault.openWithK1(payload, "card:$id"))
        }
    }

    // 2. Restoring a backup with artwork into a wallet whose K1 is gone fails before staging.
    @Test fun restoreWithImagesIsRejectedWhenTheKeyIsGone() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(4, 5, 6))
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234", faceMode = FaceMode.IMAGE, imagePath = committed))
        val before = rawRows(box).associate { it.id to it.payload.copyOf() }
        val artworkBefore = artworkFiles(box)
        val encryptionsBefore = vault.encryptions.get()
        vault.loseK1()

        val name = UUID.randomUUID().toString() + ".bin"
        val incoming = WalletCard(bank = "恢复卡", lastFour = "5678", faceMode = FaceMode.IMAGE, imagePath = name)
        val failure = runCatching {
            BackupManager(app, repo).restore(input(archive(listOf(incoming), mapOf(name to png()))), PASSWORD.toCharArray())
        }.exceptionOrNull()

        assertKeyFailure(failure)
        assertEquals("restore must not initialise a key", 0, vault.initializations.get())
        assertEquals("restore must not encrypt anything", encryptionsBefore, vault.encryptions.get())
        assertEquals("restore must not stage artwork", artworkBefore, artworkFiles(box))
        assertCiphertext(box, before)
    }

    // 3. Restoring a backup without artwork is refused at the merge, before any row is written.
    @Test fun restoreWithoutImagesIsRejectedWhenTheKeyIsGone() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234"))
        val before = rawRows(box).associate { it.id to it.payload.copyOf() }
        val encryptionsBefore = vault.encryptions.get()
        vault.loseK1()

        val incoming = WalletCard(bank = "恢复卡", lastFour = "5678")
        val failure = runCatching {
            BackupManager(app, repo).restore(input(archive(listOf(incoming))), PASSWORD.toCharArray())
        }.exceptionOrNull()

        assertKeyFailure(failure)
        assertEquals("restore must not initialise a key", 0, vault.initializations.get())
        assertEquals("restore must not encrypt anything", encryptionsBefore, vault.encryptions.get())
        assertCiphertext(box, before)
        assertTrue(artworkFiles(box).isEmpty())
    }

    // 4. Both refused restores leave every byte of stored ciphertext and every artwork file alone.
    @Test fun rejectedRestoreLeavesCiphertextAndArtworkUntouched() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234", faceMode = FaceMode.IMAGE, imagePath = committed))
        val before = rawRows(box).associate { it.id to it.payload.copyOf() }
        val artworkBefore = artworkFiles(box)
        val encryptionsBefore = vault.encryptions.get()
        vault.loseK1()

        val name = UUID.randomUUID().toString() + ".bin"
        val withImage = WalletCard(bank = "带图恢复", lastFour = "5678", faceMode = FaceMode.IMAGE, imagePath = name)
        runCatching {
            BackupManager(app, repo).restore(input(archive(listOf(withImage), mapOf(name to png()))), PASSWORD.toCharArray())
        }
        runCatching {
            BackupManager(app, repo).restore(
                input(archive(listOf(WalletCard(bank = "无图恢复", lastFour = "9999")))), PASSWORD.toCharArray())
        }

        assertCiphertext(box, before)
        assertEquals("no artwork may be added, replaced or removed", artworkBefore, artworkFiles(box))
        assertEquals("no key may be initialised", 0, vault.initializations.get())
        assertEquals("nothing may be encrypted", encryptionsBefore, vault.encryptions.get())
    }

    // 5. A genuinely fresh wallet may still recover a backup: the first key is created explicitly.
    @Test fun restoreIntoAFreshWalletCreatesTheFirstKeyExplicitly() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        vault.loseK1()

        val restored = WalletCard(bank = "恢复卡", lastFour = "5678")
        val count = BackupManager(app, repo).restore(input(archive(listOf(restored))), PASSWORD.toCharArray())

        assertEquals(1, count)
        assertEquals("the fresh wallet needs exactly one explicit initialisation", 1, vault.initializations.get())
        assertEquals(listOf("恢复卡"), repo.all().map { it.bank })
    }

    // 6. A pending undo proves an earlier key existed, even with zero rows.
    @Test fun pendingUndoBlocksInitialisationOnAnEmptyDatabase() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "待删卡", lastFour = "1234"))
        val token = repo.deleteForUndo(repo.all().single())!!
        assertEquals(0, rawRows(box).size)
        vault.loseK1()

        assertKeyFailure(runCatching { repo.undo(token) }.exceptionOrNull())
        assertEquals("a pending undo must block initialisation", 0, vault.initializations.get())
        assertEquals(0, rawRows(box).size)

        vault.restoreK1()
        assertTrue("a returning key must still allow the pending undo", repo.undo(token))
        assertEquals(1, repo.all().size)
    }

    // 7. Artwork left behind by an earlier generation also blocks initialisation.
    @Test fun leftoverArtworkBlocksInitialisationOnAnEmptyDatabase() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        val path = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "有图卡", lastFour = "1234", faceMode = FaceMode.IMAGE, imagePath = path))
        repo.delete(repo.all().single().id)
        assertEquals(0, rawRows(box).size)
        assertTrue(File(box.artwork, "card_art/$path").isFile)
        vault.loseK1()

        assertKeyFailure(runCatching { repo.save(WalletCard(bank = "新增卡", lastFour = "5678")) }.exceptionOrNull())
        assertKeyFailure(runCatching { repo.writeImage(byteArrayOf(9)) }.exceptionOrNull())
        assertKeyFailure(runCatching {
            repo.saveWithImage(WalletCard(bank = "新图卡", lastFour = "9999", faceMode = FaceMode.IMAGE),
                byteArrayOf(9), null)
        }.exceptionOrNull())

        assertEquals("leftover artwork must block initialisation", 0, vault.initializations.get())
        assertEquals(listOf(path), artworkFiles(box))
    }

    // 8a. True first run without artwork: the first card works and creates the key explicitly.
    @Test fun freshWalletCreatesItsFirstKeyForAPlainCard() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        vault.loseK1()

        repo.save(WalletCard(bank = "首卡", lastFour = "1234"))

        assertEquals(1, vault.initializations.get())
        assertEquals(1, vault.encryptions.get())
        assertEquals(1, repo.readAll().cards.size)
    }

    // 8b. The normal "pick artwork, then save the first card" flow must keep working.
    @Test fun choosingArtworkBeforeTheFirstSaveStillWorks() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        vault.loseK1()

        val path = repo.writeImage(byteArrayOf(1, 2, 3))
        assertEquals("staging the first artwork initialises the key once", 1, vault.initializations.get())
        repo.saveWithImage(
            WalletCard(bank = "首图卡", lastFour = "5678", faceMode = FaceMode.IMAGE, imagePath = path), null, null)

        assertEquals(1, repo.readAll().cards.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.image(path))
        assertEquals(1, vault.initializations.get())
    }

    // A failed initialisation must never be followed by encryption.
    @Test fun failedInitialisationNeverEncrypts() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        vault.loseK1()
        vault.initializationSucceeds = false

        assertKeyFailure(runCatching { repo.save(WalletCard(bank = "首卡", lastFour = "1234")) }.exceptionOrNull())

        assertEquals(0, vault.encryptions.get())
        assertTrue(rawRows(box).isEmpty())
    }

    // 9. With K1 available, restore merging and ordinary CRUD behave exactly as before.
    @Test fun normalKeyKeepsRestoreAndCrud() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234"))

        val name = UUID.randomUUID().toString() + ".bin"
        val image = png()
        val incoming = WalletCard(bank = "恢复卡", lastFour = "5678", faceMode = FaceMode.IMAGE, imagePath = name)
        val count = BackupManager(app, repo).restore(
            input(archive(listOf(incoming), mapOf(name to image))), PASSWORD.toCharArray())

        assertEquals(1, count)
        assertEquals(2, repo.all().size)
        val restored = repo.all().first { it.bank == "恢复卡" }
        assertArrayEquals("restored artwork must decrypt with the same key", image, repo.image(restored.imagePath!!))
        assertEquals("no initialisation is needed when a key exists", 0, vault.initializations.get())

        repo.update(restored.copy(nickname = "改名"))
        assertEquals("改名", repo.all().first { it.id == restored.id }.nickname)
        val token = repo.deleteForUndo(repo.all().first { it.id == restored.id })!!
        assertTrue(repo.undo(token))
        repo.finishDelete(token)
        assertEquals(2, repo.all().size)
    }

    // 10. The key can vanish between the probe and the encryption: nothing may be written.
    @Test fun keyDisappearingAfterTheCheckBlocksTheCommit() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234"))
        val before = rawRows(box).associate { it.id to it.payload.copyOf() }
        val encryptionsBefore = vault.encryptions.get()

        vault.failNextEncrypt = true
        assertKeyFailure(runCatching { repo.save(WalletCard(bank = "新增卡", lastFour = "5678")) }.exceptionOrNull())
        assertCiphertext(box, before)

        vault.failNextEncrypt = true
        assertKeyFailure(runCatching {
            repo.saveWithImage(WalletCard(bank = "带图卡", lastFour = "9999", faceMode = FaceMode.IMAGE),
                byteArrayOf(1, 2, 3), null)
        }.exceptionOrNull())

        assertEquals("a failed encryption must not commit a row", before.size, rawRows(box).size)
        assertTrue("a failed image encryption must not leave a file", artworkFiles(box).isEmpty())
        assertEquals("no replacement key may be created", 0, vault.initializations.get())
        assertEquals("the failed attempts must not encrypt", encryptionsBefore, vault.encryptions.get())
        assertCiphertext(box, before)
    }

    // 11. A warm instance with a cached K1 handle still must not be allowed to write.
    @Test fun cachedHandleCannotAuthoriseWritesAfterTheAliasIsGone() = runBlocking {
        val box = sandbox()
        val vault = CachedHandleVault()
        val repo = CardRepository(box.context, vault)
        repo.save(WalletCard(bank = "既有卡", lastFour = "1234"))
        val row = rawRows(box).single()
        val encryptionsBefore = vault.encryptions.get()
        vault.aliasPresent = false

        // The stale handle still opens the old row; that must never be read as "the key is present".
        assertNotNull(vault.openStale(row.payload, "card:" + row.id))
        assertKeyFailure(runCatching { repo.readAll() }.exceptionOrNull())
        assertKeyFailure(runCatching { repo.save(WalletCard(bank = "新增卡", lastFour = "5678")) }.exceptionOrNull())

        assertEquals("no encryption may run under a stale handle", encryptionsBefore, vault.encryptions.get())
        assertEquals("initialisation must not even be attempted", 0, vault.initializations.get())
        assertEquals(1, rawRows(box).size)
        assertArrayEquals("the stored row must be untouched", row.payload, rawRows(box).single().payload)
    }

    // 12. Cancellation from the initialisation path propagates and writes nothing.
    @Test fun cancellationFromInitialisationPropagates() = runBlocking {
        val box = sandbox()
        val vault = GenerationalVault()
        val repo = CardRepository(box.context, vault)
        vault.loseK1()
        vault.cancelInitialization = true

        val failure = runCatching { repo.save(WalletCard(bank = "首卡", lastFour = "1234")) }.exceptionOrNull()
        assertTrue("cancellation must not be wrapped, got $failure", failure is CancellationException)
        assertEquals(0, vault.encryptions.get())
        assertTrue(rawRows(box).isEmpty())
        assertTrue(artworkFiles(box).isEmpty())
    }
}
