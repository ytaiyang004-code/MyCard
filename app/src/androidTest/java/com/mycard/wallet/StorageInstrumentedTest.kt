package com.mycard.wallet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mycard.wallet.data.*
import com.mycard.wallet.images.*
import com.mycard.wallet.security.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun roomPersistenceUpdateDeleteAndEncryptedFiles() = runBlocking {
        val repo = CardRepository(context)
        repo.clear()
        val card = WalletCard(bank = "SensitiveBankMarker", lastFour = "6789", notes = "SensitiveNoteMarker")
        repo.save(card)
        assertEquals(card.bank, repo.all().single().bank)
        repo.save(card.copy(nickname = "更新成功"))
        assertEquals("更新成功", repo.all().single().nickname)
        val second = CardRepository(context)
        assertEquals("6789", second.all().single().lastFour)
        context.getDatabasePath("wallet.db").parentFile!!.listFiles()!!.filter { it.name.startsWith("wallet.db") }.forEach {
            val content = it.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(content.contains("SensitiveBankMarker"))
            assertFalse(content.contains("SensitiveNoteMarker"))
        }
        repo.delete(card.id)
        assertTrue(repo.all().isEmpty())
    }
    @Test fun layoutAndThemePersist() = runBlocking {
        val prefs = Preferences(context)
        prefs.boolean("stacked", false)
        prefs.text("theme", ThemeMode.LIGHT.name)
        assertFalse(Preferences(context).flow.first().stacked)
        assertEquals(ThemeMode.LIGHT, Preferences(context).flow.first().theme)
        prefs.boolean("stacked", true); prefs.text("theme", ThemeMode.DARK.name)
    }
    @Test fun redactionBurnsPixelsAndOutputsStandardRatio() = runBlocking {
        val original = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val output = ImageProcessor.render(original, CropTransform(), listOf(Mask(.2f, .2f, .5f, .5f)))
        val decoded = BitmapFactory.decodeByteArray(output, 0, output.size)
        assertEquals(1586, decoded.width); assertEquals(1000, decoded.height)
        assertEquals(Color.BLACK, decoded.getPixel(500, 300))
        assertEquals(Color.RED, decoded.getPixel(100, 100))
        val repo = CardRepository(context)
        val name = repo.writeImage(output)
        assertArrayEquals(output, repo.image(name))
        val encryptedFile = File(context.noBackupFilesDir, "card_art/$name").readBytes()
        assertFalse(encryptedFile.take(8).toByteArray().contentEquals(output.take(8).toByteArray()))
        repo.discardImage(name)
        decoded.recycle(); original.recycle()
    }
    @Test fun backupRestoreAndWrongPasswordLeaveCurrentDataUntouched() = runBlocking {
        val repo = CardRepository(context)
        repo.clear()
        val original = WalletCard(bank = "备份银行", lastFour = "0012", template = 4)
        repo.save(original)
        val file = File(context.cacheDir, "test-backup.mycard")
        try {
            val manager = BackupManager(context, repo)
            manager.export(Uri.fromFile(file), "long-test-passphrase".toCharArray())
            repo.save(original.copy(bank = "当前银行"))
            try { manager.restore(Uri.fromFile(file), "wrong-test-passphrase".toCharArray()); fail() } catch (_: javax.crypto.AEADBadTagException) {}
            assertEquals("当前银行", repo.all().single().bank)
            repo.clear()
            assertEquals(1, manager.restore(Uri.fromFile(file), "long-test-passphrase".toCharArray()))
            assertEquals("备份银行", repo.all().single().bank)
        } finally { file.delete(); repo.clear() }
    }
    @Test fun reorderSurvivesReopening() = runBlocking {
        val repo = CardRepository(context)
        repo.clear()
        val a = WalletCard(bank = "甲", lastFour = "1111", position = 0)
        val b = WalletCard(bank = "乙", lastFour = "2222", position = 1)
        repo.save(a); repo.save(b); repo.reorder(listOf(b.id, a.id))
        assertEquals(listOf(b.id, a.id), CardRules.select(CardRepository(context).all(), "", CardFilter.ALL, SortOrder.CUSTOM).map { it.id })
        repo.clear()
    }
    @Test fun manifestHasNoNetworkOrBroadStoragePermissions() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions?.toList().orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        assertFalse(permissions.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(permissions.contains("android.permission.READ_MEDIA_IMAGES"))
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
