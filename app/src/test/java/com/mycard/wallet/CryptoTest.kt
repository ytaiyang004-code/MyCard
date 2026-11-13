package com.mycard.wallet

import com.mycard.wallet.security.Crypto
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

class CryptoTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    @Test fun authenticatedEncryptionRoundTripsChineseData() {
        val plain = "私人卡包 · 1234".toByteArray()
        val encrypted = Crypto.seal(plain, key, "record-1".toByteArray())
        assertArrayEquals(plain, Crypto.open(encrypted, key, "record-1".toByteArray()))
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("1234"))
    }
    @Test fun randomNoncesPreventIdenticalCiphertext() {
        val bytes = ByteArray(64)
        assertFalse(Crypto.seal(bytes, key, byteArrayOf()).contentEquals(Crypto.seal(bytes, key, byteArrayOf())))
    }
    @Test(expected = AEADBadTagException::class) fun differentRecordIdentityRejectsCiphertext() {
        Crypto.open(Crypto.seal(byteArrayOf(1), key, "one".toByteArray()), key, "two".toByteArray())
    }
    @Test(expected = AEADBadTagException::class) fun tamperedCiphertextRejected() {
        val data = Crypto.seal(ByteArray(64), key, byteArrayOf())
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        Crypto.open(data, key, byteArrayOf())
    }
    @Test fun portableBackupRoundTripWithoutDeviceKey() {
        val plain = "portable backup".toByteArray()
        val encrypted = Crypto.backup(plain, "test passphrase".toCharArray())
        assertArrayEquals(plain, Crypto.restore(encrypted, "test passphrase".toCharArray()))
    }
    @Test(expected = AEADBadTagException::class) fun wrongBackupPasswordRejected() {
        val data = Crypto.backup(ByteArray(100), "correct-password".toCharArray())
        Crypto.restore(data, "incorrect-password".toCharArray())
    }
    @Test(expected = IllegalArgumentException::class) fun invalidBackupHeaderRejectedBeforeKdf() {
        Crypto.restore(ByteArray(100), "password".toCharArray())
    }
    @Test(expected = IllegalArgumentException::class) fun shortPasswordRejected() {
        Crypto.backup(byteArrayOf(1), "123".toCharArray())
    }
    @Test(expected = java.io.EOFException::class) fun truncatedBackupRejected() {
        Crypto.restore(ByteArray(20), "password".toCharArray())
    }
    @Test fun multipleChunksRoundTrip() {
        val plain = ByteArray(180_000) { (it % 251).toByte() }
        assertArrayEquals(plain, Crypto.restore(Crypto.backup(plain, "passphrase".toCharArray()), "passphrase".toCharArray()))
    }
    @Test(expected = java.io.EOFException::class) fun removingEndMarkerIsRejected() {
        val bytes = Crypto.backup(byteArrayOf(1, 2, 3), "passphrase".toCharArray())
        Crypto.restore(bytes.copyOf(bytes.size - 32), "passphrase".toCharArray())
    }
    @Test(expected = IllegalArgumentException::class) fun appendedGarbageIsRejected() {
        val bytes = Crypto.backup(byteArrayOf(1, 2, 3), "passphrase".toCharArray())
        Crypto.restore(bytes + byteArrayOf(1), "passphrase".toCharArray())
    }
    @Test(expected = IllegalArgumentException::class) fun unboundedWorkFactorRejected() {
        val data = Crypto.backup(byteArrayOf(1), "passphrase".toCharArray())
        data[8] = 127
        Crypto.restore(data, "passphrase".toCharArray())
    }
}
