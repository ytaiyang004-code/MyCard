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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException

/**
 * An unreadable record must keep its ciphertext and its artwork, while files this operation created
 * and never committed must still be released even when other rows are unreadable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DamagedWalletDeletionCleanupTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private var sequence = 0

    private data class Sandbox(val context: ContextWrapper, val artwork: File)

    private fun sandbox(): Sandbox {
        val root = File(app.cacheDir, "dc${sequence++}")
        val databases = File(root, "databases").apply { mkdirs() }
        val artwork = File(root, "no-backup").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(databases, name)
            override fun getNoBackupFilesDir() = artwork
        }
        return Sandbox(context, artwork)
    }

    /** Test-only key with scriptable per-record authentication and encryption failures. */
    private class ScriptedVault : VaultCipher {
        private val delegate = TestVault()
        @Volatile var failCardAuth: Set<String> = emptySet()
        @Volatile var failCardEncrypt = false
        @Volatile var failNextCardEncrypt = false

        override fun encrypt(bytes: ByteArray, context: String): ByteArray {
            if (context.startsWith("card:") && (failCardEncrypt || failNextCardEncrypt)) {
                failNextCardEncrypt = false
                throw IOException("synthetic commit failure")
            }
            return delegate.encrypt(bytes, context)
        }

        override fun decrypt(bytes: ByteArray, context: String): ByteArray {
            if (context in failCardAuth) throw AEADBadTagException("synthetic authentication failure")
            return delegate.decrypt(bytes, context)
        }

        override fun hasKey(): Boolean = true
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        fun block() { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) { "Gate release timed out" } }
        fun await() { check(entered.await(15, TimeUnit.SECONDS)) { "Gate was never reached" } }
        fun open() { release.countDown() }
    }

    /** Blocks the first armed card encryption, so a restore can be cancelled after staging artwork. */
    private class GatedVault(private val gate: Gate) : VaultCipher {
        private val delegate = TestVault()
        private val once = AtomicBoolean()
        @Volatile var armed = false
        @Volatile var failCardAuth: Set<String> = emptySet()

        override fun encrypt(bytes: ByteArray, context: String): ByteArray {
            if (armed && context.startsWith("card:") && once.compareAndSet(false, true)) gate.block()
            return delegate.encrypt(bytes, context)
        }

        override fun decrypt(bytes: ByteArray, context: String): ByteArray {
            if (context in failCardAuth) throw AEADBadTagException("synthetic authentication failure")
            return delegate.decrypt(bytes, context)
        }

        override fun hasKey(): Boolean = true
    }

    /** Raw stored rows, used to prove the ciphertext was never rewritten. */
    private suspend fun rawRows(box: Sandbox): List<EncryptedCard> {
        val database = Room.databaseBuilder(box.context, WalletDatabase::class.java, "wallet.db").build()
        return try { database.cards().all() } finally { database.close() }
    }

    private fun artworkFiles(box: Sandbox): List<String> =
        File(box.artwork, "card_art").listFiles()?.map { it.name }?.sorted().orEmpty()

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(1586, 1000, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private val password = "damaged-cleanup-password"

    private fun archive(cards: List<WalletCard>, images: Map<String, ByteArray> = emptyMap()): ByteArray {
        val manifest = JSONObject().put("version", 2).put("cards", JSONArray().apply {
            cards.forEach { put(CardJson.encode(it)) }
        }).toString().toByteArray()
        return ByteArrayOutputStream().also { raw ->
            DataOutputStream(Crypto.backupSink(raw, password.toCharArray())).use { out ->
                out.writeInt(manifest.size); out.write(manifest)
                out.writeInt(images.size)
                images.forEach { (name, bytes) -> out.writeUTF(name); out.writeInt(bytes.size); out.write(bytes) }
            }
        }.toByteArray()
    }

    private fun input(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://mycard-test/cleanup/${UUID.randomUUID()}")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    // P2-1. An ordinary delete must not destroy an unreadable record or its artwork.
    @Test fun deleteRefusesADamagedRecordAndKeepsItsPayload() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val path = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "损坏卡", lastFour = "1234", faceMode = FaceMode.IMAGE, imagePath = path))
        repo.save(WalletCard(bank = "正常卡", lastFour = "5678"))
        val stored = repo.all().first { it.bank == "损坏卡" }
        val payload = rawRows(box).single { it.id == stored.id }.payload.copyOf()
        vault.failCardAuth = setOf("card:${stored.id}")
        assertEquals(1, repo.readAll().damaged.size)

        val failure = runCatching { repo.delete(stored.id) }.exceptionOrNull()

        assertTrue("expected DamagedRecordException, got $failure", failure is DamagedRecordException)
        assertEquals(2, rawRows(box).size)
        assertArrayEquals(payload, rawRows(box).single { it.id == stored.id }.payload)
        assertTrue("the artwork of an unreadable record must survive",
            File(box.artwork, "card_art/$path").isFile)
    }

    // P2-1. A readable record is still deleted normally; its artwork is released by the orphan sweep.
    @Test fun deleteStillRemovesAReadableRecord() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val path = repo.writeImage(byteArrayOf(4, 5, 6))
        val imageCard = WalletCard(bank = "有图卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = path)
        val plainCard = WalletCard(bank = "普通卡", lastFour = "2222")
        repo.save(imageCard)
        repo.save(plainCard)

        repo.delete(imageCard.id)

        assertEquals(listOf(plainCard.id), repo.all().map { it.id })
        assertTrue("delete(id) keeps artwork until the sweep, as before",
            File(box.artwork, "card_art/$path").isFile)
        repo.collectOrphans()
        assertTrue(artworkFiles(box).isEmpty())
    }

    // P2-2. A failed save releases only the artwork it staged, even while a record is unreadable.
    @Test fun failedSaveCleansItsOwnStagedArtworkWhileTheWalletIsDamaged() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "有图卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = committed))
        repo.save(WalletCard(bank = "损坏卡", lastFour = "2222"))
        val damaged = repo.all().first { it.bank == "损坏卡" }
        vault.failCardAuth = setOf("card:${damaged.id}")
        assertEquals(1, repo.readAll().damaged.size)

        vault.failNextCardEncrypt = true
        val failure = runCatching {
            repo.saveWithImage(WalletCard(bank = "新卡", lastFour = "3333"), byteArrayOf(7, 7, 7), null)
        }.exceptionOrNull()

        assertTrue("the save must fail on encryption, got $failure", failure is IOException)
        assertEquals("only the staged file may be released", listOf(committed), artworkFiles(box))
        assertEquals("no row may be committed", 2, rawRows(box).size)
    }

    // P2-2. Committed and unknown artwork stays untouched while the wallet is incomplete.
    @Test fun committedAndUnknownArtworkSurviveWhileTheWalletIsDamaged() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "有图卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = committed))
        repo.save(WalletCard(bank = "损坏卡", lastFour = "2222"))
        val damaged = repo.all().first { it.bank == "损坏卡" }
        val unknown = UUID.randomUUID().toString() + ".bin"
        File(box.artwork, "card_art/$unknown").writeBytes(byteArrayOf(9, 9))
        vault.failCardAuth = setOf("card:${damaged.id}")
        assertEquals(1, repo.readAll().damaged.size)

        repo.discardIfUnused(committed)
        repo.discardIfUnused(unknown)
        repo.collectOrphans()

        assertEquals("committed and unknown files must both survive",
            listOf(committed, unknown).sorted(), artworkFiles(box))
    }

    /** One readable card with committed artwork plus one unreadable record. */
    private suspend fun damagedWallet(box: Sandbox, vault: ScriptedVault): Pair<CardRepository, String> {
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "有图卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = committed))
        repo.save(WalletCard(bank = "损坏卡", lastFour = "2222"))
        val damaged = repo.all().first { it.bank == "损坏卡" }
        vault.failCardAuth = setOf("card:${damaged.id}")
        assertEquals(1, repo.readAll().damaged.size)
        return repo to committed
    }

    // P2-2. A restore that fails authentication after staging artwork leaves no staged file behind.
    @Test fun failedRestoreAuthenticationCleansOnlyItsStagedArtwork() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val (repo, committed) = damagedWallet(box, vault)

        val name = UUID.randomUUID().toString() + ".bin"
        val incoming = WalletCard(bank = "恢复卡", lastFour = "5678", faceMode = FaceMode.IMAGE, imagePath = name)
        val complete = archive(listOf(incoming), mapOf(name to png()))
        // Drop the authenticated end marker: the image is written first, then the stream fails.
        val truncated = complete.copyOf(complete.size - 32)

        val failure = runCatching { BackupManager(app, repo).restore(input(truncated), password.toCharArray()) }
            .exceptionOrNull()

        assertNotNull("a truncated backup must fail", failure)
        assertEquals("the staged restore file must be released", listOf(committed), artworkFiles(box))
        assertEquals("no row may be committed", 2, rawRows(box).size)
    }

    // P2-2. A failing restore commit releases its own staged artwork but never a committed image.
    @Test fun failedRestoreCommitKeepsCommittedArtwork() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val (repo, committed) = damagedWallet(box, vault)

        val name = UUID.randomUUID().toString() + ".bin"
        val incoming = WalletCard(bank = "恢复卡", lastFour = "5678", faceMode = FaceMode.IMAGE, imagePath = name)
        vault.failCardEncrypt = true
        val failure = runCatching {
            BackupManager(app, repo).restore(
                input(archive(listOf(incoming), mapOf(name to png()))), password.toCharArray())
        }.exceptionOrNull()
        vault.failCardEncrypt = false

        assertTrue("expected the synthetic commit failure, got $failure", failure is IOException)
        assertEquals("committed artwork must survive the failed commit", listOf(committed), artworkFiles(box))
        assertEquals("the failed restore must not commit", 2, rawRows(box).size)
    }

    // P2-2. A cancelled restore releases its staged artwork and keeps committed artwork.
    @Test fun cancelledRestoreReleasesStagedArtworkAndKeepsCommitted() = runBlocking {
        val box = sandbox()
        val gate = Gate()
        val vault = GatedVault(gate)
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "有图卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = committed))
        repo.save(WalletCard(bank = "损坏卡", lastFour = "2222"))
        val damaged = repo.all().first { it.bank == "损坏卡" }
        vault.failCardAuth = setOf("card:${damaged.id}")
        assertEquals(1, repo.readAll().damaged.size)

        val name = UUID.randomUUID().toString() + ".bin"
        val incoming = WalletCard(bank = "恢复卡", lastFour = "5678", faceMode = FaceMode.IMAGE, imagePath = name)
        val uri = input(archive(listOf(incoming), mapOf(name to png())))
        vault.armed = true
        val restoring = launch(Dispatchers.IO) { BackupManager(app, repo).restore(uri, password.toCharArray()) }
        try { gate.await(); restoring.cancel() } finally { gate.open() }
        withTimeout(15_000) { restoring.join() }

        assertEquals("committed artwork must survive cancellation", listOf(committed), artworkFiles(box))
        assertEquals("the cancelled restore must not commit", 2, rawRows(box).size)
    }
}
