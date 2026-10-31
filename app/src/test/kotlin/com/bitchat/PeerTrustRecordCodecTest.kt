package com.bitchat

import com.roman.zemzeme.mesh.PeerTrustRecord
import com.roman.zemzeme.mesh.PeerTrustRecordCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PeerTrustRecordCodecTest {
    private fun record(
        peerID: String = "0123456789abcdef",
        nickname: String = "peer",
        seed: Int = 1
    ) = PeerTrustRecord(
        peerID = peerID,
        nickname = nickname,
        noisePublicKey = ByteArray(32) { (it + seed).toByte() },
        signingPublicKey = ByteArray(32) { (it + seed + 1).toByte() }
    )

    @Test
    fun roundTripPreservesAllTrustRecords() {
        val expected = listOf(record(), record("fedcba9876543210", "دستگاه دوم", 9))
        val actual = PeerTrustRecordCodec.decode(PeerTrustRecordCodec.encode(expected))

        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) ->
            assertEquals(left.peerID, right.peerID)
            assertEquals(left.nickname, right.nickname)
            assertArrayEquals(left.noisePublicKey, right.noisePublicKey)
            assertArrayEquals(left.signingPublicKey, right.signingPublicKey)
        }
    }

    @Test
    fun rejectsDuplicatePeerIds() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerTrustRecordCodec.encode(listOf(record(), record(seed = 4)))
        }
    }

    @Test
    fun rejectsInvalidPeerIdsAndKeyLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerTrustRecordCodec.encode(listOf(record(peerID = "not-a-peer")))
        }
        val invalidKey = record().copy(noisePublicKey = ByteArray(31))
        assertThrows(IllegalArgumentException::class.java) {
            PeerTrustRecordCodec.encode(listOf(invalidKey))
        }
    }

    @Test
    fun rejectsTruncatedAndTrailingData() {
        val encoded = PeerTrustRecordCodec.encode(listOf(record()))
        assertThrows(Exception::class.java) {
            PeerTrustRecordCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerTrustRecordCodec.decode(encoded + byteArrayOf(0x01))
        }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val encoded = PeerTrustRecordCodec.encode(emptyList()).also { it[4] = 2 }
        assertThrows(IllegalArgumentException::class.java) {
            PeerTrustRecordCodec.decode(encoded)
        }
    }
}
