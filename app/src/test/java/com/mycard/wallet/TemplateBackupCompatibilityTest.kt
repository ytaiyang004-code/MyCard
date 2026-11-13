package com.mycard.wallet

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.mycard.wallet.data.*
import com.mycard.wallet.security.BackupManager
import com.mycard.wallet.security.Crypto
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
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

/** Synthetic template-only archives, including committed samples exported by the T3 sources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplateBackupCompatibilityTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val password = "synthetic-诊断-2026"
    private val stores = mutableListOf<ViewModelStore>()
    private var sequence = 0
    private fun repository(): CardRepository {
        val root = File(app.cacheDir, "td${sequence++}")
        return CardRepository(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(root, "db/$name").also { it.parentFile!!.mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "art").apply { mkdirs() }
        }, TestVault())
    }
    private fun model(repo: CardRepository): WalletViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WalletViewModel(RuntimeEnvironment.getApplication(), repo) as T
        }
        return ViewModelProvider(store, factory)[WalletViewModel::class.java].also { vm -> waitFor { vm.cardsLoaded.value } }
    }
    @After fun closeModels() { stores.forEach { it.clear() }; ShadowLooper.idleMainLooper() }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            check(System.nanoTime() < deadline) { "Synthetic operation timed out" }
            Thread.sleep(5)
        }
    }
    private fun uri() = Uri.parse("content://mycard-synthetic/diagnosis/${UUID.randomUUID()}")
    private fun input(bytes: ByteArray) = uri().also { shadowOf(app.contentResolver).registerInputStream(it, ByteArrayInputStream(bytes)) }
    private suspend fun seed(repo: CardRepository): List<WalletCard> {
        listOf(0, 5, 15).forEach { template ->
            repo.save(WalletCard(bank = "Synthetic template $template", lastFour = "1234", template = template))
        }
        return repo.all().sortedBy { it.id }.also { cards -> assertTrue(cards.all { it.imagePath == null && it.faceMode == FaceMode.TEMPLATE }) }
    }
    private suspend fun export(repo: CardRepository, passphrase: String = password): ByteArray {
        val output = ByteArrayOutputStream()
        val target = uri().also { shadowOf(app.contentResolver).registerOutputStream(it, output) }
        val chars = passphrase.toCharArray()
        BackupManager(app, repo).export(target, chars)
        assertTrue("owned export password must be cleared", chars.all { it == '\u0000' })
        return output.toByteArray()
    }
    private fun assertNoImages(bytes: ByteArray, passphrase: String = password) {
        DataInputStream(Crypto.backupSource(ByteArrayInputStream(bytes), passphrase.toCharArray())).use { stream ->
            val manifest = ByteArray(stream.readInt()).also(stream::readFully)
            val cards = JSONObject(manifest.toString(Charsets.UTF_8)).getJSONArray("cards")
            repeat(cards.length()) { assertTrue(cards.getJSONObject(it).isNull("imagePath")) }
            assertEquals("template-only archives must contain zero image entries", 0, stream.readInt())
            assertEquals(-1, stream.read())
            manifest.fill(0)
        }
    }
    private fun start(vm: WalletViewModel, action: BackupAction, target: Uri, passphrase: String): String {
        val id = vm.requestBackupDocument(action)!!
        vm.backupDocumentSelected(id, target)
        val chars = passphrase.toCharArray()
        assertTrue(vm.startBackup(id, chars))
        waitFor { vm.backupOperation.value.step != BackupStep.RUNNING }
        assertTrue("each operation must clear its own password", chars.all { it == '\u0000' })
        return id
    }

    @Test fun consecutiveTemplateExportsBothRestoreAfterClear() = runBlocking {
        val repo = repository()
        val expected = seed(repo)
        val first = export(repo)
        val second = export(repo)
        assertFalse(first.contentEquals(second)) // independent salt/nonces, never reused output state
        for (bytes in listOf(first, second)) {
            assertNoImages(bytes)
            repo.clear()
            val chars = password.toCharArray()
            assertEquals(3, BackupManager(app, repo).restore(input(bytes), chars))
            assertTrue(chars.all { it == '\u0000' })
            assertEquals(expected, repo.all().sortedBy { it.id })
        }
    }

    @Test fun wrongPasswordForEitherExportLeavesExistingCardsUnchanged() = runBlocking {
        val source = repository()
        seed(source)
        val backups = listOf(export(source), export(source))
        val target = repository()
        target.save(WalletCard(bank = "Synthetic existing marker", lastFour = "5678"))
        val expected = target.all()
        for (bytes in backups) {
            val chars = "synthetic-wrong-password".toCharArray()
            assertTrue(runCatching { BackupManager(app, target).restore(input(bytes), chars) }.exceptionOrNull() is AEADBadTagException)
            assertEquals(expected, target.all())
            assertTrue(chars.all { it == '\u0000' })
        }
    }

    @Test fun viewModelExportRestoreTransitionsDoNotReusePasswordsOrIds() = runBlocking {
        val repo = repository()
        val expected = seed(repo)
        val vm = model(repo)
        val passphrases = listOf(password, "synthetic-second-password")
        val ids = mutableSetOf<String>()
        val backups = passphrases.map { passphrase ->
            val output = ByteArrayOutputStream()
            val target = uri().also { shadowOf(app.contentResolver).registerOutputStream(it, output) }
            val id = start(vm, BackupAction.EXPORT, target, passphrase)
            assertEquals(BackupStep.SUCCEEDED, vm.backupOperation.value.step)
            assertTrue(ids.add(id))
            vm.dismissBackup(id)
            assertEquals(BackupStep.IDLE, vm.backupOperation.value.step)
            output.toByteArray().also { assertNoImages(it, passphrase) }
        }
        backups.forEachIndexed { index, bytes ->
            repo.clear()
            val id = start(vm, BackupAction.RESTORE, input(bytes), passphrases[index])
            assertEquals(BackupStep.SUCCEEDED, vm.backupOperation.value.step)
            assertTrue(ids.add(id))
            assertEquals(expected, repo.all().sortedBy { it.id })
            vm.dismissBackup(id)
        }
        val wrong = start(vm, BackupAction.RESTORE, input(backups[0]), passphrases[1])
        assertEquals(BackupStep.VALIDATION_FAILED, vm.backupOperation.value.step)
        assertEquals(expected, repo.all().sortedBy { it.id })
        assertFalse(vm.backupOperation.value.message!!.contains("现有卡片未改变"))
        vm.dismissBackup(wrong)
    }

    @Test fun passwordIsIntactUntilHeaderReadAndClearedOnlyAfterKeyDerivation() = runBlocking {
        val source = repository()
        seed(source)
        val bytes = export(source)
        val chars = password.toCharArray()
        var headerObserved = false
        var contentsObserved = false
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos == 0) {
                    headerObserved = true
                    assertTrue("password was cleared before derivation", chars.contentEquals(password.toCharArray()))
                }
                return super.read(buffer, offset, length)
            }
            override fun read(): Int {
                if (pos >= 28) {
                    contentsObserved = true
                    assertTrue("password should no longer be retained after derivation", chars.all { it == '\u0000' })
                }
                return super.read()
            }
        }
        val target = uri().also { shadowOf(app.contentResolver).registerInputStream(it, stream) }
        assertEquals(3, BackupManager(app, repository()).restore(target, chars))
        assertTrue(headerObserved && contentsObserved)
    }

    @Test fun distinctValidationErrorsExposeTheirFailureStages() = runBlocking {
        val repo = repository()
        val expected = seed(repo)
        val vm = model(repo)
        fun archive(root: JSONObject): ByteArray = ByteArrayOutputStream().also { raw ->
            DataOutputStream(Crypto.backupSink(raw, password.toCharArray())).use {
                val json = root.toString().toByteArray()
                it.writeInt(json.size); it.write(json); it.writeInt(0)
            }
        }.toByteArray()
        val valid = export(repo)
        val cases = listOf(
            valid to "synthetic-wrong-password",
            valid.copyOf(20) to password,
            archive(JSONObject().put("version", 99).put("cards", JSONArray())) to password,
            archive(JSONObject().put("version", 2).put("cards", JSONArray().put(JSONObject()))) to password
        )
        val stages = listOf("AUTH", "HEADER", "VALIDATION", "VALIDATION")
        for ((index, case) in cases.withIndex()) {
            val (bytes, passphrase) = case
            val id = start(vm, BackupAction.RESTORE, input(bytes), passphrase)
            assertEquals(BackupStep.VALIDATION_FAILED, vm.backupOperation.value.step)
            assertEquals(stages[index], vm.backupOperation.value.diagnostic!!.stage.name)
            assertFalse(vm.backupOperation.value.diagnostic!!.committed)
            assertTrue(vm.backupOperation.value.message!!.contains("stage=${stages[index]}"))
            assertEquals(expected, repo.all().sortedBy { it.id })
            vm.dismissBackup(id)
        }
    }

    @Test fun restoreFixturesExportedByTheUnmodifiedT3ProductionSources() = runBlocking {
        val repo = repository()
        val hashes = listOf(
            "911f400e4550f99933263d9e61295fab2896c7a451f8b1129ea9e5844fbbe029",
            "393fbf03c0d834497a83e022fb143e2469ead64c5b4afb7ae01eab3cd42b1901"
        )
        repeat(2) { index ->
            val resource = "/backups/t3/template-${index + 1}.mycard"
            val bytes = checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing synthetic fixture: $resource" }
                .use { it.readBytes() }
            assertEquals("historical fixtures must not be regenerated by current code", hashes[index],
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            assertNoImages(bytes)
            repo.clear()
            assertEquals(3, BackupManager(app, repo).restore(input(bytes), password.toCharArray()))
            assertEquals(setOf(0, 5, 15), repo.all().map { it.template }.toSet())
            assertTrue(repo.all().all { it.imagePath == null })
        }
    }
}
