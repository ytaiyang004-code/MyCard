package com.mycard.wallet.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Raised when the device key is absent. No replacement key is ever generated implicitly. */
class VaultKeyUnavailableException : GeneralSecurityException("Device key is unavailable")

interface VaultCipher {
    fun encrypt(bytes: ByteArray, context: String): ByteArray
    fun decrypt(bytes: ByteArray, context: String): ByteArray
    /**
     * Read-only lookup of the key this cipher needs.
     *
     * Implementations must only look the key up: never create or replace one, and never report a
     * failed check as "present". The default keeps existing test ciphers source compatible; the
     * repository still treats authentication failures as record damage only when other rows decrypt.
     */
    fun hasKey(): Boolean = true
    /**
     * Explicit first-run initialisation, the only path allowed to create a key.
     *
     * Must never replace an existing key and must return true only when a usable key exists
     * afterwards. The default reports the existing state: a cipher that cannot create keys simply
     * cannot initialise one.
     */
    fun initializeKey(): Boolean = hasKey()
}

class DeviceVault : VaultCipher {
    /**
     * Resolved on every operation and deliberately never cached: a cached handle would keep sealing
     * and opening data with the old key after the alias is gone, so a warm instance would silently
     * behave differently from a cold one. Reading is the only thing this class may do with an alias.
     */
    private fun key(): SecretKey =
        (store().getKey(ALIAS, null) as? SecretKey) ?: throw VaultKeyUnavailableException()

    private fun store(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun encrypt(bytes: ByteArray, context: String) = Crypto.seal(bytes, key(), context.toByteArray())
    override fun decrypt(bytes: ByteArray, context: String) = Crypto.open(bytes, key(), context.toByteArray())

    /** Read-only lookup. Reporting a missing alias lets the repository fail as a whole. */
    override fun hasKey(): Boolean = store().containsAlias(ALIAS)

    /**
     * Creates the alias only when it is absent. The check is repeated under a process-wide monitor
     * so two concurrent first-run initialisations cannot race each other into a replacement, and a
     * key that appears in between always wins.
     */
    override fun initializeKey(): Boolean {
        if (hasKey()) return true
        synchronized(CREATION) {
            if (hasKey()) return true
            KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
            return hasKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "mycard.private.v1"
        /** Process-wide: only one first-run creation at a time, regardless of instance count. */
        private val CREATION = Any()
    }
}
