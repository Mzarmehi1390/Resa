package com.roman.zemzeme.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeReplayGuardTest {

    @Test
    fun rejectsDuplicateWhileExchangeIsInFlight() {
        val guard = HandshakeReplayGuard()

        assertTrue(guard.tryBegin("exchange-a"))
        assertFalse(guard.tryBegin("exchange-a"))
    }

    @Test
    fun failedExchangeCanBeRetried() {
        val guard = HandshakeReplayGuard()

        assertTrue(guard.tryBegin("exchange-a"))
        guard.abort("exchange-a")

        assertTrue(guard.tryBegin("exchange-a"))
    }

    @Test
    fun completedExchangeCannotBeProcessedAgain() {
        val guard = HandshakeReplayGuard()

        assertTrue(guard.tryBegin("exchange-a"))
        guard.markProcessed("exchange-a")

        assertFalse(guard.tryBegin("exchange-a"))
        assertEquals(1, guard.processedCount())
    }

    @Test
    fun trimmingRemovesOldestCompletedEntriesOnly() {
        val guard = HandshakeReplayGuard()

        assertTrue(guard.tryBegin("one"))
        guard.markProcessed("one")
        assertTrue(guard.tryBegin("two"))
        guard.markProcessed("two")
        assertTrue(guard.tryBegin("in-flight"))

        assertEquals(1, guard.trimTo(1))
        assertFalse(guard.tryBegin("two"))
        assertTrue(guard.tryBegin("one"))
        assertFalse(guard.tryBegin("in-flight"))
        assertEquals(listOf("two"), guard.processedSnapshot(10))
    }

    @Test
    fun clearResetsCompletedAndInFlightEntries() {
        val guard = HandshakeReplayGuard()

        assertTrue(guard.tryBegin("completed"))
        guard.markProcessed("completed")
        assertTrue(guard.tryBegin("pending"))

        guard.clear()

        assertEquals(0, guard.processedCount())
        assertTrue(guard.tryBegin("completed"))
        assertTrue(guard.tryBegin("pending"))
    }
}
