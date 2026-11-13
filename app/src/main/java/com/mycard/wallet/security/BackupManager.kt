package com.mycard.wallet.security

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.mycard.wallet.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*

enum class RestoreWarning { STREAM_CLOSE_FAILED, INTERRUPTED_AFTER_COMMIT }
data class RestoreResult(val count: Int, val warning: RestoreWarning? = null, val diagnostic: RestoreDiagnostic? = null)

/** Bounded streaming archive. No plaintext backup or original image ever lands on disk. */
class BackupManager(private val context: Context, private val repository: CardRepository) {
    suspend fun export(uri: Uri, password: CharArray) {
        try {
            withContext(Dispatchers.IO) {
                val snapshot = repository.readAll()
                // A backup that silently omitted unreadable records would later look complete.
                if (!snapshot.complete) throw IncompleteWalletException(snapshot.damaged.size)
                val cards = snapshot.cards
                require(cards.size <= 5000)
                val manifest = JSONObject().put("version", 2).put("cards", JSONArray().apply {
                    cards.forEach { put(CardJson.encode(it)) }
                }).toString().toByteArray()
                require(manifest.size <= 8 * 1024 * 1024)
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write backup")
                stream.use { raw ->
                    DataOutputStream(Crypto.backupSink(raw, password).also { password.fill('\u0000') }).use { out ->
                        currentCoroutineContext().ensureActive()
                        out.writeInt(manifest.size); out.write(manifest)
                        val images = cards.mapNotNull { it.imagePath }.distinct()
                        out.writeInt(images.size)
                        for (name in images) {
                            currentCoroutineContext().ensureActive()
                            // Own the temporary bytes: image() returns shared, read-only cache data.
                            val bytes = repository.image(name).copyOf()
                            try { out.writeUTF(name); out.writeInt(bytes.size); out.write(bytes) }
                            finally { bytes.fill(0) }
                        }
                    }
                }
            }
        } finally { password.fill('\u0000') }
    }

    /** Compatibility entry point; callers displaying results should use [restoreWithResult]. */
    suspend fun restore(uri: Uri, password: CharArray): Int = restoreWithResult(uri, password).count

    suspend fun restoreWithResult(uri: Uri, password: CharArray,
                                  onDiagnostic: (RestoreDiagnostic) -> Unit = {}): RestoreResult {
        // A clear or another committed restore invalidates this entire staging operation.
        val generation = repository.dataGeneration
        val createdFiles = mutableListOf<String>()
        var committedCount: Int? = null
        var stage = RestoreStage.FILE_OPEN
        var streamFailureStage: RestoreStage? = null
        try {
            return withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(uri) ?: error("Cannot read backup")
                val count = stream.use { raw ->
                    stage = RestoreStage.HEADER
                    DataInputStream(Crypto.backupSource(raw, password) {
                        if (streamFailureStage == null) streamFailureStage = it
                    }.also { password.fill('\u0000') }).use { input ->
                        stage = RestoreStage.MANIFEST
                        val manifestLength = input.readInt()
                        require(manifestLength in 1..(8 * 1024 * 1024))
                        val manifest = ByteArray(manifestLength).also(input::readFully)
                        val root = try { JSONObject(manifest.toString(Charsets.UTF_8)) } finally { manifest.fill(0) }
                        stage = RestoreStage.VALIDATION
                        require(root.getInt("version") in 1..2)
                        val array = root.getJSONArray("cards")
                        require(array.length() <= 5000)
                        val cards = (0 until array.length()).map { CardJson.decode(array.getJSONObject(it)) }
                        require(cards.map { it.id }.toSet().size == cards.size)
                        val names = cards.mapNotNull { it.imagePath }.toSet()
                        val count = input.readInt()
                        require(count == names.size && count <= 5000)
                        val replacements = mutableMapOf<String, String>()
                        repeat(count) {
                            stage = RestoreStage.VALIDATION
                            currentCoroutineContext().ensureActive()
                            val name = input.readUTF()
                            require(name in names && name !in replacements)
                            val size = input.readInt()
                            require(size in 1..(6 * 1024 * 1024))
                            stage = RestoreStage.IMAGE
                            val image = ByteArray(size).also(input::readFully)
                            try {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                                require(bounds.outWidth == 1586 && bounds.outHeight == 1000 && bounds.outMimeType == "image/png")
                                val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: error("Invalid image")
                                bitmap.recycle()
                                stage = RestoreStage.LOCAL_WRITE
                                val path = repository.writeImage(image, generation)
                                createdFiles += path; replacements[name] = path
                            } finally { image.fill(0) }
                        }
                        stage = RestoreStage.VALIDATION
                        // Forces end-marker authentication before any database changes.
                        require(input.read() == -1)
                        // Cancellation before the commit boundary must not enter NonCancellable.
                        currentCoroutineContext().ensureActive()
                        stage = RestoreStage.DB_COMMIT
                        repository.merge(cards.map { it.copy(imagePath = it.imagePath?.let(replacements::getValue)) }, generation) {
                            // Set while still inside the repository's non-cancellable commit boundary.
                            committedCount = cards.size
                        }
                        stage = RestoreStage.STREAM_CLOSE
                        cards.size
                    }
                }
                RestoreResult(count)
            }
        } catch (e: Exception) {
            val diagnostic = RestoreDiagnostics.record(streamFailureStage ?: stage, e, committedCount != null)
            onDiagnostic(diagnostic)
            // A close failure or prompt cancellation on returning from IO cannot undo the commit.
            val count = committedCount ?: throw e
            return RestoreResult(count, if (e is CancellationException) RestoreWarning.INTERRUPTED_AFTER_COMMIT
                else RestoreWarning.STREAM_CLOSE_FAILED, diagnostic)
        } finally {
            password.fill('\u0000')
            if (committedCount == null) withContext(NonCancellable + Dispatchers.IO) {
                createdFiles.forEach {
                    try {
                        if (!repository.discardIfUnused(it))
                            RestoreDiagnostics.record(RestoreStage.CLEANUP,
                                IOException("Staged artwork was not released"), false)
                    } catch (e: Exception) {
                        // Keep the primary failure and continue best-effort cleanup of other files.
                        RestoreDiagnostics.record(RestoreStage.CLEANUP, e, false)
                    }
                }
            }
        }
    }
}
