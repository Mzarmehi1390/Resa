package com.roman.zemzeme.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChannelMessageEnvelopeTest {

    private fun sample() = ChannelMessageEnvelope(
        channelId = "#general",
        messageId = "msg_01-abc",
        ciphertext = ByteArray(28) { it.toByte() }
    )

    @Test
    fun encodeDecodeRoundTripPreservesAllFields() {
        val original = sample()
        val decoded = ChannelMessageEnvelope.decode(ChannelMessageEnvelope.encode(original))

        assertNotNull(decoded)
        assertEquals(original.channelId, decoded?.channelId)
        assertEquals(original.messageId, decoded?.messageId)
        assertArrayEquals(original.ciphertext, decoded?.ciphertext)
    }

    @Test
    fun magicPrefixDetectsReservedEnvelopeFramesOnly() {
        val encoded = ChannelMessageEnvelope.encode(sample())

        assertEquals(true, ChannelMessageEnvelope.hasMagicPrefix(encoded))
        assertEquals(true, ChannelMessageEnvelope.hasMagicPrefix(encoded.copyOf(8)))
        assertEquals(false, ChannelMessageEnvelope.hasMagicPrefix("RSCT".toByteArray()))
        assertEquals(false, ChannelMessageEnvelope.hasMagicPrefix(byteArrayOf(0x52, 0x53, 0x43)))
        assertEquals(false, ChannelMessageEnvelope.hasMagicPrefix(ByteArray(0)))
    }

    @Test
    fun decoderRejectsTruncatedAndTrailingData() {
        val encoded = ChannelMessageEnvelope.encode(sample())

        assertNull(ChannelMessageEnvelope.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(ChannelMessageEnvelope.decode(encoded + byteArrayOf(0x00)))
    }

    @Test
    fun decoderRejectsUnknownVersionAndBadMagic() {
        val encoded = ChannelMessageEnvelope.encode(sample())

        val unknownVersion = encoded.copyOf().also { it[4] = 2 }
        val badMagic = encoded.copyOf().also { it[0] = 0 }

        assertNull(ChannelMessageEnvelope.decode(unknownVersion))
        assertNull(ChannelMessageEnvelope.decode(badMagic))
    }

    @Test
    fun encoderRejectsInvalidMessageIdAndTooShortCiphertext() {
        val invalidId = sample().copy(messageId = "contains spaces")
        val shortCiphertext = sample().copy(ciphertext = ByteArray(27))

        try {
            ChannelMessageEnvelope.encode(invalidId)
            throw AssertionError("Expected invalid message ID to be rejected")
        } catch (_: IllegalArgumentException) {
        }

        try {
            ChannelMessageEnvelope.encode(shortCiphertext)
            throw AssertionError("Expected short ciphertext to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun decoderRejectsImpossibleLengthFields() {
        val encoded = ChannelMessageEnvelope.encode(sample())
        encoded[5] = 0x7f
        encoded[6] = 0x7f

        assertNull(ChannelMessageEnvelope.decode(encoded))
    }
}
