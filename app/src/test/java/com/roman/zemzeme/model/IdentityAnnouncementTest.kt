package com.roman.zemzeme.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityAnnouncementTest {

    private val noiseKey = ByteArray(32) { (it + 1).toByte() }
    private val signingKey = ByteArray(32) { (it + 33).toByte() }

    @Test
    fun `encode and decode preserve valid identity announcement`() {
        val original = IdentityAnnouncement("Resa peer", noiseKey, signingKey)
        val encoded = original.encode()

        assertNotNull(encoded)
        val decoded = IdentityAnnouncement.decode(encoded!!)

        assertNotNull(decoded)
        assertEquals(original.nickname, decoded!!.nickname)
        assertArrayEquals(noiseKey, decoded.noisePublicKey)
        assertArrayEquals(signingKey, decoded.signingPublicKey)
    }

    @Test
    fun `encode rejects keys with incorrect lengths`() {
        assertNull(IdentityAnnouncement("peer", ByteArray(31), signingKey).encode())
        assertNull(IdentityAnnouncement("peer", noiseKey, ByteArray(33)).encode())
    }

    @Test
    fun `encode rejects empty nickname`() {
        assertNull(IdentityAnnouncement("", noiseKey, signingKey).encode())
    }

    @Test
    fun `decode rejects duplicate known TLV fields`() {
        val valid = validPayload()
        val duplicateNickname = tlv(0x01, byteArrayOf('x'.code.toByte()))
        assertNull(IdentityAnnouncement.decode(valid + duplicateNickname))
    }

    @Test
    fun `decode rejects truncated TLV header`() {
        assertNull(IdentityAnnouncement.decode(validPayload() + byteArrayOf(0x7f)))
    }

    @Test
    fun `decode rejects malformed UTF8 nickname`() {
        val payload = tlv(0x01, byteArrayOf(0xC3.toByte(), 0x28)) +
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey)

        assertNull(IdentityAnnouncement.decode(payload))
    }

    @Test
    fun `decode rejects incorrectly sized identity keys`() {
        val payload = tlv(0x01, "peer".toByteArray()) +
            tlv(0x02, ByteArray(31)) +
            tlv(0x03, signingKey)

        assertNull(IdentityAnnouncement.decode(payload))
    }

    @Test
    fun `decode skips unknown TLV for forward compatibility`() {
        val payload = validPayload() + tlv(0x7f, byteArrayOf(1, 2, 3))
        val decoded = IdentityAnnouncement.decode(payload)

        assertNotNull(decoded)
        assertEquals("peer", decoded!!.nickname)
    }

    private fun validPayload(): ByteArray =
        tlv(0x01, "peer".toByteArray()) +
            tlv(0x02, noiseKey) +
            tlv(0x03, signingKey)

    private fun tlv(type: Int, value: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), value.size.toByte()) + value
}
