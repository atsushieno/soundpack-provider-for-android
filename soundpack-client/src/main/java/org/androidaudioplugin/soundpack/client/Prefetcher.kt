package org.androidaudioplugin.soundpack.client

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Opens [paths] in order on [parallelism] threads, for an engine that asks for them one at a
 * time ([PackSession.prefetch]). At most [window] opened files wait to be taken, so a path is
 * opened only as earlier ones are taken. [take] waits for a path that is being opened, and
 * returns null for anything else, so the caller opens it itself. Whatever is not taken
 * within [idleMillis] of the last [take] is closed: an engine that stops asking leaves nothing
 * open for long. */
internal class Prefetcher<T : Closeable>(
    paths: List<String>,
    parallelism: Int,
    private val window: Int,
    private val idleMillis: Long,
    private val open: (String) -> T,
) : Closeable {
    private val lock = Any()
    private val queue = ArrayDeque(paths.distinct())
    private val pending = HashMap<String, Future<T>>()
    private val executor = Executors.newFixedThreadPool(parallelism) { Thread(it, "soundpack-prefetch").apply { isDaemon = true } }
    private val timer = ScheduledThreadPoolExecutor(1) { Thread(it, "soundpack-prefetch-idle").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private var idle: ScheduledFuture<*>? = null
    private var closed = false

    init {
        synchronized(lock) {
            fill()
            rearmIdle()
        }
    }

    /** The opened [path], or null when it was not prefetched (or opening it failed). */
    fun take(path: String): T? {
        val future = synchronized(lock) {
            if (closed)
                return null
            rearmIdle()
            // Not started yet: the caller opening it now is as fast as waiting for a turn.
            if (queue.remove(path))
                return null
            pending.remove(path).also { fill() }
        } ?: return null
        return try {
            future.get()
        } catch (_: ExecutionException) {
            null
        } catch (_: CancellationException) {
            null
        }
    }

    /** Stops opening, and closes whatever was opened and not taken. */
    override fun close() {
        val left = synchronized(lock) {
            if (closed)
                return
            closed = true
            queue.clear()
            idle?.cancel(false)
            pending.values.toList().also { pending.clear() }
        }
        for (future in left) {
            if (!future.cancel(false))
                runCatching { future.get() }.getOrNull()?.let { runCatching { it.close() } }
        }
        executor.shutdown()
        timer.shutdown()
    }

    private fun fill() {
        while (!closed && pending.size < window && queue.isNotEmpty()) {
            val path = queue.removeFirst()
            pending[path] = executor.submit(Callable { open(path) })
        }
    }

    private fun rearmIdle() {
        idle?.cancel(false)
        idle = timer.schedule({ close() }, idleMillis, TimeUnit.MILLISECONDS)
    }
}
