package com.mycard.wallet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.mycard.wallet.data.*
import com.mycard.wallet.images.*
import com.mycard.wallet.security.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** Software key is test-only. Actual Keystore integration stays in androidTest. */
internal class TestVault : VaultCipher {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    override fun encrypt(bytes: ByteArray, context: String) = Crypto.seal(bytes, key, context.toByteArray())
    override fun decrypt(bytes: ByteArray, context: String) = Crypto.open(bytes, key, context.toByteArray())
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidPipelineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun realPixelMaskAndPngRoundTrip() = runBlocking {
        val input = Bitmap.createBitmap(2000, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val output = ImageProcessor.render(input, CropTransform(), listOf(Mask(.25f, .3f, .5f, .6f)))
        val decoded = BitmapFactory.decodeByteArray(output, 0, output.size)
        assertEquals(1586, decoded.width); assertEquals(1000, decoded.height)
        assertEquals(Color.BLACK, decoded.getPixel(500, 400))
        assertEquals(Color.RED, decoded.getPixel(100, 100))
        assertFalse(output.toString(Charsets.ISO_8859_1).contains("eXIf"))
        input.recycle(); decoded.recycle()
    }
    @Test fun cropBoundsCoverFrameAtEveryZoomAndPan() {
        val bitmap = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888)
        listOf(1f, 2f, 6f).forEach { zoom ->
            listOf(-10f, 0f, 10f).forEach { pan ->
                val rect = ImageProcessor.destination(bitmap, 1586f, 1000f, CropTransform(zoom, pan, pan))
                assertTrue(rect.left <= .01f && rect.top <= .01f && rect.right >= 1585.99f && rect.bottom >= 999.99f)
            }
        }
        bitmap.recycle()
    }
    @Test fun whitespaceTrimFindsCardBounds() = runBlocking {
        val bitmap = Bitmap.createBitmap(200, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(bitmap).drawRect(20f, 20f, 180f, 120f, Paint().apply { color = Color.BLUE })
        val trimmed = ImageProcessor.trimWhitespace(bitmap)
        assertEquals(160, trimmed.width); assertEquals(100, trimmed.height)
        trimmed.recycle(); bitmap.recycle()
    }
    @Test fun invalidOrCanceledImageDoesNotCreateDiskCache() = runBlocking {
        val file = File(context.cacheDir, "not-an-image.png").apply { writeText("invalid") }
        try {
            try { ImageProcessor.decode(context.contentResolver, Uri.fromFile(file)); fail("Must reject invalid data") }
            catch (_: Exception) { }
            assertTrue(File(context.noBackupFilesDir, "card_art").listFiles().isNullOrEmpty())
        } finally { file.delete() }
    }
    @Test fun roomPersistsEncryptedUpdatesAndDelete() = runBlocking {
        val repo = CardRepository(context, TestVault())
        repo.clear()
        val card = WalletCard(bank = "SENSITIVE_BANK", lastFour = "0088", notes = "PRIVATE_NOTE")
        repo.save(card)
        repo.save(card.copy(nickname = "已更新"))
        assertEquals("已更新", CardRepository(context, TestVault()).all().single().nickname)
        context.getDatabasePath("wallet.db").parentFile!!.listFiles()!!.filter { it.name.startsWith("wallet.db") }.forEach {
            val raw = it.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("SENSITIVE_BANK")); assertFalse(raw.contains("PRIVATE_NOTE"))
        }
        repo.delete(card.id); assertTrue(repo.all().isEmpty())
    }
    @Test fun imageBackupRestoresIntoFreshEncryptedFilesAndRejectsCorruption() = runBlocking {
        val repo = CardRepository(context, TestVault()); repo.clear()
        val bitmap = Bitmap.createBitmap(1586, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val image = ImageProcessor.render(bitmap, CropTransform(), emptyList()); bitmap.recycle()
        val path = repo.writeImage(image)
        val card = WalletCard(bank = "图像备份", lastFour = "0011", faceMode = FaceMode.IMAGE, imagePath = path)
        repo.save(card)
        val file = File(context.cacheDir, "roundtrip.mycard")
        val manager = BackupManager(context, repo)
        try {
            manager.export(Uri.fromFile(file), "strong-test-password".toCharArray())
            val original = file.readBytes()
            val damaged = original.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
            file.writeBytes(damaged)
            try { manager.restore(Uri.fromFile(file), "strong-test-password".toCharArray()); fail() }
            catch (_: javax.crypto.AEADBadTagException) { }
            assertEquals(path, repo.all().single().imagePath)
            file.writeBytes(original); repo.clear()
            assertEquals(1, manager.restore(Uri.fromFile(file), "strong-test-password".toCharArray()))
            val restored = repo.all().single()
            assertNotEquals(path, restored.imagePath)
            assertArrayEquals(image, repo.image(restored.imagePath!!))
        } finally { file.delete(); repo.clear() }
    }
    @Test fun oldEncryptedRowsReadWithoutMigrationOrStyleReset() = runBlocking {
        val repo = CardRepository(context, TestVault()); repo.clear()
        val card = WalletCard(bank = "旧版银行", lastFour = "0123", template = 5, primary = 0xFF382917, notes = "原备注")
        val json = CardJson.encode(card)
        listOf("showNotes", "showWordmark", "logoPosition", "amexLayout").forEach(json::remove)
        val row = EncryptedCard(card.id, TestVault().encrypt(json.toString().toByteArray(), "card:${card.id}"))
        val database = androidx.room.Room.databaseBuilder(context, WalletDatabase::class.java, "wallet.db").build()
        try {
            database.cards().upsert(listOf(row))
            assertEquals(card, repo.all().single())
            assertArrayEquals(row.payload, database.cards().all().single().payload)
        } finally { database.close(); repo.clear() }
    }
    @Test fun legacyBackupRestoresAndNewDesignBackupRoundTrips() = runBlocking {
        val repo = CardRepository(context, TestVault()); repo.clear()
        val card = WalletCard(bank = "旧备份银行", lastFour = "4567", template = 3, notes = "保留原备注")
        val oldJson = CardJson.encode(card)
        listOf("showNotes", "showWordmark", "logoPosition", "amexLayout").forEach(oldJson::remove)
        val manifest = org.json.JSONObject().put("version", 1).put("cards", org.json.JSONArray().put(oldJson)).toString().toByteArray()
        val file = File(context.cacheDir, "compatibility.mycard")
        val manager = BackupManager(context, repo)
        try {
            file.outputStream().use { raw -> java.io.DataOutputStream(Crypto.backupSink(raw, "compat-password".toCharArray())).use {
                it.writeInt(manifest.size); it.write(manifest); it.writeInt(0)
            } }
            assertEquals(1, manager.restore(Uri.fromFile(file), "compat-password".toCharArray()))
            assertEquals(card, repo.all().single())
            val new = card.copy(template = 15, showNotes = true, showWordmark = false, logoPosition = LogoPosition.TOP_RIGHT, amexLayout = true)
            repo.save(new)
            manager.export(Uri.fromFile(file), "compat-password".toCharArray())
            repo.clear()
            assertEquals(1, manager.restore(Uri.fromFile(file), "compat-password".toCharArray()))
            assertEquals(new.copy(updatedAt = repo.all().single().updatedAt), repo.all().single())
        } finally { file.delete(); repo.clear() }
    }
}
