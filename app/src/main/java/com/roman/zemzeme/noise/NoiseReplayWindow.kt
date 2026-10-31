package com.roman.zemzeme.noise

/**
 * Pure replay-window bookkeeping for the 32-bit transport nonce.
 *
 * Nonces are represented as unsigned values in a Long so the full UInt range
 * can be handled without signed Int overflow.
 */
internal object NoiseReplayWindow {
    const val WINDOW_SIZE = 1024
    const val WINDOW_BYTES = WINDOW_SIZE / 8

    fun isValidNonce(
        receivedNonce: Long,
        highestReceivedNonce: Long,
        replayWindow: ByteArray
    ): Boolean {
        require(receivedNonce in 0L..UInt.MAX_VALUE.toLong())
        require(highestReceivedNonce in 0L..UInt.MAX_VALUE.toLong())
        require(replayWindow.size == WINDOW_BYTES)

        if (receivedNonce + WINDOW_SIZE <= highestReceivedNonce) {
            return false
        }

        if (receivedNonce > highestReceivedNonce) {
            return true
        }

        val offset = (highestReceivedNonce - receivedNonce).toInt()
        val byteIndex = offset / 8
        val bitIndex = offset % 8
        return (replayWindow[byteIndex].toInt() and (1 shl bitIndex)) == 0
    }

    fun markNonceAsSeen(
        receivedNonce: Long,
        highestReceivedNonce: Long,
        replayWindow: ByteArray
    ): Pair<Long, ByteArray> {
        require(receivedNonce in 0L..UInt.MAX_VALUE.toLong())
        require(highestReceivedNonce in 0L..UInt.MAX_VALUE.toLong())
        require(replayWindow.size == WINDOW_BYTES)

        var newHighestReceivedNonce = highestReceivedNonce
        val newReplayWindow = replayWindow.copyOf()

        if (receivedNonce > highestReceivedNonce) {
            // Keep the difference in Long until after the bounds check. Casting
            // UInt.MAX_VALUE to Int first would wrap it to -1 and corrupt indexing.
            val shiftLong = receivedNonce - highestReceivedNonce

            if (shiftLong >= WINDOW_SIZE.toLong()) {
                newReplayWindow.fill(0)
            } else {
                val shift = shiftLong.toInt()
                for (i in (WINDOW_BYTES - 1) downTo 0) {
                    val sourceByteIndex = i - shift / 8
                    var newByte = 0

                    if (sourceByteIndex >= 0) {
                        newByte = (newReplayWindow[sourceByteIndex].toInt() and 0xFF) ushr (shift % 8)
                        if (sourceByteIndex > 0 && shift % 8 != 0) {
                            newByte = newByte or (
                                (newReplayWindow[sourceByteIndex - 1].toInt() and 0xFF) shl
                                    (8 - shift % 8)
                                )
                        }
                    }

                    newReplayWindow[i] = (newByte and 0xFF).toByte()
                }
            }

            newHighestReceivedNonce = receivedNonce
            newReplayWindow[0] = (newReplayWindow[0].toInt() or 1).toByte()
        } else {
            val offset = (highestReceivedNonce - receivedNonce).toInt()
            val byteIndex = offset / 8
            val bitIndex = offset % 8
            newReplayWindow[byteIndex] = (newReplayWindow[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }

        return newHighestReceivedNonce to newReplayWindow
    }
}
