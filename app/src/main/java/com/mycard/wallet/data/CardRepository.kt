package com.mycard.wallet.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.mycard.wallet.images.CardImageCache
import com.mycard.wallet.security.DeviceVault
import com.mycard.wallet.security.VaultCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
import javax.crypto.BadPaddingException

/** One-use, in-memory deletion capability. Its card and lifetime are owned by the repository. */
class DeleteUndoToken internal constructor()
class StaleDataOperationException : IllegalStateException("The wallet changed; retry with current data")

class CardRepository(context: Context, private val vault: VaultCipher = DeviceVault()) {
    private val database = Room.databaseBuilder(context, WalletDatabase::class.java, "wallet.db").build()
    private val dao = database.cards()
    private val directory = File(context.noBackupFilesDir, "card_art").apply { mkdirs() }
    private val mutex = Mutex()
    @Volatile var dataGeneration: Long = 0
        private set
    private val pendingDeletes = mutableMapOf<DeleteUndoToken, WalletCard>()
    private val stagedImages = mutableSetOf<String>()

    /**
     * Filesystem seam used only by artwork cleanup. Production deletes through [File.delete]; tests
     * inject a failing deleter to verify that ownership survives an unconfirmed deletion. Returns
     * true only when the path is gone.
     */
    internal var fileDeleter: (File) -> Boolean = { file -> file.delete() || !file.exists() }

    /**
     * Number of records the last full read proved unreadable, or null while that is unknown.
     *
     * It is only used to widen a conservative window: every deletion still re-reads the database,
     * so this value can block a deletion but never authorise one.
     */
    @Volatile private var unreadableRecords: Int? = null

    // Every writer uses this mutex. Locked helpers never call another public locking method.
    private suspend fun <T> mutate(generation: Long, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (generation != dataGeneration) throw StaleDataOperationException()
            currentCoroutineContext().ensureActive()
            block()
        }
    }
    private sealed interface RecordRead {
        data class Read(val card: WalletCard) : RecordRead
        data class Damaged(val record: DamagedRecord) : RecordRead
    }
    private sealed interface RecordLookup {
        object Missing : RecordLookup
        data class Present(val card: WalletCard) : RecordLookup
        data class Unreadable(val record: DamagedRecord) : RecordLookup
    }

    /** Keeps only the exception class name: messages can quote plaintext or JSON fragments. */
    private fun damaged(row: EncryptedCard, stage: DamageStage, error: Exception): DamagedRecord {
        val type = error.javaClass.name
        return DamagedRecord(row.id, stage, if (type.matches(Regex("[A-Za-z0-9_.$]{1,200}"))) type else "Exception")
    }

    /**
     * Classifies one stored payload. Authentication, short-payload, JSON and validation failures
     * belong to that record. Key-store, provider and algorithm faults affect the whole wallet and
     * are never downgraded to "one damaged card"; anything unknown propagates unchanged.
     */
    private fun decodeRow(row: EncryptedCard): RecordRead {
        val bytes = try {
            vault.decrypt(row.payload, "card:" + row.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when {
                // Truncated payloads are rejected by Crypto.open's minimum-length check too.
                e is BadPaddingException || e is IllegalArgumentException ->
                    return RecordRead.Damaged(damaged(row, DamageStage.DECRYPT, e))
                e is GeneralSecurityException || e is ProviderException ->
                    throw WalletUnavailableException(WalletUnavailableException.Reason.KEY_UNAVAILABLE, e)
                else -> throw e
            }
        }
        return try {
            val card = CardJson.decode(org.json.JSONObject(bytes.toString(Charsets.UTF_8)))
                .also { require(it.id == row.id) }
            RecordRead.Read(card)
        } catch (e: JSONException) {
            RecordRead.Damaged(damaged(row, DamageStage.PARSE, e))
        } catch (e: IllegalArgumentException) {
            RecordRead.Damaged(damaged(row, DamageStage.VALIDATE, e))
        } finally { bytes.fill(0) }
    }

    /**
     * Read-only key probe. A failing probe is a wallet-wide key fault: it is never treated as
     * "the key is present" and never downgraded to damage of one record.
     */
    private fun vaultKeyPresent(): Boolean = try { vault.hasKey() }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            throw WalletUnavailableException(WalletUnavailableException.Reason.KEY_UNAVAILABLE, e)
        }

    private fun requireVaultKeyPresent() {
        if (!vaultKeyPresent()) throw WalletUnavailableException(WalletUnavailableException.Reason.KEY_UNAVAILABLE)
    }

    /**
     * A missing key makes every row fail authentication. Checking first keeps that fault visible as
     * one global failure; decryption itself never creates a key.
     */
    private fun requireVaultKey(rows: List<EncryptedCard>) {
        if (rows.isEmpty()) return
        requireVaultKeyPresent()
    }

    /** Reads never initialise: they only report whether the key that protects stored data is there. */
    private suspend fun requireKeyForRead() {
        if (dao.count() > 0) requireVaultKeyPresent()
    }

    /**
     * Key gate for every encryption.
     *
     * An accessible key is used as is. A missing key may be created only for a wallet that provably
     * owns nothing encrypted, and only through the vault's explicit [VaultCipher.initializeKey].
     * Every other case fails before a single byte is encrypted, so a replacement key can never
     * strand existing rows or mix generations.
     */
    private suspend fun requireKeyForEncryption() {
        if (vaultKeyPresent()) return
        if (!isFreshWallet()) throw WalletUnavailableException(WalletUnavailableException.Reason.KEY_UNAVAILABLE)
        val created = try { vault.initializeKey() }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            throw WalletUnavailableException(WalletUnavailableException.Reason.KEY_UNAVAILABLE, e)
        }
        if (!created) throw WalletUnavailableException(WalletUnavailableException.Reason.KEY_UNAVAILABLE)
    }

    /**
     * A wallet is fresh only when it holds no ciphertext of any kind. A zero row count alone is not
     * proof: a pending undo copy, staged artwork or a leftover artwork file all mean an earlier key
     * existed, so its loss must not be papered over with a new one. An unreadable directory is
     * treated as history too, never as an empty wallet.
     */
    private suspend fun isFreshWallet(): Boolean {
        val files = directory.listFiles() ?: return false
        return dao.count() == 0 && pendingDeletes.isEmpty() && stagedImages.isEmpty() && files.isEmpty()
    }

    /** Encryption failures are wallet-wide key faults, never record damage. */
    private fun seal(bytes: ByteArray, context: String): ByteArray = try { vault.encrypt(bytes, context) }
    catch (e: Exception) {
        if (e is CancellationException) throw e
        when {
            e is GeneralSecurityException || e is ProviderException ->
                throw WalletUnavailableException(WalletUnavailableException.Reason.KEY_UNAVAILABLE, e)
            else -> throw e
        }
    }

    private suspend fun readSnapshot(rows: List<EncryptedCard>): WalletReadResult {
        unreadableRecords = null // unknown until this read completes
        requireVaultKey(rows)
        val cards = ArrayList<WalletCard>(rows.size)
        val damaged = ArrayList<DamagedRecord>()
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            when (val read = decodeRow(row)) {
                is RecordRead.Read -> cards += read.card
                is RecordRead.Damaged -> damaged += read.record
            }
        }
        // A replaced key makes every row fail authentication. Showing that as an empty, partly
        // damaged wallet would invite destructive cleanup of data that may still be recoverable.
        if (damaged.isNotEmpty() && damaged.size == rows.size && damaged.all { it.stage == DamageStage.DECRYPT }) {
            throw WalletUnavailableException(WalletUnavailableException.Reason.ALL_RECORDS_UNREADABLE)
        }
        return WalletReadResult(cards, damaged).also { unreadableRecords = it.damaged.size }
    }
    private suspend fun readSnapshot() = readSnapshot(dao.all())

    private suspend fun findLocked(id: String): RecordLookup {
        val row = dao.all().firstOrNull { it.id == id } ?: return RecordLookup.Missing
        return when (val read = decodeRow(row)) {
            is RecordRead.Read -> RecordLookup.Present(read.card)
            is RecordRead.Damaged -> RecordLookup.Unreadable(read.record)
        }
    }
    private fun imageFile(name: String): File {
        require(name.matches(Regex("[a-f0-9-]{36}\\.bin")))
        return File(directory, name)
    }
    private fun checkImages(cards: List<WalletCard>) {
        cards.mapNotNull { it.imagePath }.distinct().forEach { require(imageFile(it).isFile) { "Missing artwork" } }
    }
    private suspend fun saveLocked(card: WalletCard, previous: WalletCard?) {
        checkImages(listOf(card))
        val timestamp = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: -1) + 1)
        dao.upsert(listOf(encode(card.copy(updatedAt = timestamp))))
        card.imagePath?.let(stagedImages::remove)
    }
    private fun writeImageLocked(bytes: ByteArray): String {
        require(bytes.size <= 6 * 1024 * 1024)
        val name = UUID.randomUUID().toString() + ".bin"
        val file = imageFile(name)
        // Registered before the file can exist: a failed seal, a partial write or a failed cleanup
        // all keep the same ownership proof, which is what makes a later safe retry possible.
        stagedImages += name
        try {
            file.writeBytes(seal(bytes, "image:" + name))
            return name
        } catch (e: Exception) {
            if (!releaseStagedFile(name, file)) e.addSuppressed(artworkCleanupFailure(name))
            throw e
        }
    }
    /**
     * Releases artwork only when ownership or a complete read proves it unused.
     *
     * A staged name is the repository's one ownership proof: it is registered before the file can
     * exist and dropped only after the commit that stores it (saveLocked, merge, undo) or after a
     * confirmed deletion. A staged file is therefore released without reading the database at all -
     * old rows may be unreadable or the key may be gone, and neither changes the fact that this file
     * was never committed. Anything else needs a complete read; a failed, partial or cancelled read
     * keeps the file. Returns true only when no owned file is left behind.
     */
    private suspend fun discardIfUnusedLocked(name: String): Boolean {
        val file = imageFile(name)
        if (pendingDeletes.values.any { it.imagePath == name }) {
            stagedImages.remove(name)
            return true
        }
        if (name in stagedImages) return releaseStagedFile(name, file)
        if ((unreadableRecords ?: 0) > 0) { CardImageCache.evict(name); return true }
        val snapshot = try { readSnapshot() } catch (e: Exception) {
            if (e is CancellationException) throw e
            CardImageCache.evict(name)
            return true // conservative: nothing was deleted, so nothing failed
        }
        if (!snapshot.complete) { CardImageCache.evict(name); return true }
        if (snapshot.cards.any { it.imagePath == name }) return true
        CardImageCache.evict(name)
        return deleteUnusedFile(file)
    }

    /**
     * Releases a file this operation created. The mark is dropped only once the path is confirmed
     * gone; a false or throwing delete keeps it, so a later attempt can still retry safely.
     */
    private fun releaseStagedFile(name: String, file: File): Boolean {
        CardImageCache.evict(name)
        if (name !in stagedImages) return true
        // delete() may return false for a path that is already gone, so existence is the authority.
        try { fileDeleter(file) } catch (e: Exception) { /* resolved by the existence check below */ }
        val present = try { file.exists() } catch (e: Exception) { true }
        if (present) return false
        stagedImages.remove(name)
        return true
    }

    /** Deletes an unowned file. Returns true only when the path is confirmed gone. */
    private fun deleteUnusedFile(file: File): Boolean {
        try { fileDeleter(file) } catch (e: Exception) { /* resolved by the existence check below */ }
        return try { !file.exists() } catch (e: Exception) { false }
    }

    /**
     * Diagnostics attached with [Throwable.addSuppressed] are best effort: kotlinx.coroutines may copy
     * an exception at a suspension boundary and drop suppressed entries, so callers identify a failed
     * release through the Boolean result of [discardIfUnused] instead.
     */
    private fun artworkCleanupFailure(name: String) = IOException("Staged artwork could not be released: $name")
    private fun encode(card: WalletCard): EncryptedCard {
        require(CardRules.valid(card))
        val bytes = CardJson.encode(card).toString().toByteArray()
        return try { EncryptedCard(card.id, seal(bytes, "card:" + card.id)) } finally { bytes.fill(0) }
    }
    /** Keeps readable cards and names unreadable records; never silently drops either. */
    suspend fun readAll(): WalletReadResult = withContext(Dispatchers.IO) { readSnapshot() }
    val cards: Flow<WalletReadResult> = dao.observe().map { rows -> readSnapshot(rows) }.flowOn(Dispatchers.IO)
    /** Strict view: a caller that needs the whole wallet must see an explicit failure. */
    suspend fun all(): List<WalletCard> = readAll().let { result ->
        if (!result.complete) throw IncompleteWalletException(result.damaged.size)
        result.cards
    }
    suspend fun save(card: WalletCard, generation: Long = dataGeneration) = mutate(generation) {
        requireKeyForEncryption()
        val previous = when (val found = findLocked(card.id)) {
            RecordLookup.Missing -> null
            is RecordLookup.Present -> found.card
            // Overwriting an unreadable payload would destroy the only copy of that data.
            is RecordLookup.Unreadable -> throw DamagedRecordException(card.id)
        }
        withContext(NonCancellable) { saveLocked(card, previous) }
    }
    /** Existing-card edits are compare-and-set; a stale detail/editor cannot resurrect a deletion. */
    suspend fun update(card: WalletCard, generation: Long = dataGeneration) = mutate(generation) {
        requireKeyForEncryption()
        val previous = when (val found = findLocked(card.id)) {
            RecordLookup.Missing -> throw StaleDataOperationException()
            is RecordLookup.Present -> found.card
            is RecordLookup.Unreadable -> throw DamagedRecordException(card.id)
        }
        if (previous.updatedAt != card.updatedAt) throw StaleDataOperationException()
        withContext(NonCancellable) { saveLocked(card, previous) }
    }
    /** Owns file staging through the database commit. Room cannot roll back a file write. */
    suspend fun saveWithImage(card: WalletCard, image: ByteArray?, original: WalletCard?,
                              generation: Long = dataGeneration) = mutate(generation) {
        // Checked before staging or decrypting: no file may be written under a replacement key.
        requireKeyForEncryption()
        val previous = when (val found = findLocked(card.id)) {
            RecordLookup.Missing -> null
            is RecordLookup.Present -> found.card
            is RecordLookup.Unreadable -> throw DamagedRecordException(card.id)
        }
        if (previous != original) throw StaleDataOperationException()
        var path: String? = null
        var failure: Throwable? = null
        try {
            path = image?.let(::writeImageLocked)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { saveLocked(card.copy(imagePath = path ?: card.imagePath), previous) }
            // Committed: the database now owns this file, so no cleanup is needed.
            path = null
        } catch (e: Exception) {
            failure = e
            throw e
        } finally {
            val staged = path
            if (staged != null) withContext(NonCancellable) {
                // A failed release keeps its ownership mark, so the original failure stays primary.
                if (!discardIfUnusedLocked(staged)) failure?.addSuppressed(artworkCleanupFailure(staged))
            }
        }
    }
    suspend fun delete(id: String, generation: Long = dataGeneration) = mutate(generation) {
        // Reading first is the guard: an unreadable payload may reference artwork we cannot see and
        // cannot be re-created, so an ordinary delete must never destroy it.
        if (findLocked(id) is RecordLookup.Unreadable) throw DamagedRecordException(id)
        withContext(NonCancellable) {
            dao.delete(id)
            pendingDeletes.entries.removeAll { it.value.id == id }
            unreadableRecords = null // other rows may still be unreadable
        }
    }
    suspend fun deleteForUndo(card: WalletCard, generation: Long = dataGeneration): DeleteUndoToken? = mutate(generation) {
        // Reading the target row must not turn a missing key into record damage.
        requireKeyForRead()
        val previous = when (val found = findLocked(card.id)) {
            RecordLookup.Missing -> return@mutate null
            is RecordLookup.Present -> found.card
            // Deleting an unreadable payload would destroy it without a usable undo copy.
            is RecordLookup.Unreadable -> throw DamagedRecordException(card.id)
        }
        if (previous.updatedAt != card.updatedAt) throw StaleDataOperationException()
        withContext(NonCancellable) {
            dao.delete(card.id)
            DeleteUndoToken().also { pendingDeletes[it] = previous }
        }
    }
    suspend fun undo(token: DeleteUndoToken): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val card = pendingDeletes[token] ?: return@withLock false
            // Undo re-encrypts the card, so it is an ordinary write for the key guard.
            requireKeyForEncryption()
            if (findLocked(card.id) !is RecordLookup.Missing) { pendingDeletes.remove(token); return@withLock false }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                saveLocked(card, card)
                pendingDeletes.remove(token)
            }
            true
        }
    }
    suspend fun finishDelete(token: DeleteUndoToken) = withContext(Dispatchers.IO) {
        mutex.withLock {
            withContext(NonCancellable) {
                pendingDeletes.remove(token)?.imagePath?.let { discardIfUnusedLocked(it) }
            }
        }
    }
    suspend fun reorder(ids: List<String>, generation: Long = dataGeneration) = mutate(generation) {
        val snapshot = readSnapshot()
        // Renumbering a wallet that hides unreadable rows would rewrite the whole table blindly.
        if (!snapshot.complete) throw IncompleteWalletException(snapshot.damaged.size)
        val all = snapshot.cards
        require(ids.toSet().size == ids.size)
        val selected = ids.map { id -> all.first { it.id == id } }.iterator()
        val normalized = all.sortedWith(compareBy<WalletCard> { it.position }.thenBy { it.id })
            .map { if (it.id in ids) selected.next() else it }
            .mapIndexed { i, c -> c.copy(position = i.toLong(), updatedAt = maxOf(System.currentTimeMillis(), c.updatedAt + 1)) }
        val rows = normalized.map(::encode)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { database.withTransaction { dao.upsert(rows) } }
    }
    suspend fun clear(generation: Long = dataGeneration): Unit = mutate(generation) {
        withContext(NonCancellable) {
            dao.clear()
            dataGeneration++
            unreadableRecords = 0
            pendingDeletes.clear()
            stagedImages.clear()
            CardImageCache.clear()
            // Failed filesystem deletion leaves encrypted orphans, never live database references.
            directory.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }
    /**
     * Stages encrypted artwork. The key gate runs inside the lock and before the file exists, so a
     * wallet whose key is gone never gains a file encrypted under a different generation.
     */
    suspend fun writeImage(bytes: ByteArray, generation: Long = dataGeneration): String {
        var path: String? = null
        try {
            return mutate(generation) {
                requireKeyForEncryption()
                writeImageLocked(bytes).also { path = it }
            }
        }
        catch (e: Exception) {
            // Also covers cancellation on dispatching the successful filename back to the caller.
            val staged = path
            if (staged != null) withContext(NonCancellable + Dispatchers.IO) {
                mutex.withLock { if (!discardIfUnusedLocked(staged)) e.addSuppressed(artworkCleanupFailure(staged)) }
            }
            throw e
        }
    }
    /** Shared read-only image bytes. Callers must copy before modifying or wiping them. */
    suspend fun image(name: String): ByteArray = withContext(Dispatchers.IO) {
        require(name.matches(Regex("[a-f0-9-]{36}\\.bin")))
        // Decrypting through a lazily created replacement key would only report false damage.
        requireVaultKeyPresent()
        // Decrypted bytes are held in memory only (bounded LRU); the file on disk stays ciphertext.
        CardImageCache.load(name) { vault.decrypt(File(directory, it).readBytes(), "image:" + it) }
    }
    suspend fun discardImage(name: String): Boolean = discardIfUnused(name)
    /** Returns true when the file is confirmed gone, or was never owned by this operation. */
    suspend fun discardIfUnused(name: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            withContext(NonCancellable) { discardIfUnusedLocked(name) }
        }
    }
    /** Called on startup only; deleted card artwork remains until then to support undo. */
    suspend fun collectOrphans() = withContext(Dispatchers.IO) {
        mutex.withLock {
            // An unreadable payload hides its image references, so nothing is provably orphaned.
            val snapshot = readSnapshot()
            if (!snapshot.complete) return@withLock
            val used = snapshot.cards.mapNotNull { it.imagePath }.toSet() + stagedImages +
                pendingDeletes.values.mapNotNull { it.imagePath }
            directory.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
        }
    }
    /**
     * onCommitted records the outcome before cancellation can interrupt the return to the caller.
     *
     * Restore writes through exactly the same key gate as any other write: it may only use an
     * existing key, and a genuinely fresh wallet may create its first one explicitly. Restoring into
     * a wallet that already holds ciphertext while the key is gone therefore fails before any row or
     * artwork is written, so old and new generations can never be mixed in place.
     */
    suspend fun merge(cards: List<WalletCard>, generation: Long = dataGeneration,
                      onCommitted: () -> Unit = {}) = mutate(generation) {
        // An empty restore encrypts nothing, so it must not demand a key either.
        if (cards.isNotEmpty()) requireKeyForEncryption()
        checkImages(cards)
        val rows = cards.map(::encode)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            database.withTransaction { dao.upsert(rows) }
            dataGeneration++
            // Restored rows may replace unreadable ones, so damage must be recomputed.
            unreadableRecords = null
            pendingDeletes.clear()
            stagedImages.removeAll(cards.mapNotNull { it.imagePath }.toSet())
            CardImageCache.clear()
            onCommitted()
        }
    }
}
