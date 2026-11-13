package com.mycard.wallet

import android.content.Context
import android.content.ContextWrapper
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import com.mycard.wallet.data.*
import com.mycard.wallet.images.CardImageCache
import com.mycard.wallet.security.BackupManager
import com.mycard.wallet.security.Crypto
import com.mycard.wallet.security.VaultCipher
import com.mycard.wallet.security.VaultKeyUnavailableException
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException

/**
 * Staged artwork is owned by the operation that created it. Ownership may only be dropped once the
 * path is confirmed gone, so a failed filesystem operation never turns a retryable file into an
 * untracked orphan. A staged file is released without reading the database, while historical files
 * still require a complete read before any deletion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StagedArtworkCleanupTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private var sequence = 0
    private val password = "staged-cleanup-password"
    private val defaultDeleter: (File) -> Boolean = { file -> file.delete() || !file.exists() }

    private data class Sandbox(val context: ContextWrapper, val artwork: File)

    private fun sandbox(): Sandbox {
        val root = File(app.cacheDir, "sc${sequence++}")
        val databases = File(root, "databases").apply { mkdirs() }
        val artwork = File(root, "no-backup").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(databases, name)
            override fun getNoBackupFilesDir() = artwork
        }
        return Sandbox(context, artwork)
    }

    /** Test-only key with scriptable authentication, encryption and key-availability failures. */
    private class ScriptedVault : VaultCipher {
        private val delegate = TestVault()
        @Volatile var keyPresent = true
        @Volatile var failCardAuth: Set<String> = emptySet()
        @Volatile var failCardEncrypt = false
        @Volatile var failNextCardEncrypt = false
        val initializations = AtomicInteger(0)

        override fun encrypt(bytes: ByteArray, context: String): ByteArray {
            if (context.startsWith("card:") && (failCardEncrypt || failNextCardEncrypt)) {
                failNextCardEncrypt = false
                throw IOException("synthetic commit failure")
            }
            if (!keyPresent) throw VaultKeyUnavailableException()
            return delegate.encrypt(bytes, context)
        }

        override fun decrypt(bytes: ByteArray, context: String): ByteArray {
            if (!keyPresent) throw VaultKeyUnavailableException()
            if (context in failCardAuth) throw AEADBadTagException("synthetic authentication failure")
            return delegate.decrypt(bytes, context)
        }

        override fun hasKey(): Boolean = keyPresent

        override fun initializeKey(): Boolean {
            if (keyPresent) return true
            initializations.incrementAndGet()
            keyPresent = true
            return true
        }
    }

    private suspend fun rawRows(box: Sandbox): List<EncryptedCard> {
        val database = Room.databaseBuilder(box.context, WalletDatabase::class.java, "wallet.db").build()
        return try { database.cards().all() } finally { database.close() }
    }

    private fun artworkFiles(box: Sandbox): List<String> =
        File(box.artwork, "card_art").listFiles()?.map { it.name }?.sorted().orEmpty()

    private fun stagedFile(box: Sandbox, name: String) = File(box.artwork, "card_art/$name")

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
            DataOutputStream(Crypto.backupSink(raw, password.toCharArray())).use { out ->
                out.writeInt(manifest.size); out.write(manifest)
                out.writeInt(images.size)
                images.forEach { (name, bytes) -> out.writeUTF(name); out.writeInt(bytes.size); out.write(bytes) }
            }
        }.toByteArray()
    }

    private fun input(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://mycard-test/staged/${UUID.randomUUID()}")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    @After fun clearImageCache() = CardImageCache.clear()

    /** One readable card with committed artwork plus one record that fails authentication. */
    private suspend fun damagedWallet(box: Sandbox, vault: ScriptedVault): Pair<CardRepository, String> {
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "有图卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = committed))
        val damaged = WalletCard(bank = "损坏卡", lastFour = "2222")
        repo.save(damaged)
        vault.failCardAuth = setOf("card:${damaged.id}")
        assertEquals(1, repo.readAll().damaged.size)
        return repo to committed
    }

    // A. A delete that reports failure keeps ownership, so a later retry still works.
    @Test fun refusedDeletionKeepsOwnershipAndRetrySucceeds() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val (repo, committed) = damagedWallet(box, vault)
        val staged = repo.writeImage(byteArrayOf(4, 5, 6))

        repo.fileDeleter = { false }
        assertFalse("a failed delete must be reported", repo.discardIfUnused(staged))
        assertTrue("the file is still there", stagedFile(box, staged).isFile)

        repo.fileDeleter = defaultDeleter
        assertTrue("the retained ownership must allow a retry", repo.discardIfUnused(staged))
        assertFalse(stagedFile(box, staged).exists())
        assertTrue(stagedFile(box, committed).isFile)
    }

    // B. A delete that throws keeps the same ownership for a later retry.
    @Test fun throwingDeletionKeepsOwnershipAndRetrySucceeds() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val (repo, committed) = damagedWallet(box, vault)
        val staged = repo.writeImage(byteArrayOf(4, 5, 6))

        repo.fileDeleter = { throw IOException("synthetic delete failure") }
        assertFalse("a throwing delete must be reported", repo.discardIfUnused(staged))
        assertTrue(stagedFile(box, staged).isFile)

        repo.fileDeleter = defaultDeleter
        assertTrue(repo.discardIfUnused(staged))
        assertFalse(stagedFile(box, staged).exists())
        assertTrue(stagedFile(box, committed).isFile)
    }

    // C. A failed save whose cleanup also fails keeps the staged file retryable.
    @Test fun failedSaveRetainsOwnershipAndRetryReleasesTheFile() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val (repo, committed) = damagedWallet(box, vault)

        vault.failNextCardEncrypt = true
        repo.fileDeleter = { false }
        val failure = runCatching {
            repo.saveWithImage(WalletCard(bank = "新卡", lastFour = "3333"), byteArrayOf(7, 7, 7), null)
        }.exceptionOrNull()

        assertTrue("the save must fail, got $failure", failure is IOException)
        val leftover = artworkFiles(box).single { it != committed }
        assertTrue("the unconfirmed deletion must keep the file", stagedFile(box, leftover).isFile)

        repo.fileDeleter = defaultDeleter
        assertTrue("the retained ownership must allow a retry", repo.discardIfUnused(leftover))
        assertEquals(listOf(committed), artworkFiles(box))
    }

    // H. A cleanup failure never replaces the primary failure and stays identifiable for a retry.
    @Test fun cleanupFailureIsDiagnosableWithoutReplacingThePrimaryFailure() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val (repo, committed) = damagedWallet(box, vault)

        vault.failNextCardEncrypt = true
        repo.fileDeleter = { false }
        val failure = runCatching {
            repo.saveWithImage(WalletCard(bank = "新卡", lastFour = "3333"), byteArrayOf(7, 7, 7), null)
        }.exceptionOrNull()

        // The primary failure survives unchanged. Cleanup diagnostics are best effort: a coroutine
        // boundary may copy the exception and drop suppressed entries, so the contract below is what
        // callers can actually observe.
        assertTrue("expected the synthetic commit failure, got $failure", failure is IOException)
        assertEquals("the primary failure must survive unchanged", "synthetic commit failure", failure!!.message)

        val leftover = artworkFiles(box).single { it != committed }
        assertTrue("an unconfirmed delete must never report success", stagedFile(box, leftover).isFile)
        assertFalse("the failed release must be reported to the caller", repo.discardIfUnused(leftover))
        assertTrue("the retained file must still be there", stagedFile(box, leftover).isFile)

        repo.fileDeleter = defaultDeleter
        assertTrue("a retry after the filesystem recovers must succeed", repo.discardIfUnused(leftover))
        assertFalse(stagedFile(box, leftover).exists())
        assertTrue("committed historical artwork must never be touched", stagedFile(box, committed).isFile)
        assertEquals("only the committed artwork remains", listOf(committed), artworkFiles(box))
    }

    // D. Every record failing authentication must not stop a staged file from being released.
    @Test fun stagedArtworkIsReleasedWhenEveryRecordFailsAuthentication() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val first = WalletCard(bank = "甲卡", lastFour = "1111")
        val second = WalletCard(bank = "乙卡", lastFour = "2222")
        repo.save(first)
        repo.save(second)
        vault.failCardAuth = setOf("card:${first.id}", "card:${second.id}")
        val read = runCatching { repo.readAll() }.exceptionOrNull()
        assertTrue("expected a global read failure, got $read", read is WalletUnavailableException)
        assertEquals(WalletUnavailableException.Reason.ALL_RECORDS_UNREADABLE,
            (read as WalletUnavailableException).reason)

        vault.failNextCardEncrypt = true
        val failure = runCatching {
            repo.saveWithImage(WalletCard(bank = "新卡", lastFour = "3333"), byteArrayOf(7, 7, 7), null)
        }.exceptionOrNull()

        assertTrue("the save must fail, got $failure", failure is IOException)
        assertTrue("the staged image belongs to this operation", artworkFiles(box).isEmpty())
        assertEquals("no record may be added or removed", 2, rawRows(box).size)
    }

    // E. Losing the key mid-flight blocks the restore, creates no replacement and keeps history.
    @Test fun lostKeyNeverCreatesAReplacementAndKeepsHistoricalArtwork() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "有图卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = committed))
        val staged = repo.writeImage(byteArrayOf(4, 5, 6))
        vault.keyPresent = false

        val name = UUID.randomUUID().toString() + ".bin"
        val incoming = WalletCard(bank = "恢复卡", lastFour = "5678", faceMode = FaceMode.IMAGE, imagePath = name)
        val failure = runCatching {
            BackupManager(app, repo).restore(
                input(archive(listOf(incoming), mapOf(name to png()))), password.toCharArray())
        }.exceptionOrNull()

        assertTrue("the restore must fail on the missing key, got $failure", failure is WalletUnavailableException)
        assertEquals("no replacement key may be created", 0, vault.initializations.get())
        assertEquals("the restore must not stage or delete artwork",
            listOf(committed, staged).sorted(), artworkFiles(box))

        assertTrue("staged cleanup must not need the key", repo.discardIfUnused(staged))
        assertFalse(stagedFile(box, staged).exists())
        assertTrue("historical artwork must survive", stagedFile(box, committed).isFile)
        assertEquals("cleanup must not initialise a key", 0, vault.initializations.get())
    }

    // F. A failed read never authorises deleting historical or unknown artwork.
    @Test fun failedReadKeepsHistoricalAndUnknownArtwork() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "有图卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = committed))
        val unknown = UUID.randomUUID().toString() + ".bin"
        File(box.artwork, "card_art/$unknown").writeBytes(byteArrayOf(9, 9))
        val staged = repo.writeImage(byteArrayOf(4, 5, 6))
        vault.keyPresent = false

        assertTrue("a failed read must not report a deletion", repo.discardIfUnused(committed))
        assertTrue(repo.discardIfUnused(unknown))
        assertTrue("committed artwork must survive", stagedFile(box, committed).isFile)
        assertTrue("unknown artwork must survive", stagedFile(box, unknown).isFile)

        assertTrue("the trusted staged file is released", repo.discardIfUnused(staged))
        assertFalse(stagedFile(box, staged).exists())
        assertEquals(listOf(committed, unknown).sorted(), artworkFiles(box))
    }

    // G. A real SQLite trigger failure rolls the transaction back and releases the staged file.
    @Test fun realSqliteTransactionFailureRollsBackAndReleasesStagedArtwork() = runBlocking {
        val box = sandbox()
        val vault = ScriptedVault()
        val repo = CardRepository(box.context, vault)
        val committed = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "既有卡", lastFour = "1111", faceMode = FaceMode.IMAGE, imagePath = committed))
        val before = rawRows(box).associate { it.id to it.payload.copyOf() }
        val name = UUID.randomUUID().toString() + ".bin"

        val rejectedId = UUID.randomUUID().toString()
        val legacy = SQLiteDatabase.openDatabase(
            box.context.getDatabasePath("wallet.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            legacy.execSQL(
                "CREATE TRIGGER reject_card BEFORE INSERT ON cards WHEN NEW.id = '$rejectedId' " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic trigger rejection'); END")
        } finally { legacy.close() }

        val accepted = WalletCard(id = UUID.randomUUID().toString(), bank = "先写入卡", lastFour = "2222",
            faceMode = FaceMode.IMAGE, imagePath = name)
        val rejected = WalletCard(id = rejectedId, bank = "被拒卡", lastFour = "3333")
        val failure = runCatching {
            BackupManager(app, repo).restore(
                input(archive(listOf(accepted, rejected), mapOf(name to png()))), password.toCharArray())
        }.exceptionOrNull()

        assertNotNull("the transaction must fail", failure)
        assertTrue("expected a real database failure, got $failure",
            failure is SQLException || failure?.cause is SQLException)
        assertFalse("a database failure must not be an encoding failure", failure is IOException)

        val after = rawRows(box).associate { it.id to it.payload }
        assertEquals("the transaction must roll back every row", before.keys, after.keys)
        before.forEach { (id, payload) ->
            assertArrayEquals("row $id must stay byte-identical", payload, after.getValue(id))
        }
        assertEquals("committed artwork must survive", listOf(committed), artworkFiles(box))
    }
}