package com.roman.zemzeme.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Date

class ZemzemeMessagePayloadTest {

    @Test
    fun binaryPayloadRoundTripPreservesChannelAndMentions() {
        val original = ZemzemeMessage(
            id = "message-001",
            sender = "peer-a",
            content = "Hello from #general",
            timestamp = Date(1_700_000_000_000L),
            senderPeerID = "a1b2c3d4",
            mentions = listOf("peer-b", "peer-c"),
            channel = "#general"
        )

        val payload = original.toBinaryPayload()
        assertNotNull(payload)

        val decoded = ZemzemeMessage.fromBinaryPayload(payload!!)
        assertNotNull(decoded)
        assertEquals(original.id, decoded?.id)
        assertEquals(original.sender, decoded?.sender)
        assertEquals(original.content, decoded?.content)
        assertEquals(original.timestamp, decoded?.timestamp)
        assertEquals(original.senderPeerID, decoded?.senderPeerID)
        assertEquals(original.mentions, decoded?.mentions)
        assertEquals(original.channel, decoded?.channel)
    }

    @Test
    fun encryptedPayloadRoundTripPreservesOpaqueCiphertext() {
        val ciphertext = byteArrayOf(1, 2, 3, 4, 5)
        val original = ZemzemeMessage(
            id = "encrypted-001",
            sender = "peer-a",
            content = "",
            timestamp = Date(1_700_000_000_000L),
            channel = "#private",
            encryptedContent = ciphertext,
            isEncrypted = true
        )

        val payload = original.toBinaryPayload()
        assertNotNull(payload)

        val decoded = ZemzemeMessage.fromBinaryPayload(payload!!)
        assertNotNull(decoded)
        assertEquals(true, decoded?.isEncrypted)
        assertArrayEquals(ciphertext, decoded?.encryptedContent)
        assertEquals("#private", decoded?.channel)
    }

    @Test
    fun legacyUtf8PayloadIsNotMistakenForBinaryMessage() {
        val decoded = ZemzemeMessage.fromBinaryPayload("hello".toByteArray(Charsets.UTF_8))
        assertEquals(null, decoded)
    }
    @Test
    fun decoderRejectsTruncatedOptionalChannelField() {
        val original = ZemzemeMessage(
            sender = "peer-a",
            content = "hello",
            timestamp = Date(1_700_000_000_000L),
            channel = "#general"
        )
        val encoded = original.toBinaryPayload()!!
        // The channel is the final optional field: remove its final byte.
        assertEquals(null, ZemzemeMessage.fromBinaryPayload(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun decoderRejectsTrailingBytes() {
        val original = ZemzemeMessage(
            sender = "peer-a",
            content = "hello",
            timestamp = Date(1_700_000_000_000L)
        )
        val encoded = original.toBinaryPayload()!!
        assertEquals(null, ZemzemeMessage.fromBinaryPayload(encoded + byteArrayOf(0x00)))
    }

    @Test
    fun decoderRejectsTruncatedMentionEntry() {
        val original = ZemzemeMessage(
            sender = "peer-a",
            content = "hello",
            timestamp = Date(1_700_000_000_000L),
            mentions = listOf("peer-b")
        )
        val encoded = original.toBinaryPayload()!!
        // Remove the final byte from the mention value.
        assertEquals(null, ZemzemeMessage.fromBinaryPayload(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun encoderRejectsFieldsThatWouldPreviouslyBeTruncated() {
        val oversizedId = ZemzemeMessage(
            id = "x".repeat(256),
            sender = "peer-a",
            content = "hello",
            timestamp = Date(1_700_000_000_000L)
        )
        assertEquals(null, oversizedId.toBinaryPayload())

        val oversizedUtf8Sender = ZemzemeMessage(
            sender = "م".repeat(128),
            content = "hello",
            timestamp = Date(1_700_000_000_000L)
        )
        assertEquals(null, oversizedUtf8Sender.toBinaryPayload())

        val tooManyMentions = ZemzemeMessage(
            sender = "peer-a",
            content = "hello",
            timestamp = Date(1_700_000_000_000L),
            mentions = (0..255).map { "peer-$it" }
        )
        assertEquals(null, tooManyMentions.toBinaryPayload())
    }

    @Test
    fun encoderRejectsPayloadLargerThanWireLimit() {
        val oversized = ZemzemeMessage(
            sender = "peer-a",
            content = "x".repeat(4090),
            timestamp = Date(1_700_000_000_000L),
            channel = "#general"
        )
        assertEquals(null, oversized.toBinaryPayload())
    }

    @Test
    fun encoderRejectsEncryptedMessageWithoutCiphertext() {
        val invalid = ZemzemeMessage(
            sender = "peer-a",
            content = "",
            timestamp = Date(1_700_000_000_000L),
            isEncrypted = true,
            encryptedContent = null
        )
        assertEquals(null, invalid.toBinaryPayload())
    }

    @Test
    fun decoderRejectsPayloadAboveWireLimit() {
        assertEquals(null, ZemzemeMessage.fromBinaryPayload(ByteArray(4097)))
    }

    @Test
    fun decoderRejectsMalformedUtf8InContent() {
        val original = ZemzemeMessage(
            id = "id",
            sender = "peer-a",
            content = "hello",
            timestamp = Date(1_700_000_000_000L)
        )
        val encoded = original.toBinaryPayload()!!
        val contentOffset = 1 + 8 + 1 + original.id.toByteArray(Charsets.UTF_8).size +
            1 + original.sender.toByteArray(Charsets.UTF_8).size + 2
        encoded[contentOffset] = 0xC3.toByte()
        assertEquals(null, ZemzemeMessage.fromBinaryPayload(encoded))
    }

}
