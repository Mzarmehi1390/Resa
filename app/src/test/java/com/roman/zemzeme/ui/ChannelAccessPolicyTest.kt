package com.roman.zemzeme.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAccessPolicyTest {

    @Test
    fun orphanedCommitmentStillMarksChannelAsProtected() {
        assertTrue(ChannelAccessPolicy.isPasswordProtected(
            markerPresent = false,
            commitmentPresent = true
        ))
    }

    @Test
    fun missingMarkerAndCommitmentMeansChannelIsOpen() {
        assertFalse(ChannelAccessPolicy.isPasswordProtected(
            markerPresent = false,
            commitmentPresent = false
        ))
    }

    @Test
    fun protectedChannelRequiresVerificationWhenSessionKeyIsMissing() {
        assertTrue(ChannelAccessPolicy.requiresPasswordVerification(
            passwordProtected = true,
            hasSessionKey = false
        ))
    }

    @Test
    fun protectedChannelDoesNotRequireAnotherPromptWhenSessionKeyExists() {
        assertFalse(ChannelAccessPolicy.requiresPasswordVerification(
            passwordProtected = true,
            hasSessionKey = true
        ))
    }

    @Test
    fun openChannelDoesNotRequirePasswordVerification() {
        assertFalse(ChannelAccessPolicy.requiresPasswordVerification(
            passwordProtected = false,
            hasSessionKey = false
        ))
    }

    @Test
    fun protectedChannelBlocksPlaintextMediaOrText() {
        assertTrue(ChannelAccessPolicy.blocksPlaintextSend(
            passwordProtected = true,
            hasSessionKey = false
        ))
    }

    @Test
    fun orphanedKeyAlsoBlocksPlaintextSend() {
        assertTrue(ChannelAccessPolicy.blocksPlaintextSend(
            passwordProtected = false,
            hasSessionKey = true
        ))
    }

    @Test
    fun openChannelWithoutKeyAllowsPlaintextSend() {
        assertFalse(ChannelAccessPolicy.blocksPlaintextSend(
            passwordProtected = false,
            hasSessionKey = false
        ))
    }
}
