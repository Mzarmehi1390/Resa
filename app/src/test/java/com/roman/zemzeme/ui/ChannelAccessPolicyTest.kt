package com.roman.zemzeme.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAccessPolicyTest {

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
}
