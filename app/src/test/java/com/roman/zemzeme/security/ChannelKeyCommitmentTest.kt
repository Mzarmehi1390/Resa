package com.roman.zemzeme.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelKeyCommitmentTest {

    @Test
    fun createdCommitmentMatchesOriginalKey() {
        val key = "channel-key-material".toByteArray()
        val commitment = ChannelKeyCommitment.create(key)

        assertTrue(ChannelKeyCommitment.matches(key, commitment))
    }

    @Test
    fun differentKeyDoesNotMatchCommitment() {
        val commitment = ChannelKeyCommitment.create("correct-key".toByteArray())

        assertFalse(
            ChannelKeyCommitment.matches("incorrect-key".toByteArray(), commitment)
        )
    }

    @Test
    fun missingOrMalformedCommitmentFailsClosed() {
        val key = "channel-key-material".toByteArray()

        assertFalse(ChannelKeyCommitment.matches(key, null))
        assertFalse(ChannelKeyCommitment.matches(key, ""))
        assertFalse(ChannelKeyCommitment.matches(key, "not-a-sha256-commitment"))
    }

    @Test
    fun commitmentComparisonAcceptsUppercaseHex() {
        val key = "channel-key-material".toByteArray()
        val commitment = ChannelKeyCommitment.create(key).uppercase()

        assertTrue(ChannelKeyCommitment.matches(key, commitment))
    }
}
