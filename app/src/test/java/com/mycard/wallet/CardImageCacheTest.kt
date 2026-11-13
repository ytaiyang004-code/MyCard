package com.mycard.wallet

import com.mycard.wallet.images.CardImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The artwork cache is a plain class, so its budget, keys and de-duplication are tested directly. */
class CardImageCacheTest {
    private val key = "11111111-2222-3333-4444-555555555555.bin"

    @After fun tearDown() = CardImageCache.clear()

    @Test fun repeatedLoadsDecryptOnceAndKeepOneInstance() = runBlocking {
        val calls = AtomicInteger(0)
        val first = CardImageCache.load(key) { calls.incrementAndGet(); ByteArray(64) { 7 } }
        val second = CardImageCache.load(key) { calls.incrementAndGet(); ByteArray(64) { 7 } }
        assertEquals(1, calls.get())
        assertSame(first, second)
        assertEquals(64, CardImageCache.sizeInBytes())
    }

    @Test fun concurrentLoadsOfTheSamePictureShareOneDecryption() = runBlocking {
        val calls = AtomicInteger(0)
        val results = (1..8).map {
            async(Dispatchers.Default) { CardImageCache.load(key) { calls.incrementAndGet(); delay(50); ByteArray(32) { 9 } } }
        }.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(results.all { it === results.first() })
    }

    @Test fun aNewSaveIsANewKeySoNoStalePictureCanBeServed() = runBlocking {
        val old = CardImageCache.load("aaaaaaaa-0000-0000-0000-000000000000.bin") { ByteArray(16) { 1 } }
        val edited = CardImageCache.load("bbbbbbbb-0000-0000-0000-000000000000.bin") { ByteArray(16) { 2 } }
        assertEquals(1, old[0].toInt())
        assertEquals(2, edited[0].toInt())
        CardImageCache.evict("aaaaaaaa-0000-0000-0000-000000000000.bin")
        assertNull(CardImageCache.peek("aaaaaaaa-0000-0000-0000-000000000000.bin"))
        assertNotNull(CardImageCache.peek("bbbbbbbb-0000-0000-0000-000000000000.bin"))
    }

    @Test fun theCacheEvictsToStayInsideItsBudgetAndSkipsOversizeEntries() = runBlocking {
        repeat(20) { index ->
            CardImageCache.load("cccccccc-0000-0000-0000-%012d.bin".format(index)) { ByteArray(4 * 1024 * 1024) }
        }
        assertTrue("entries ${CardImageCache.count()}", CardImageCache.count() <= 12)
        assertTrue("bytes ${CardImageCache.sizeInBytes()}", CardImageCache.sizeInBytes() <= 24 * 1024 * 1024)
        CardImageCache.load("dddddddd-0000-0000-0000-000000000000.bin") { ByteArray(25 * 1024 * 1024) }
        assertNull(CardImageCache.peek("dddddddd-0000-0000-0000-000000000000.bin"))
    }

    @Test fun aFailedLoadIsNotCachedAndCanBeRetried() = runBlocking {
        val attempts = AtomicInteger(0)
        runCatching { CardImageCache.load(key) { attempts.incrementAndGet(); error("corrupt file") } }
        assertEquals(1, attempts.get())
        assertNull(CardImageCache.peek(key))
        val recovered = CardImageCache.load(key) { attempts.incrementAndGet(); ByteArray(8) }
        assertEquals(2, attempts.get())
        assertEquals(8, recovered.size)
    }
}
