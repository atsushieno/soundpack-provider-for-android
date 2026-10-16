package org.androidaudioplugin.soundpack.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class PrefetcherTest {
    private class Opened(val path: String) : Closeable {
        @Volatile var closed = false
        override fun close() { closed = true }
    }

    private class Opener(private val delayMillis: Long = 0) {
        val opened: MutableList<Opened> = Collections.synchronizedList(mutableListOf())
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        fun open(path: String): Opened {
            maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                Thread.sleep(delayMillis)
                if (path.startsWith("bad"))
                    throw IOException(path)
                return Opened(path).also { opened += it }
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun opensAheadInParallelAndHandsOutEachFileOnce() {
        val opener = Opener(delayMillis = 50)
        val paths = (1..32).map { "s/$it.flac" }
        Prefetcher(paths, parallelism = 8, window = 64, idleMillis = 10_000, open = opener::open).use { prefetcher ->
            val started = System.nanoTime()
            val taken = paths.map { prefetcher.take(it)!! }
            val millis = (System.nanoTime() - started) / 1_000_000
            assertEquals(paths, taken.map { it.path })
            assertEquals(32, opener.opened.size)
            assertEquals(8, opener.maxRunning.get())
            assertTrue("one at a time would take 1600 ms, took $millis", millis < 800)
        }
    }

    @Test
    fun theWindowLimitsWhatIsOpenedButNotTaken() {
        val opener = Opener()
        Prefetcher((1..10).map { "$it" }, parallelism = 4, window = 3, idleMillis = 10_000, open = opener::open).use { prefetcher ->
            waitUntil { opener.opened.size == 3 }
            Thread.sleep(100)
            assertEquals(3, opener.opened.size)
            prefetcher.take("1")!!
            waitUntil { opener.opened.size == 4 }
            // Opened 4 at a time, they finish in any order.
            assertEquals(setOf("1", "2", "3", "4"), opener.opened.map { it.path }.toSet())
            assertEquals(4, opener.opened.size)
        }
    }

    @Test
    fun queuedUnknownAndFailedPathsAreLeftToTheCaller() {
        val opener = Opener()
        Prefetcher(listOf("a", "bad", "c"), parallelism = 1, window = 2, idleMillis = 10_000, open = opener::open).use { prefetcher ->
            assertNull("c is queued behind the window", prefetcher.take("c"))
            assertNull(prefetcher.take("unknown"))
            assertNull("opening it failed", prefetcher.take("bad"))
            assertSame(opener.opened.single { it.path == "a" }, prefetcher.take("a"))
            Thread.sleep(100)
            assertFalse("c was handed to the caller, not opened", opener.opened.any { it.path == "c" })
        }
    }

    @Test
    fun closeAndIdleTimeoutCloseWhatWasNotTaken() {
        val opener = Opener()
        val prefetcher = Prefetcher(listOf("a", "b"), parallelism = 2, window = 2, idleMillis = 10_000, open = opener::open)
        val a = prefetcher.take("a")!!
        waitUntil { opener.opened.size == 2 }
        prefetcher.close()
        assertFalse("taken files belong to the caller", a.closed)
        assertTrue(opener.opened.single { it.path == "b" }.closed)
        assertNull(prefetcher.take("b"))

        val idleOpener = Opener()
        val idle = Prefetcher(listOf("x", "y"), parallelism = 2, window = 2, idleMillis = 100, open = idleOpener::open)
        waitUntil { idleOpener.opened.size == 2 && idleOpener.opened.all { it.closed } }
        assertTrue(idleOpener.opened.all { it.closed })
        assertNull(idle.take("x"))
    }
}
