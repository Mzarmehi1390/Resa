package com.roman.zemzeme.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseReplayWindowTest {

    @Test
    fun acceptsAndRecordsInitialZeroNonce() {
        val window = ByteArray(NoiseReplayWindow.WINDOW_BYTES)
        assertTrue(NoiseReplayWindow.isValidNonce(0L, 0L, window))

        val (highest, updated) = NoiseReplayWindow.markNonceAsSeen(0L, 0L, window)
        assertEquals(0L, highest)
        assertFalse(NoiseReplayWindow.isValidNonce(0L, highest, updated))
    }

    @Test
    fun supportsOutOfOrderNoncesInsideReplayWindow() {
        val first = NoiseReplayWindow.markNonceAsSeen(
            10L, 0L, ByteArray(NoiseReplayWindow.WINDOW_BYTES)
        )
        assertTrue(NoiseReplayWindow.isValidNonce(8L, first.first, first.second))

        val older = NoiseReplayWindow.markNonceAsSeen(8L, first.first, first.second)
        assertFalse(NoiseReplayWindow.isValidNonce(8L, older.first, older.second))
        assertTrue(NoiseReplayWindow.isValidNonce(9L, older.first, older.second))
    }

    @Test
    fun rejectsNonceOutsideReplayWindow() {
        val highest = 2_000L
        val window = ByteArray(NoiseReplayWindow.WINDOW_BYTES)
        assertFalse(NoiseReplayWindow.isValidNonce(976L, highest, window))
        assertTrue(NoiseReplayWindow.isValidNonce(977L, highest, window))
    }

    @Test
    fun largeUnsignedNonceJumpClearsWindowWithoutSignedOverflow() {
        val initial = NoiseReplayWindow.markNonceAsSeen(
            5L, 0L, ByteArray(NoiseReplayWindow.WINDOW_BYTES)
        )
        val maxNonce = UInt.MAX_VALUE.toLong()
        assertTrue(NoiseReplayWindow.isValidNonce(maxNonce, initial.first, initial.second))

        val updated = NoiseReplayWindow.markNonceAsSeen(
            maxNonce, initial.first, initial.second
        )
        assertEquals(maxNonce, updated.first)
        assertFalse(NoiseReplayWindow.isValidNonce(maxNonce, updated.first, updated.second))
        assertTrue(NoiseReplayWindow.isValidNonce(maxNonce - 1L, updated.first, updated.second))
        assertFalse(NoiseReplayWindow.isValidNonce(5L, updated.first, updated.second))
    }

    @Test
    fun rejectsNonceOutsideUnsigned32BitRange() {
        val window = ByteArray(NoiseReplayWindow.WINDOW_BYTES)

        try {
            NoiseReplayWindow.isValidNonce(-1L, 0L, window)
            throw AssertionError("Negative nonce should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        try {
            NoiseReplayWindow.markNonceAsSeen(UInt.MAX_VALUE.toLong() + 1L, 0L, window)
            throw AssertionError("Nonce above UInt.MAX_VALUE should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
