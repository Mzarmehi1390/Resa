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
}
