package com.roman.zemzeme.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class PeerIdentityBindingTest {

    @Test
    fun `accepts peer ID derived from announced Noise key`() {
        val noiseKey = ByteArray(32) { (it * 3 + 7).toByte() }
        val peerID = MessageDigest.getInstance("SHA-256")
            .digest(noiseKey)
            .copyOfRange(0, 8)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        assertTrue(PeerIdentityBinding.matchesPeerId(peerID, noiseKey))
        assertTrue(PeerIdentityBinding.matchesPeerId(peerID.uppercase(), noiseKey))
    }

    @Test
    fun `rejects peer ID not derived from announced Noise key`() {
        val noiseKey = ByteArray(32) { it.toByte() }
        assertFalse(PeerIdentityBinding.matchesPeerId("0000000000000000", noiseKey))
    }

    @Test
    fun `rejects malformed peer IDs and wrong key lengths`() {
        val noiseKey = ByteArray(32)
        assertFalse(PeerIdentityBinding.matchesPeerId("not-a-peer-id", noiseKey))
        assertFalse(PeerIdentityBinding.matchesPeerId("0011223344556677", ByteArray(31)))
        assertFalse(PeerIdentityBinding.matchesPeerId("001122334455667", noiseKey))
    }
}
