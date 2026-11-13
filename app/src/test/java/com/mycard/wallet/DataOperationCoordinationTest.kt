package com.mycard.wallet

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.mycard.wallet.data.*
import com.mycard.wallet.security.BackupManager
import com.mycard.wallet.security.Crypto
import com.mycard.wallet.security.VaultCipher
import kotlinx.coroutines.*
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
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** All storage lives in the Robolectric sandbox, with synthetic cards and a test-only key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataOperationCoordinationTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val stores = mutableListOf<ViewModelStore>()
    private val art get() = File(app.cacheDir, "t4/art/card_art")
    private fun repository(vault: VaultCipher = TestVault()): CardRepository {
        val root = File(app.cacheDir, "t4")
        return CardRepository(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(root, "db/$name").also { it.parentFile!!.mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "art").apply { mkdirs() }
        }, vault)
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            check(System.nanoTime() < deadline) { "Operation did not complete" }
            Thread.sleep(5)
        }
    }
    private fun model(repo: CardRepository): WalletViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WalletViewModel(RuntimeEnvironment.getApplication(), repo) as T
        }
        return ViewModelProvider(store, factory)[WalletViewModel::class.java].also { waitFor { it.cardsLoaded.value } }
    }
    @After fun closeModels() { stores.forEach { it.clear() }; ShadowLooper.idleMainLooper() }

    private fun archive(card: WalletCard, image: ByteArray? = null): ByteArray {
        val manifest = JSONObject().put("version", 2).put("cards", JSONArray().put(CardJson.encode(card))).toString().toByteArray()
        return ByteArrayOutputStream().also { raw ->
            DataOutputStream(Crypto.backupSink(raw, "coordination-test".toCharArray())).use {
                it.writeInt(manifest.size); it.write(manifest); it.writeInt(if (image == null) 0 else 1)
                if (image != null) { it.writeUTF(card.imagePath!!); it.writeInt(image.size); it.write(image) }
            }
        }.toByteArray()
    }
    private fun backup(card: WalletCard): Uri = input(ByteArrayInputStream(archive(card)))
    private fun input(stream: ByteArrayInputStream): Uri {
        val uri = Uri.parse("content://mycard-test/t4/${UUID.randomUUID()}")
        shadowOf(app.contentResolver).registerInputStream(uri, stream)
        return uri
    }
    private class Gate {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        fun block() { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) { "Gate release timed out" } }
        fun await() { check(entered.await(15, TimeUnit.SECONDS)) { "Gate was never reached" } }
        fun open() { release.countDown() }
    }
    private class GatedVault(private val gate: Gate, private val prefix: String) : VaultCipher {
        private val delegate = TestVault()
        private val once = AtomicBoolean()
        override fun encrypt(bytes: ByteArray, context: String): ByteArray {
            if (context.startsWith(prefix) && once.compareAndSet(false, true)) gate.block()
            return delegate.encrypt(bytes, context)
        }
        override fun decrypt(bytes: ByteArray, context: String) = delegate.decrypt(bytes, context)
    }
    private fun gatedInput(bytes: ByteArray, gate: Gate) = input(object : ByteArrayInputStream(bytes) {
        override fun read(): Int = super.read().also { if (it == -1) gate.block() }
    })
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(1586, 1000, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private suspend fun assertReferences(repo: CardRepository) {
        repo.all().mapNotNull { it.imagePath }.forEach { assertTrue("Referenced file $it must exist", File(art, it).isFile) }
    }

    @Test fun oldUndoCannotResurrectACardAfterClear() = runBlocking {
        val repo = repository()
        repo.save(WalletCard(bank = "Synthetic deleted card", lastFour = "1234"))
        val vm = model(repo)
        val job = vm.delete(repo.all().single()) { }
        waitFor { job.isCompleted }
        val notice = vm.notices.tryReceive().getOrThrow()
        repo.clear()
        val undo = vm.undo(notice.undo!!)
        waitFor { undo.isCompleted }
        assertTrue("a pre-clear snackbar must not restore old data", repo.all().isEmpty())
    }

    @Test fun oldUndoCannotResurrectACardAfterRestore() = runBlocking {
        val repo = repository()
        repo.save(WalletCard(bank = "Synthetic deleted card", lastFour = "1234"))
        val vm = model(repo)
        val job = vm.delete(repo.all().single()) { }
        waitFor { job.isCompleted }
        val notice = vm.notices.tryReceive().getOrThrow()
        val restored = WalletCard(bank = "Synthetic restored card", lastFour = "5678")
        BackupManager(app, repo).restore(backup(restored), "coordination-test".toCharArray())
        val undo = vm.undo(notice.undo!!)
        waitFor { undo.isCompleted }
        assertEquals("restore must invalidate older delete actions", listOf(restored), repo.all())
    }

    @Test fun aClearedImageCannotBeSavedAsADanglingReference() = runBlocking {
        val repo = repository()
        val path = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.clear()
        assertFalse(File(art, path).exists())
        val card = WalletCard(bank = "Stale image save", lastFour = "1234", imagePath = path)
        assertTrue("a missing image must never become a committed reference", runCatching { repo.save(card) }.isFailure)
        assertTrue(repo.all().isEmpty())
    }

    @Test fun startupCleanupCannotRemoveAnImageBeingStagedForSave() = runBlocking {
        val repo = repository()
        val path = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.collectOrphans()
        assertTrue("uncommitted artwork still owned by an active operation must be retained", File(art, path).exists())
        repo.save(WalletCard(bank = "Staged image", lastFour = "1234", imagePath = path))
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.image(path))
    }

    @Test fun aDraftOpenedBeforeClearCannotSaveIntoTheNewDataset() = runBlocking {
        val repo = repository()
        val vm = model(repo)
        vm.beginEdit(null)
        vm.draft.value = vm.draft.value!!.copy(bank = "Old draft", lastFour = "1234")
        repo.clear()
        vm.saveDraft { }
        waitFor { !vm.busy.value }
        assertTrue("clearing invalidates previously opened edit sessions", repo.all().isEmpty())
    }

    @Test fun undoIsSingleUseAndCannotOverwriteASubsequentEdit() = runBlocking {
        val repo = repository()
        repo.save(WalletCard(bank = "Original", lastFour = "1234"))
        val token = repo.deleteForUndo(repo.all().single())!!
        assertTrue(repo.undo(token))
        repo.update(repo.all().single().copy(nickname = "Newer edit"))
        assertFalse(repo.undo(token))
        repo.finishDelete(token)
        assertEquals("Newer edit", repo.all().single().nickname)
    }

    @Test fun repeatedDeleteIssuesOnlyOneTokenAndPendingUndoProtectsArtwork() = runBlocking {
        val repo = repository()
        val path = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "Undo artwork", lastFour = "1234", imagePath = path))
        val card = repo.all().single()
        val token = repo.deleteForUndo(card)!!
        assertNull(repo.deleteForUndo(card))
        repo.collectOrphans()
        assertTrue(File(art, path).isFile)
        assertTrue(repo.undo(token))
        assertReferences(repo)
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.image(path))
    }

    @Test fun expiredUndoCannotRestoreDeletedArtwork() = runBlocking {
        val repo = repository()
        val path = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "Expired undo", lastFour = "1234", imagePath = path))
        val token = repo.deleteForUndo(repo.all().single())!!
        repo.finishDelete(token); repo.finishDelete(token)
        assertFalse(File(art, path).exists())
        assertFalse(repo.undo(token))
        assertTrue(repo.all().isEmpty())
    }

    @Test fun expiringADeletionDoesNotRemoveSharedArtwork() = runBlocking {
        val repo = repository()
        val path = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "First", lastFour = "1234", imagePath = path))
        repo.save(WalletCard(bank = "Second", lastFour = "5678", imagePath = path))
        val token = repo.deleteForUndo(repo.all().first())!!
        repo.finishDelete(token)
        repo.discardImage(path)
        assertReferences(repo)
        assertTrue(File(art, path).isFile)
    }

    @Test fun aStaleUpdateCannotResurrectADeletionOrOverwriteAnEdit() = runBlocking {
        val repo = repository()
        repo.save(WalletCard(bank = "Original", lastFour = "1234"))
        val original = repo.all().single()
        repo.update(original.copy(nickname = "New edit"))
        assertTrue(runCatching { repo.update(original.copy(notes = "Stale edit")) }.exceptionOrNull() is StaleDataOperationException)
        assertEquals("New edit", repo.all().single().nickname)
        repo.deleteForUndo(repo.all().single())
        assertTrue(runCatching { repo.update(original) }.exceptionOrNull() is StaleDataOperationException)
        assertTrue(repo.all().isEmpty())
    }

    @Test fun imageAndCardSaveCompleteBeforeAQueuedClear() = runBlocking {
        val gate = Gate()
        val repo = repository(GatedVault(gate, "image:"))
        val generation = repo.dataGeneration
        val saving = async(Dispatchers.IO) {
            repo.saveWithImage(WalletCard(bank = "Atomic save", lastFour = "1234"), byteArrayOf(1, 2, 3), null, generation)
        }
        try {
            gate.await()
            // UNDISPATCHED + IO context registers this mutex waiter before returning to the test.
            val clearing = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { repo.clear(generation) }
            assertFalse(clearing.isCompleted)
            gate.open()
            withTimeout(15_000) { saving.await(); clearing.await() }
            assertTrue(repo.all().isEmpty())
            assertTrue(art.listFiles().isNullOrEmpty())
        } finally { gate.open() }
    }

    @Test fun failedRecordSaveRemovesOnlyItsNewImage() = runBlocking {
        val delegate = TestVault()
        val vault = object : VaultCipher {
            override fun encrypt(bytes: ByteArray, context: String): ByteArray {
                if (context.startsWith("card:")) throw IOException("Synthetic record write failure")
                return delegate.encrypt(bytes, context)
            }
            override fun decrypt(bytes: ByteArray, context: String) = delegate.decrypt(bytes, context)
        }
        val repo = repository(vault)
        assertTrue(runCatching { repo.saveWithImage(WalletCard(bank = "Failed", lastFour = "1234"), byteArrayOf(1, 2, 3), null) }.isFailure)
        assertTrue(repo.all().isEmpty())
        assertTrue(art.listFiles().isNullOrEmpty())
        // A subsequent operation can acquire the same mutex; failure did not leave it locked.
        repo.clear()
    }

    @Test fun cancellationAfterImageWriteCleansStagingWithoutCommitting() = runBlocking {
        val gate = Gate()
        val repo = repository(GatedVault(gate, "image:"))
        val saving = async(Dispatchers.IO) {
            repo.saveWithImage(WalletCard(bank = "Cancelled", lastFour = "1234"), byteArrayOf(1, 2, 3), null)
        }
        try { gate.await(); saving.cancel() } finally { gate.open() }
        withTimeout(15_000) { saving.join() }
        assertTrue(repo.all().isEmpty())
        assertTrue(art.listFiles().isNullOrEmpty())
        repo.clear()
    }

    @Test fun restoreWaitsForASaveThenRejectsOlderQueuedWrites() = runBlocking {
        val gate = Gate()
        val repo = repository(GatedVault(gate, "card:"))
        val card = WalletCard(bank = "Saving", lastFour = "1234")
        val generation = repo.dataGeneration
        val saving = async(Dispatchers.IO) { repo.saveWithImage(card, null, null, generation) }
        try {
            gate.await()
            val restored = card.copy(bank = "Restored", notes = "Preserve restored data")
            val restoring = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { repo.merge(listOf(restored), generation) }
            val stale = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runCatching { repo.save(card, generation) } }
            assertFalse(restoring.isCompleted)
            gate.open()
            withTimeout(15_000) {
                saving.await(); restoring.await()
                assertTrue(stale.await().exceptionOrNull() is StaleDataOperationException)
            }
            assertEquals(restored, repo.all().single())
        } finally { gate.open() }
    }

    @Test fun clearBeforeQueuedRestorePreventsTheOldRestoreFromRepopulating() = runBlocking {
        val gate = Gate()
        val repo = repository(GatedVault(gate, "card:"))
        val card = WalletCard(bank = "Saving", lastFour = "1234")
        val generation = repo.dataGeneration
        val saving = async(Dispatchers.IO) { repo.saveWithImage(card, null, null, generation) }
        try {
            gate.await()
            val clearing = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { repo.clear(generation) }
            val restoring = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runCatching { repo.merge(listOf(card), generation) } }
            gate.open()
            withTimeout(15_000) {
                saving.await(); clearing.await()
                assertTrue(restoring.await().exceptionOrNull() is StaleDataOperationException)
            }
            assertTrue(repo.all().isEmpty())
        } finally { gate.open() }
    }

    @Test fun restoreBeforeQueuedClearProtectsTheNewDataset() = runBlocking {
        val gate = Gate()
        val repo = repository(GatedVault(gate, "card:"))
        val restored = WalletCard(bank = "Restored", lastFour = "1234")
        val generation = repo.dataGeneration
        val restoring = async(Dispatchers.IO) { repo.merge(listOf(restored), generation) }
        try {
            gate.await()
            val clearing = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runCatching { repo.clear(generation) } }
            gate.open()
            withTimeout(15_000) {
                restoring.await()
                assertTrue(clearing.await().exceptionOrNull() is StaleDataOperationException)
            }
            assertEquals(restored, repo.all().single())
        } finally { gate.open() }
    }

    @Test fun failedRestorePreservesExistingUndoCapability() = runBlocking {
        val repo = repository()
        repo.save(WalletCard(bank = "Deleted", lastFour = "1234"))
        val token = repo.deleteForUndo(repo.all().single())!!
        val generation = repo.dataGeneration
        assertTrue(runCatching { BackupManager(app, repo).restore(backup(WalletCard(bank = "Rejected", lastFour = "5678")), "incorrect-password".toCharArray()) }.isFailure)
        assertEquals(generation, repo.dataGeneration)
        assertTrue(repo.undo(token))
        assertEquals("Deleted", repo.all().single().bank)
    }

    @Test fun stagedRestoreImagesSurviveCleanupAndCommitWithTheirRows() = runBlocking {
        val gate = Gate()
        val repo = repository()
        val card = WalletCard(bank = "Restored artwork", lastFour = "1234", imagePath = "${UUID.randomUUID()}.bin")
        val image = png()
        val uri = gatedInput(archive(card, image), gate)
        val restoring = async(Dispatchers.IO) { BackupManager(app, repo).restore(uri, "coordination-test".toCharArray()) }
        try {
            gate.await()
            repo.collectOrphans()
            assertEquals(1, art.listFiles()!!.size)
            gate.open()
            assertEquals(1, withTimeout(15_000) { restoring.await() })
            assertReferences(repo)
            assertArrayEquals(image, repo.image(repo.all().single().imagePath!!))
        } finally { gate.open() }
    }

    @Test fun clearingDuringRestoreStagingPreventsCommitAndLeavesNoDanglingFiles() = runBlocking {
        val gate = Gate()
        val repo = repository()
        val card = WalletCard(bank = "Old restore", lastFour = "1234", imagePath = "${UUID.randomUUID()}.bin")
        val uri = gatedInput(archive(card, png()), gate)
        val restoring = async(Dispatchers.IO) { runCatching { BackupManager(app, repo).restore(uri, "coordination-test".toCharArray()) } }
        try {
            gate.await()
            repo.clear()
            gate.open()
            assertTrue(withTimeout(15_000) { restoring.await() }.exceptionOrNull() is StaleDataOperationException)
            assertTrue(repo.all().isEmpty())
            assertTrue(art.listFiles().isNullOrEmpty())
        } finally { gate.open() }
    }

    @Test fun cancelledRestoreCleansStagedFilesAndKeepsUndoValid() = runBlocking {
        val gate = Gate()
        val repo = repository()
        repo.save(WalletCard(bank = "Existing undo", lastFour = "1234"))
        val token = repo.deleteForUndo(repo.all().single())!!
        val card = WalletCard(bank = "Cancelled restore", lastFour = "5678", imagePath = "${UUID.randomUUID()}.bin")
        val uri = gatedInput(archive(card, png()), gate)
        val restoring = async(Dispatchers.IO) { BackupManager(app, repo).restore(uri, "coordination-test".toCharArray()) }
        try { gate.await(); restoring.cancel() } finally { gate.open() }
        withTimeout(15_000) { restoring.join() }
        assertTrue(art.listFiles().isNullOrEmpty())
        assertTrue(repo.undo(token))
        assertEquals("Existing undo", repo.all().single().bank)
    }

    @Test fun cancellingAMutationWhileItWaitsDoesNotDeadlockLaterOperations() = runBlocking {
        val gate = Gate()
        val repo = repository(GatedVault(gate, "card:"))
        val card = WalletCard(bank = "Save", lastFour = "1234")
        val saving = async(Dispatchers.IO) { repo.saveWithImage(card, null, null) }
        try {
            gate.await()
            val restoring = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { repo.merge(listOf(card.copy(bank = "Cancelled restore"))) }
            restoring.cancel()
            gate.open()
            withTimeout(15_000) { saving.await(); restoring.join() }
            assertEquals("Save", repo.all().single().bank)
            repo.clear()
            assertTrue(repo.all().isEmpty())
        } finally { gate.open() }
    }

    @Test fun duplicateViewModelSaveDeleteAndUndoHaveOnlyOneEffect() = runBlocking {
        val gate = Gate()
        val repo = repository(GatedVault(gate, "image:"))
        val vm = model(repo)
        vm.beginEdit(null)
        vm.draft.value = vm.draft.value!!.copy(bank = "Single save", lastFour = "1234")
        vm.draftImage.value = byteArrayOf(1, 2, 3)
        var saved = 0
        vm.saveDraft { saved++ }
        vm.saveDraft { saved++ }
        try { waitFor { vm.busy.value }; ShadowLooper.idleMainLooper(); gate.await() }
        finally { gate.open() }
        waitFor { !vm.busy.value }
        assertEquals(1, saved)
        assertEquals(1, repo.all().size)
        val card = repo.all().single()
        var deleted = 0
        val first = vm.delete(card) { deleted++ }
        val second = vm.delete(card) { deleted++ }
        waitFor { first.isCompleted && second.isCompleted }
        assertEquals(1, deleted)
        val token = vm.notices.tryReceive().getOrThrow().undo!!
        assertTrue(vm.notices.tryReceive().isFailure)
        val undoFirst = vm.undo(token)
        val undoSecond = vm.undo(token)
        waitFor { undoFirst.isCompleted && undoSecond.isCompleted }
        assertEquals(1, repo.all().size)
        assertReferences(repo)
    }

    @Test fun repeatedClearClicksShareOnePendingJob() = runBlocking {
        val gate = Gate()
        val repo = repository(GatedVault(gate, "image:"))
        val vm = model(repo)
        val generation = repo.dataGeneration
        val saving = async(Dispatchers.IO) {
            repo.saveWithImage(WalletCard(bank = "Saving", lastFour = "1234"), byteArrayOf(1, 2, 3), null)
        }
        try {
            gate.await()
            val first = vm.clearWallet()
            assertSame(first, vm.clearWallet())
            ShadowLooper.idleMainLooper()
            gate.open()
            saving.await()
            waitFor { first.isCompleted }
            assertEquals(generation + 1, repo.dataGeneration)
            assertTrue(repo.all().isEmpty())
        } finally { gate.open() }
    }

    @Test fun allQueuedMutationTypesRejectAnInvalidatedGeneration() = runBlocking {
        val repo = repository()
        val generation = repo.dataGeneration
        repo.clear()
        val card = WalletCard(bank = "Stale", lastFour = "1234")
        val operations: List<suspend () -> Any?> = listOf(
            { repo.save(card, generation) }, { repo.update(card, generation) },
            { repo.saveWithImage(card, byteArrayOf(1), null, generation) },
            { repo.delete(card.id, generation) }, { repo.deleteForUndo(card, generation) },
            { repo.reorder(emptyList(), generation) }, { repo.writeImage(byteArrayOf(1), generation) },
            { repo.merge(listOf(card), generation) }, { repo.clear(generation) }
        )
        operations.forEach { operation -> assertTrue(runCatching { operation() }.exceptionOrNull() is StaleDataOperationException) }
        assertEquals(generation + 1, repo.dataGeneration)
        assertTrue(repo.all().isEmpty())
        assertTrue(art.listFiles().isNullOrEmpty())
    }

    @Test fun restoreRecordFailureCleansOnlyStagingAndKeepsOldUndo() = runBlocking {
        val delegate = TestVault()
        val fail = AtomicBoolean()
        val repo = repository(object : VaultCipher {
            override fun encrypt(bytes: ByteArray, context: String): ByteArray {
                if (context.startsWith("card:") && fail.get()) throw IOException("Synthetic restore commit failure")
                return delegate.encrypt(bytes, context)
            }
            override fun decrypt(bytes: ByteArray, context: String) = delegate.decrypt(bytes, context)
        })
        val oldPath = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "Old undo", lastFour = "1234", imagePath = oldPath))
        val token = repo.deleteForUndo(repo.all().single())!!
        val generation = repo.dataGeneration
        val incoming = WalletCard(bank = "Rejected restore", lastFour = "5678", imagePath = "${UUID.randomUUID()}.bin")
        val uri = input(ByteArrayInputStream(archive(incoming, png())))
        fail.set(true)
        assertTrue(runCatching { BackupManager(app, repo).restore(uri, "coordination-test".toCharArray()) }.exceptionOrNull() is IOException)
        assertEquals(generation, repo.dataGeneration)
        assertEquals(listOf(oldPath), art.listFiles()!!.map { it.name })
        fail.set(false)
        assertTrue(repo.undo(token))
        assertReferences(repo)
    }

    @Test fun oldUndoAndCleanupCannotChangeARestoredCardWithTheSameId() = runBlocking {
        val repo = repository()
        val oldPath = repo.writeImage(byteArrayOf(1, 2, 3))
        repo.save(WalletCard(bank = "Old", lastFour = "1234", imagePath = oldPath))
        val original = repo.all().single()
        val token = repo.deleteForUndo(original)!!
        val newPath = repo.writeImage(byteArrayOf(4, 5, 6))
        val restored = original.copy(bank = "Restored", imagePath = newPath)
        repo.merge(listOf(restored))
        assertFalse(repo.undo(token))
        repo.finishDelete(token)
        repo.collectOrphans()
        assertEquals(restored, repo.all().single())
        assertFalse(File(art, oldPath).exists())
        assertReferences(repo)
        assertArrayEquals(byteArrayOf(4, 5, 6), repo.image(newPath))
    }

    @Test fun cancelledStandaloneImageWriteDoesNotLeaveAnUnownedReservation() = runBlocking {
        val gate = Gate()
        val repo = repository(GatedVault(gate, "image:"))
        val writing = async(Dispatchers.IO) { repo.writeImage(byteArrayOf(1, 2, 3)) }
        try { gate.await(); writing.cancel() } finally { gate.open() }
        withTimeout(15_000) { writing.join() }
        repo.collectOrphans()
        assertTrue(art.listFiles().isNullOrEmpty())
        assertTrue(repo.all().isEmpty())
    }
}
