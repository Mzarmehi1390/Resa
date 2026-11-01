package com.roman.zemzeme.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChannelKeyCommitmentStoreCodecTest {

    @Test
    fun roundTripsMultipleCommitments() {
        val entries = linkedMapOf(
            "#general" to "a".repeat(64),
            "#school" to "b".repeat(64)
        )

        assertEquals(entries, ChannelKeyCommitmentStoreCodec.decode(
            ChannelKeyCommitmentStoreCodec.encode(entries)
        ))
    }

    @Test
    fun rejectsDuplicateChannelEntries() {
        val encoded = ChannelKeyCommitmentStoreCodec.encode(
            mapOf("#general" to "a".repeat(64))
        )
        val duplicate = encoded + encoded.copyOfRange(7, encoded.size)

        assertThrows(IllegalArgumentException::class.java) {
            ChannelKeyCommitmentStoreCodec.decode(duplicate)
        }
    }

    @Test
    fun rejectsMalformedCommitment() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelKeyCommitmentStoreCodec.encode(mapOf("#general" to "not-a-commitment"))
        }
    }

    @Test
    fun rejectsTrailingBytes() {
        val encoded = ChannelKeyCommitmentStoreCodec.encode(emptyMap()) + byteArrayOf(0x01)

        assertThrows(IllegalArgumentException::class.java) {
            ChannelKeyCommitmentStoreCodec.decode(encoded)
        }
    }
}
