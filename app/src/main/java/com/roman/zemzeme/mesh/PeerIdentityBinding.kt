package com.roman.zemzeme.mesh

import java.security.MessageDigest

/**
 * Validates the protocol binding between a packet peer ID and its Noise static key.
 *
 * The wire peer ID is the first 8 bytes of SHA-256(Noise static public key),
 * represented as 16 hexadecimal characters.
 */
internal object PeerIdentityBinding {

    fun matchesPeerId(peerID: String, noisePublicKey: ByteArray): Boolean {
        if (peerID.length != 16 || noisePublicKey.size != 32) return false
        if (!peerID.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return false
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(noisePublicKey)
        val expected = digest.copyOfRange(0, 8).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return expected.equals(peerID, ignoreCase = true)
    }
}
