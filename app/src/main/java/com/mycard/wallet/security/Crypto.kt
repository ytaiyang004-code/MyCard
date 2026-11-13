package com.mycard.wallet.security

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Standard JCA AES-256-GCM and PBKDF2-HMAC-SHA256; no custom cryptographic primitives. */
object Crypto {
    private val random = SecureRandom()
    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    fun seal(plain: ByteArray, key: SecretKey, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }
    fun open(encrypted: ByteArray, key: SecretKey, aad: ByteArray): ByteArray {
        require(encrypted.size >= 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            updateAAD(aad)
            doFinal(encrypted, 12, encrypted.size - 12)
        }
    }
    private fun derive(password: CharArray, salt: ByteArray): SecretKey {
        require(password.size in 8..128)
        val spec = PBEKeySpec(password, salt, ROUNDS, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }
    private val magic = "MYCARD02".toByteArray(Charsets.US_ASCII)
    const val MAX_BACKUP_BYTES = 256 * 1024 * 1024
    private const val ROUNDS = 600_000
    private const val CHUNK = 64 * 1024
    private fun aad(header: ByteArray, index: Int) = header + ByteBuffer.allocate(4).putInt(index).array()

    /**
     * Stream format: header (magic, rounds, salt), bounded independently authenticated chunks,
     * then an authenticated empty terminator. AAD binds each chunk to header + monotonic index.
     * Missing/reordered/trailing chunks are rejected. Restore commits only after the terminator.
     */
    fun backupSink(output: OutputStream, password: CharArray): OutputStream {
        val salt = randomBytes(16)
        val header = magic + ByteBuffer.allocate(4).putInt(ROUNDS).array() + salt
        val key = derive(password, salt)
        return object : OutputStream() {
            val out = DataOutputStream(output)
            val buffer = ByteArray(CHUNK)
            var used = 0; var index = 0; var total = 0L; var closed = false
            init { out.write(header) }
            fun emit(bytes: ByteArray) {
                val encrypted = seal(bytes, key, aad(header, index++))
                out.writeInt(encrypted.size); out.write(encrypted)
            }
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                check(!closed)
                require(off >= 0 && len >= 0 && off <= bytes.size - len)
                require(total + len <= MAX_BACKUP_BYTES)
                total += len
                var offset = off; var remaining = len
                while (remaining > 0) {
                    val n = minOf(CHUNK - used, remaining)
                    bytes.copyInto(buffer, used, offset, offset + n)
                    used += n; offset += n; remaining -= n
                    if (used == CHUNK) { emit(buffer); buffer.fill(0); used = 0 }
                }
            }
            override fun flush() { out.flush() }
            override fun close() {
                if (closed) return
                closed = true
                try {
                    if (used > 0) {
                        val last = buffer.copyOf(used)
                        try { emit(last) } finally { last.fill(0) }
                    }
                    emit(byteArrayOf()) // Authentication of the end marker prevents truncation.
                    out.flush()
                } finally { buffer.fill(0); out.close() }
            }
        }
    }
    fun backupSource(input: InputStream, password: CharArray,
                     onFailure: (RestoreStage) -> Unit = {}): InputStream {
        // Observe the original exception without wrapping it or changing authentication behavior.
        fun <T> observed(stage: RestoreStage, block: () -> T): T = try { block() }
        catch (e: Exception) { onFailure(stage); throw e }
        val source = DataInputStream(input)
        val header = observed(RestoreStage.HEADER) {
            ByteArray(28).also {
                source.readFully(it)
                require(it.copyOfRange(0, 8).contentEquals(magic))
                require(ByteBuffer.wrap(it, 8, 4).int == ROUNDS)
            }
        }
        val key = observed(RestoreStage.AUTH) { derive(password, header.copyOfRange(12, 28)) }
        return object : InputStream() {
            var buffer = byteArrayOf(); var offset = 0; var index = 0; var total = 0L; var ended = false
            fun ensure(): Boolean = observed(RestoreStage.AUTH) {
                if (offset < buffer.size) return@observed true
                if (ended) return@observed false
                buffer.fill(0)
                require(index <= MAX_BACKUP_BYTES / CHUNK + 1)
                val length = source.readInt()
                require(length in 28..(CHUNK + 28))
                val encrypted = ByteArray(length).also(source::readFully)
                buffer = open(encrypted, key, aad(header, index++))
                offset = 0; total += buffer.size
                require(total <= MAX_BACKUP_BYTES)
                if (buffer.isEmpty()) {
                    require(source.read() == -1) { "Trailing backup data" }
                    ended = true; return@observed false
                }
                true
            }
            override fun read(): Int = if (ensure()) buffer[offset++].toInt() and 255 else -1
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                require(off >= 0 && len >= 0 && off <= bytes.size - len)
                if (len == 0) return 0
                if (!ensure()) return -1
                val n = minOf(len, buffer.size - offset)
                buffer.copyInto(bytes, off, offset, offset + n); offset += n
                return n
            }
            override fun close() { buffer.fill(0); source.close() }
        }
    }
    // Convenient in-memory entry points for small values and format tests. Backups use the streams.
    fun backup(plain: ByteArray, password: CharArray): ByteArray = ByteArrayOutputStream().let { out ->
        backupSink(out, password).use { it.write(plain) }; out.toByteArray()
    }
    fun restore(bytes: ByteArray, password: CharArray): ByteArray =
        backupSource(ByteArrayInputStream(bytes), password).use { it.readBytes() }
}
