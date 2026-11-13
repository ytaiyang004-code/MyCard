package com.mycard.wallet

import com.mycard.wallet.data.CardRepository
import com.mycard.wallet.images.CardImageCache
import com.mycard.wallet.security.Crypto
import com.mycard.wallet.security.VaultCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** The repository must decrypt a picture once, keep the bytes in memory, and leave the file ciphered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardImageCacheRepositoryTest {
    private class CountingVault : VaultCipher {
        private val key: SecretKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val decrypts = AtomicInteger(0)
        override fun encrypt(bytes: ByteArray, context: String) = Crypto.seal(bytes, key, context.toByteArray())
        override fun decrypt(bytes: ByteArray, context: String): ByteArray {
            decrypts.incrementAndGet()
            return Crypto.open(bytes, key, context.toByteArray())
        }
    }

    @After fun tearDown() = CardImageCache.clear()

    @Test fun artworkIsDecryptedOnceAndTheFileStaysCiphertext() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val vault = CountingVault()
        val repository = CardRepository(app, vault)
        repository.clear()
        val plain = ByteArray(2048) { (it % 251).toByte() }
        val name = repository.writeImage(plain)

        val first = repository.image(name)
        assertArrayEquals(plain, first)
        assertEquals(1, vault.decrypts.get())
        assertSame("a second read must not decrypt again", first, repository.image(name))
        assertEquals(1, vault.decrypts.get())

        val stored = File(app.noBackupFilesDir, "card_art/$name").readBytes()
        assertFalse("the stored file must not contain the plaintext", stored.contentEquals(plain))
        repository.discardImage(name)
        assertNull(CardImageCache.peek(name))
        repository.clear()
    }

    @Test fun lockingTheWalletDropsDecryptedArtwork() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val vm = WalletViewModel(app, CardRepository(app, TestVault()))
        val name = "eeeeeeee-0000-0000-0000-000000000000.bin"
        CardImageCache.load(name) { ByteArray(128) { 5 } }
        assertNotNull(CardImageCache.peek(name))
        vm.onHostStopped(changingConfigurations = false)
        assertNull(CardImageCache.peek(name))
        assertFalse(vm.unlocked.value)
        // A configuration change keeps the session and must not throw the cache away.
        CardImageCache.load(name) { ByteArray(128) { 5 } }
        vm.onHostStopped(changingConfigurations = true)
        assertNotNull(CardImageCache.peek(name))
    }
}
