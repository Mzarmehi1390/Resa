package com.roman.zemzeme.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrPendingEventQueueTest {

    private fun event(id: String) = NostrEvent(
        id = id,
        pubkey = "pubkey",
        createdAt = 1,
        kind = NostrKind.TEXT_NOTE,
        tags = emptyList(),
        content = "message"
    )

    @Test
    fun eventIsRetainedUntilEveryTargetRelayAcknowledges() {
        val queue = NostrPendingEventQueue(capacity = 4)
        val item = event("event-1")

        assertTrue(queue.enqueue(item, listOf("relay-a", "relay-b")))
        assertEquals(listOf(item), queue.claimForRelay("relay-a"))
        assertTrue(queue.acknowledge("event-1", "relay-a"))
        assertEquals(1, queue.size())

        assertEquals(listOf(item), queue.claimForRelay("relay-b"))
        assertTrue(queue.acknowledge("event-1", "relay-b"))
        assertEquals(0, queue.size())
    }

    @Test
    fun claimedEventIsNotSentTwiceUntilReleasedOrDisconnected() {
        val queue = NostrPendingEventQueue(capacity = 4)
        queue.enqueue(event("event-1"), listOf("relay-a"))

        assertNotNull(queue.claim("event-1", "relay-a"))
        assertNull(queue.claim("event-1", "relay-a"))

        queue.releaseRelay("relay-a")
        assertNotNull(queue.claim("event-1", "relay-a"))
    }

    @Test
    fun duplicateEventMergesRelayTargetsInsteadOfGrowingQueue() {
        val queue = NostrPendingEventQueue(capacity = 1)
        val item = event("event-1")

        assertTrue(queue.enqueue(item, listOf("relay-a")))
        assertTrue(queue.enqueue(item, listOf("relay-b")))

        assertEquals(1, queue.size())
        assertEquals(listOf(item), queue.claimForRelay("relay-a"))
        assertEquals(listOf(item), queue.claimForRelay("relay-b"))
    }

    @Test
    fun queueRejectsNewEventsWhenCapacityIsReached() {
        val queue = NostrPendingEventQueue(capacity = 1)

        assertTrue(queue.enqueue(event("event-1"), listOf("relay-a")))
        assertFalse(queue.enqueue(event("event-2"), listOf("relay-a")))
        assertEquals(1, queue.size())
    }

    @Test
    fun queueRejectsEventsWithoutAnIdOrRelayTarget() {
        val queue = NostrPendingEventQueue(capacity = 2)

        assertFalse(queue.enqueue(event(""), listOf("relay-a")))
        assertFalse(queue.enqueue(event("event-1"), emptyList()))
        assertEquals(0, queue.size())
    }
}
