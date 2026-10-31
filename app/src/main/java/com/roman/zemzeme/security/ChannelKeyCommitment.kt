package com.roman.zemzeme.security

import java.security.MessageDigest

/**
 * Creates and checks non-secret fingerprints of derived channel keys.
 *
 * A commitment is only useful when its expected value is obtained from an
 * authenticated source. This helper deliberately does not provide transport
 * or peer authentication.
 */
internal object ChannelKeyCommitment {
    private val HEX = "0123456789abcdef".toCharArray()

    fun create(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    fun matches(key: ByteArray, expectedHex: String?): Boolean {
        if (expectedHex == null || expectedHex.length != 64) return false
        val expected = decodeHex(expectedHex) ?: return false
        val actual = MessageDigest.getInstance("SHA-256").digest(key)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun decodeHex(value: String): ByteArray? {
        if (value.length != 64) return null
        val result = ByteArray(32)
        for (index in result.indices) {
            val high = value[index * 2].digitToIntOrNull(16) ?: return null
            val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
            result[index] = ((high shl 4) or low).toByte()
        }
        return result
    }
}
