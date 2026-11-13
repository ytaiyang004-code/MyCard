package com.mycard.wallet

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.mycard.wallet.data.CardRepository
import com.mycard.wallet.data.FaceMode
import com.mycard.wallet.data.WalletCard
import com.mycard.wallet.images.CardImageCache
import com.mycard.wallet.security.BackupManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** JVM-only data: each repository has its own directory inside Robolectric's disposable sandbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupImageOwnershipTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private var repositoryNumber = 0

    private fun repository(): CardRepository {
        // Short names also keep native SQLite's journal paths within Windows path limits.
        val root = File(app.cacheDir, "bo${repositoryNumber++}")
        val databases = File(root, "databases").apply { mkdirs() }
        val artwork = File(root, "no-backup").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(databases, name)
            override fun getNoBackupFilesDir() = artwork
        }
        return CardRepository(context, TestVault())
    }

    private data class Fixture(val repository: CardRepository, val path: String, val expected: ByteArray)

    private suspend fun fixture(): Fixture {
        val bitmap = Bitmap.createBitmap(1586, 1000, Bitmap.Config.ARGB_8888)
        val expected = try {
            val pixels = IntArray(bitmap.width * bitmap.height) { index ->
                val x = index % bitmap.width
                val y = index / bitmap.width
                Color.rgb(x and 255, y and 255, (x * y) and 255)
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        // Ensure exports reach the controlled output stream while writing an image chunk.
        check(expected.size > 64 * 1024)
        val repository = repository()
        val path = repository.writeImage(expected)
        repository.save(WalletCard(bank = "Ownership test", lastFour = "1234",
            faceMode = FaceMode.IMAGE, imagePath = path, notes = "Synthetic JVM test data"))
        return Fixture(repository, path, expected)
    }

    private fun outputUri(output: ByteArrayOutputStream): Uri {
        val uri = Uri.parse("content://mycard-test/backup/${UUID.randomUUID()}")
        shadowOf(app.contentResolver).registerOutputStream(uri, output)
        return uri
    }

    private suspend fun export(fixture: Fixture): ByteArray {
        val output = ByteArrayOutputStream()
        BackupManager(app, fixture.repository).export(outputUri(output), "ownership-test-password".toCharArray())
        return output.toByteArray()
    }

    @After fun clearMemoryCache() = CardImageCache.clear()

    @Test fun exportPreservesCachedBytesTheirIdentityAndEncryptedFile() = runBlocking {
        val fixture = fixture()
        val shared = fixture.repository.image(fixture.path)
        assertSame(shared, CardImageCache.peek(fixture.path))
        assertArrayEquals(fixture.expected, shared)

        export(fixture)

        assertArrayEquals("export must not wipe bytes borrowed by image consumers", fixture.expected, shared)
        assertSame("export must preserve cache hits", shared, fixture.repository.image(fixture.path))
        assertSame(shared, CardImageCache.peek(fixture.path))
        CardImageCache.clear()
        assertArrayEquals("the encrypted source must still decrypt correctly", fixture.expected,
            fixture.repository.image(fixture.path))
    }

    @Test fun twoConsecutiveExportsBothRestoreWithoutClearingTheSourceCache() = runBlocking {
        val fixture = fixture()
        val first = export(fixture)
        val second = export(fixture)
        val sourceCard = fixture.repository.all().single()

        for (backup in listOf(first, second)) {
            val target = repository()
            val uri = Uri.parse("content://mycard-test/restore/${UUID.randomUUID()}")
            shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(backup))
            assertEquals(1, BackupManager(app, target).restore(uri, "ownership-test-password".toCharArray()))
            val restored = target.all().single()
            assertNotEquals(sourceCard.imagePath, restored.imagePath)
            assertEquals(sourceCard.copy(imagePath = restored.imagePath), restored)
            assertArrayEquals(fixture.expected, target.image(restored.imagePath!!))
        }
    }

    @Test fun concurrentReadersKeepValidSharedBytesDuringAndAfterExport(): Unit = runBlocking {
        withTimeout(30_000) {
            val fixture = fixture()
            val shared = fixture.repository.image(fixture.path)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CompletableDeferred<Unit>()
            val ready = Channel<Unit>(4)
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (length > 28) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS)) { "Timed out waiting for image readers" }
                    }
                    super.write(bytes, offset, length)
                }
            }
            val uri = outputUri(output)
            try {
                val exporting = async(Dispatchers.IO) {
                    BackupManager(app, fixture.repository).export(uri, "ownership-test-password".toCharArray())
                }
                assertTrue("export must reach its image chunk", entered.await(15, TimeUnit.SECONDS))
                val readers = (1..4).map {
                    async(Dispatchers.IO) {
                        val image = fixture.repository.image(fixture.path)
                        assertSame(shared, image)
                        assertArrayEquals(fixture.expected, image)
                        ready.send(Unit)
                        finished.await()
                        assertArrayEquals("export must not invalidate a reader's existing reference", fixture.expected, image)
                        assertSame(image, fixture.repository.image(fixture.path))
                    }
                }
                repeat(4) { ready.receive() }
                release.countDown()
                exporting.await()
                finished.complete(Unit)
                readers.awaitAll()
            } finally {
                release.countDown()
                finished.complete(Unit)
                ready.close()
            }
        }
    }

    @Test fun failedExportPreservesCachedBytesAndClearsPassword() = runBlocking {
        val fixture = fixture()
        val shared = fixture.repository.image(fixture.path)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > 28) throw IOException("Synthetic storage failure")
                super.write(bytes, offset, length)
            }
        }
        val password = "ownership-test-password".toCharArray()
        try {
            BackupManager(app, fixture.repository).export(outputUri(output), password)
            fail("The injected output failure must reach the caller")
        } catch (_: IOException) { }
        assertTrue(password.all { it == '\u0000' })
        assertArrayEquals("failed exports must not wipe shared images either", fixture.expected, shared)
        assertSame(shared, fixture.repository.image(fixture.path))
    }
}
