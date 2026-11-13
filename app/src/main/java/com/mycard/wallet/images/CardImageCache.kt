package com.mycard.wallet.images

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/**
 * Process-wide cache for decrypted card artwork.
 *
 * Key: the stored file name. A save always writes a new UUID file, so an edited card can never be
 * served its previous bytes and no separate version counter is needed.
 *
 * Bound: a byte budget and an entry cap, both enforced on insert, so a wallet of large artwork cannot
 * grow the cache without limit.
 *
 * Concurrency: one short monitor protects both entries and loading-task registration. Loading runs
 * outside it on the caller's dispatcher, independently of any one caller's Job. A task is cancelled
 * when its last waiter leaves; success and failure both remove its registration.
 *
 * Invalidation: clear/evict detach old tasks. Existing waiters may finish reading their shared bytes,
 * but old tasks cannot cache their result or remove a replacement task. New callers start fresh.
 *
 * Security boundary: only decrypted bytes live here, and nothing is ever written to disk. The cache
 * is dropped when the wallet locks, when its data is replaced or cleared, and evicted per entry when
 * an image is discarded. This is a privacy measure, not an erasure guarantee: the JVM and the platform
 * may keep copies of the same bytes in heap pages, the graphics driver or the decoder for an unknown
 * time, so this class never claims that sensitive data is wiped.
 */
object CardImageCache {
    private const val MAX_BYTES = 24 * 1024 * 1024
    private const val MAX_ENTRIES = 12

    private val entries = LinkedHashMap<String, ByteArray>(0, .75f, true)
    private val inFlight = mutableMapOf<String, Flight>()
    private var bytes = 0

    private class Flight {
        lateinit var task: Deferred<ByteArray>
        val waiters = mutableSetOf<Waiter>()
    }

    private class Waiter(val job: Job?)

    /** Shared read-only bytes for [key], or null. Never touches the file system. */
    @Synchronized
    fun peek(key: String): ByteArray? = entries[key]

    /** Bytes currently held, for diagnostics and tests. */
    @Synchronized
    fun sizeInBytes(): Int = bytes

    @Synchronized
    fun count(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
        inFlight.clear()
    }

    @Synchronized
    fun evict(key: String) {
        entries.remove(key)?.let { bytes -= it.size }
        inFlight.remove(key)
    }

    @Synchronized
    private fun store(key: String, value: ByteArray) {
        if (value.size > MAX_BYTES) return
        entries.remove(key)?.let { bytes -= it.size }
        entries[key] = value
        bytes += value.size
        val iterator = entries.entries.iterator()
        while ((bytes > MAX_BYTES || entries.size > MAX_ENTRIES) && iterator.hasNext()) {
            val oldest = iterator.next()
            iterator.remove()
            bytes -= oldest.value.size
        }
    }

    /**
     * Shared read-only bytes for [key]. Cache lookup and task registration are atomic: callers share
     * one load until it completes or is invalidated. The loader keeps the caller's dispatcher, but
     * its lifetime is owned by all waiters together. No caller may modify or wipe the returned bytes.
     */
    suspend fun load(key: String, loader: suspend (String) -> ByteArray): ByteArray {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        val waiter = Waiter(callerContext[Job])
        val flight = synchronized(this) {
            entries[key]?.let { return it }
            val pending = inFlight[key]?.takeIf {
                !it.task.isCompleted && !it.task.isCancelled && hasActiveWaiters(it)
            } ?: newFlight(key, loader, callerContext).also { fresh ->
                inFlight[key] = fresh
                // Completion handlers also run if a lazy task is cancelled before its body starts.
                fresh.task.invokeOnCompletion {
                    synchronized(this@CardImageCache) {
                        if (inFlight[key] === fresh) inFlight.remove(key)
                    }
                }
            }
            pending.waiters.add(waiter)
            pending
        }
        try { return flight.task.await() }
        finally {
            val abandoned = synchronized(this) {
                flight.waiters.remove(waiter)
                if (flight.waiters.isEmpty()) {
                    if (inFlight[key] === flight) inFlight.remove(key)
                    true
                } else false
            }
            // Cancellation is outside the monitor and also applies to detached, invalidated tasks.
            if (abandoned) flight.task.cancel()
        }
    }

    /** Called under the monitor; LAZY prevents the loader from running in the critical section. */
    private fun newFlight(key: String, loader: suspend (String) -> ByteArray,
                          callerContext: CoroutineContext): Flight {
        val flight = Flight()
        // A root Deferred is owned by the waiters, not the first caller. Preserve its dispatcher
        // without creating a process-wide scope or an extra parent Job that could outlive the task.
        val loadingScope = object : CoroutineScope {
            override val coroutineContext = callerContext.minusKey(Job)
        }
        flight.task = loadingScope.async(start = CoroutineStart.LAZY) {
            synchronized(this@CardImageCache) {
                // A cancelled caller's finally may still be queued behind this lazy task.
                if (!hasActiveWaiters(flight)) throw CancellationException("No active image readers")
            }
            val value = loader(key)
            coroutineContext.ensureActive()
            synchronized(this@CardImageCache) {
                // Object identity is the generation token, including across repeated invalidations.
                if (inFlight[key] === flight && hasActiveWaiters(flight)) store(key, value)
            }
            value
        }
        return flight
    }

    /** Called under the monitor; a caller without a Job cannot signal cancellation. */
    private fun hasActiveWaiters(flight: Flight) = flight.waiters.any { it.job?.isActive != false }
}
