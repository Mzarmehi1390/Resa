package com.roman.zemzeme.mesh

/**
 * Tracks completed and currently-processing handshake payloads.
 *
 * A reservation is committed only after Noise accepts the message. Failed
 * processing releases the reservation, allowing a legitimate retry.
 */
internal class HandshakeReplayGuard {
    private val lock = Any()
    private val processed = LinkedHashSet<String>()
    private val inFlight = HashSet<String>()

    fun tryBegin(key: String): Boolean = synchronized(lock) {
        if (key in processed || key in inFlight) {
            false
        } else {
            inFlight.add(key)
        }
    }

    fun markProcessed(key: String) = synchronized(lock) {
        inFlight.remove(key)
        processed.add(key)
    }

    fun abort(key: String) = synchronized(lock) {
        inFlight.remove(key)
    }

    fun processedCount(): Int = synchronized(lock) { processed.size }

    fun processedSnapshot(limit: Int): List<String> = synchronized(lock) {
        processed.take(limit)
    }

    fun trimTo(maxEntries: Int): Int = synchronized(lock) {
        require(maxEntries >= 0)
        val excess = (processed.size - maxEntries).coerceAtLeast(0)
        repeat(excess) {
            val oldest = processed.firstOrNull() ?: return@repeat
            processed.remove(oldest)
        }
        excess
    }

    fun clear() = synchronized(lock) {
        processed.clear()
        inFlight.clear()
    }
}
