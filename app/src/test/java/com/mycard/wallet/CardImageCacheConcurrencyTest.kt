package com.mycard.wallet

import com.mycard.wallet.images.CardImageCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Synthetic bytes and scheduler-controlled gates only; no files, Android data or timing sleeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardImageCacheConcurrencyTest {
    private val key = "cache-concurrency-test"

    @After fun tearDown() = CardImageCache.clear()

    @Test fun clearPreventsAnInFlightLoadFromRefillingTheCache() = runTest {
        val release = CompletableDeferred<Unit>()
        val loading = async { CardImageCache.load(key) { release.await(); byteArrayOf(1) } }
        runCurrent()
        CardImageCache.clear()
        release.complete(Unit)
        loading.join()
        assertNull("clear must invalidate results that were already loading", CardImageCache.peek(key))
        assertEquals(0, CardImageCache.count())
        assertEquals(0, CardImageCache.sizeInBytes())
        assertArrayEquals("existing readers may finish without refilling the cache", byteArrayOf(1), loading.await())
    }

    @Test fun evictPreventsAnInFlightLoadFromRefillingOnlyThatKey() = runTest {
        val other = CardImageCache.load("other") { byteArrayOf(8, 9) }
        val release = CompletableDeferred<Unit>()
        val loading = async { CardImageCache.load(key) { release.await(); byteArrayOf(1) } }
        runCurrent()
        CardImageCache.evict(key)
        release.complete(Unit)
        loading.join()
        assertNull("evict must invalidate results that were already loading", CardImageCache.peek(key))
        assertSame(other, CardImageCache.peek("other"))
        assertEquals(2, CardImageCache.sizeInBytes())
        assertArrayEquals(byteArrayOf(1), loading.await())
    }

    @Test fun clearingDetachesOldWorkAndLateCompletionCannotRemoveItsReplacement() =
        invalidationAllowsIndependentReplacement { CardImageCache.clear() }

    @Test fun evictingDetachesOldWorkAndLateCompletionCannotRemoveItsReplacement() =
        invalidationAllowsIndependentReplacement { CardImageCache.evict(key) }

    private fun invalidationAllowsIndependentReplacement(invalidate: () -> Unit) = runTest {
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val oldBytes = byteArrayOf(1)
        val newBytes = byteArrayOf(2, 3)
        val old = async {
            runCatching {
                CardImageCache.load(key) {
                    // Model blocking decryption that cannot be interrupted immediately by cancellation.
                    withContext(NonCancellable) { oldRelease.await() }
                    oldBytes
                }
            }
        }
        runCurrent()
        invalidate()
        var newLoads = 0
        var extraLoads = 0
        val replacement = async {
            CardImageCache.load(key) { newLoads++; newRelease.await(); newBytes }
        }
        try {
            runCurrent()
            oldRelease.complete(Unit)
            runCurrent()
            val follower = async {
                CardImageCache.load(key) { extraLoads++; byteArrayOf(99) }
            }
            runCurrent()
            newRelease.complete(Unit)
            old.join()
            assertSame(newBytes, replacement.await())
            assertSame(newBytes, follower.await())
            assertEquals("new calls must not join invalidated work", 1, newLoads)
            assertEquals("old completion must not remove the new in-flight entry", 0, extraLoads)
            assertSame(newBytes, CardImageCache.peek(key))
        } finally {
            oldRelease.complete(Unit)
            newRelease.complete(Unit)
        }
    }

    @Test fun cancellingTheFirstCallerDoesNotCancelOtherWaiters() = runTest {
        val release = CompletableDeferred<Unit>()
        val expected = byteArrayOf(4, 5)
        var loads = 0
        val first = async { CardImageCache.load(key) { loads++; release.await(); expected } }
        runCurrent()
        val second = async { runCatching { CardImageCache.load(key) { error("must share the load") } } }
        runCurrent()
        first.cancel()
        runCurrent()
        release.complete(Unit)
        first.join()
        val result = second.await()
        assertTrue("another caller must survive the initiator's cancellation: $result", result.isSuccess)
        assertSame(expected, result.getOrThrow())
        assertSame(expected, CardImageCache.peek(key))
        assertEquals(1, loads)
    }

    @Test fun cancellingAFollowerDoesNotCancelTheRemainingCaller() = runTest {
        val release = CompletableDeferred<Unit>()
        val expected = byteArrayOf(6)
        val first = async { CardImageCache.load(key) { release.await(); expected } }
        runCurrent()
        val second = async { CardImageCache.load(key) { error("must share the load") } }
        runCurrent()
        second.cancel()
        runCurrent()
        release.complete(Unit)
        second.join()
        assertSame(expected, first.await())
    }

    @Test fun cancellingTheLastWaiterStopsTheLoadAndAllowsRetry() = runTest {
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val first = async {
            CardImageCache.load(key) {
                try { release.await(); byteArrayOf(1) } finally { stopped.complete(Unit) }
            }
        }
        runCurrent()
        first.cancel()
        runCurrent()
        try {
            assertTrue("unobserved loading work must be cancelled", stopped.isCompleted)
            assertNull(CardImageCache.peek(key))
            val expected = byteArrayOf(7)
            assertSame(expected, CardImageCache.load(key) { expected })
        } finally {
            release.complete(Unit)
            first.join()
        }
    }

    @Test fun cancellationBeforeTheLoaderStartsDoesNotPoisonRetry() = runTest {
        var abandonedLoads = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            CardImageCache.load(key) { abandonedLoads++; byteArrayOf(1) }
        }
        first.cancel()
        runCurrent()
        first.join()
        assertEquals(0, abandonedLoads)
        val expected = byteArrayOf(8)
        assertSame(expected, CardImageCache.load(key) { expected })
    }

    @Test fun newCallersDoNotJoinWorkWhoseOnlyReaderHasAlreadyCancelled() = runTest {
        val release = CompletableDeferred<Unit>()
        var abandonedLoads = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            CardImageCache.load(key) { abandonedLoads++; release.await(); byteArrayOf(1) }
        }
        first.cancel() // Its finally is queued, so the registration may still exist.
        val expected = byteArrayOf(15)
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            CardImageCache.load(key) { expected }
        }
        try {
            runCurrent()
            assertTrue("a fresh caller must not wait for abandoned work", replacement.isCompleted)
            assertSame(expected, replacement.await())
            assertEquals(0, abandonedLoads)
        } finally { release.complete(Unit); first.join() }
    }

    @Test fun cancelledReadersCannotPublishBeforeTheirFinallyRuns() = runTest {
        lateinit var reader: kotlinx.coroutines.Deferred<ByteArray>
        reader = async(start = CoroutineStart.LAZY) {
            CardImageCache.load(key) { reader.cancel(); byteArrayOf(1) }
        }
        reader.start()
        runCurrent()
        reader.join()
        assertNull("all readers cancelled immediately before publication", CardImageCache.peek(key))
        val expected = byteArrayOf(16)
        assertSame(expected, CardImageCache.load(key) { expected })
    }

    @Test fun detachedWorkIsCancelledWhenItsLastWaiterLeaves() = runTest {
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val loading = async {
            CardImageCache.load(key) {
                try { release.await(); byteArrayOf(1) } finally { stopped.complete(Unit) }
            }
        }
        runCurrent()
        CardImageCache.clear()
        loading.cancel()
        runCurrent()
        try {
            assertTrue("detached work must not outlive all of its readers", stopped.isCompleted)
            assertNull(CardImageCache.peek(key))
        } finally { release.complete(Unit); loading.join() }
    }

    @Test fun loaderRunsOutsideTheMonitorAndKeepsSynchronousCacheReadsAvailable() = runTest {
        val expected = byteArrayOf(14)
        assertSame(expected, CardImageCache.load(key) {
            assertFalse("the loader must not execute under the cache monitor", Thread.holdsLock(CardImageCache))
            assertNull(CardImageCache.peek(key))
            assertEquals(0, CardImageCache.count())
            expected
        })
    }

    @Test fun aSharedFailureReachesAllWaitersAndAllowsRetry() = runTest {
        val release = CompletableDeferred<Unit>()
        val failure = IllegalStateException("synthetic decryption failure")
        val first = async { runCatching { CardImageCache.load(key) { release.await(); throw failure } } }
        runCurrent()
        val second = async { runCatching { CardImageCache.load(key) { error("must share the failure") } } }
        runCurrent()
        release.complete(Unit)
        listOf(first.await(), second.await()).forEach { result ->
            // Coroutine stack-trace recovery may copy an exception; its identity is not the contract.
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(failure.message, result.exceptionOrNull()?.message)
        }
        assertNull(CardImageCache.peek(key))
        val expected = byteArrayOf(9)
        assertSame(expected, CardImageCache.load(key) { expected })
    }

    @Test fun loaderCancellationIsNotCachedAndAllowsRetry() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = async {
            runCatching { CardImageCache.load(key) { release.await(); throw CancellationException("synthetic cancellation") } }
        }
        runCurrent()
        val second = async { runCatching { CardImageCache.load(key) { error("must share cancellation") } } }
        runCurrent()
        release.complete(Unit)
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertTrue(second.await().exceptionOrNull() is CancellationException)
        assertNull(CardImageCache.peek(key))
        val expected = byteArrayOf(10)
        assertSame(expected, CardImageCache.load(key) { expected })
    }

    @Test fun queuedCallersReuseACompletedLoadWithoutStartingAnotherOne() = runTest {
        val expected = byteArrayOf(11)
        var loads = 0
        val callers = (1..20).map { async { CardImageCache.load(key) { loads++; expected } } }
        callers.forEach { assertSame(expected, it.await()) }
        assertEquals(1, loads)
    }

    @Test fun invalidationDoesNotWipeBytesAlreadyBorrowedByReaders() = runTest {
        val expected = byteArrayOf(12, 13)
        val borrowed = CardImageCache.load(key) { expected }
        CardImageCache.evict(key)
        assertArrayEquals(byteArrayOf(12, 13), borrowed)
        CardImageCache.load(key) { borrowed }
        CardImageCache.clear()
        assertArrayEquals(byteArrayOf(12, 13), borrowed)
    }
}
