package com.roman.zemzeme.nostr

/**
 * Bounded, relay-aware queue for Nostr events awaiting relay acknowledgements.
 *
 * An event remains pending for a relay until that relay responds with OK.
 * In-flight tracking prevents duplicate sends on the same live connection;
 * disconnecting a relay releases its events so they can be retried after reconnect.
 */
internal class NostrPendingEventQueue(
    private val capacity: Int
) {
    private data class Entry(
        val event: NostrEvent,
        val pendingRelays: MutableSet<String>,
        val inFlightRelays: MutableSet<String> = mutableSetOf()
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    init {
        require(capacity > 0) { "Queue capacity must be positive" }
    }

    fun enqueue(event: NostrEvent, relayUrls: Collection<String>): Boolean {
        if (event.id.isBlank()) return false
        val targets = relayUrls.filter { it.isNotBlank() }.toSet()
        if (targets.isEmpty()) return false

        synchronized(lock) {
            val existing = entries[event.id]
            if (existing != null) {
                existing.pendingRelays.addAll(targets)
                return true
            }
            if (entries.size >= capacity) return false

            entries[event.id] = Entry(event, targets.toMutableSet())
            return true
        }
    }

    fun claim(eventId: String, relayUrl: String): NostrEvent? = synchronized(lock) {
        val entry = entries[eventId] ?: return@synchronized null
        if (relayUrl !in entry.pendingRelays || !entry.inFlightRelays.add(relayUrl)) {
            return@synchronized null
        }
        entry.event
    }

    fun claimForRelay(relayUrl: String): List<NostrEvent> = synchronized(lock) {
        entries.values.mapNotNull { entry ->
            if (relayUrl in entry.pendingRelays && entry.inFlightRelays.add(relayUrl)) {
                entry.event
            } else {
                null
            }
        }
    }

    fun release(eventId: String, relayUrl: String) {
        synchronized(lock) {
            entries[eventId]?.inFlightRelays?.remove(relayUrl)
        }
    }

    fun releaseRelay(relayUrl: String) {
        synchronized(lock) {
            entries.values.forEach { it.inFlightRelays.remove(relayUrl) }
        }
    }

    fun acknowledge(eventId: String, relayUrl: String): Boolean = synchronized(lock) {
        val entry = entries[eventId] ?: return@synchronized false
        entry.inFlightRelays.remove(relayUrl)
        val removed = entry.pendingRelays.remove(relayUrl)
        if (entry.pendingRelays.isEmpty()) {
            entries.remove(eventId)
        }
        removed
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }
}
