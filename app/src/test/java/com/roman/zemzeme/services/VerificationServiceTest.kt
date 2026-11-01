package com.roman.zemzeme.services

import android.util.Base64
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VerificationServiceTest {

    private fun validQrUrl(
        version: Int = 1,
        noiseKey: String = "11".repeat(32),
        signingKey: String = "22".repeat(32),
        timestamp: Long = 1_800_000_000L,
        nickname: String = "Resa",
        nonce: String = Base64.encodeToString(
            ByteArray(16),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        ),
        signature: String = "33".repeat(64)
    ): String = "zemzeme://verify".toUri().buildUpon()
        .appendQueryParameter("v", version.toString())
        .appendQueryParameter("noise", noiseKey)
        .appendQueryParameter("sign", signingKey)
        .appendQueryParameter("nick", nickname)
        .appendQueryParameter("ts", timestamp.toString())
        .appendQueryParameter("nonce", nonce)
        .appendQueryParameter("sig", signature)
        .build()
        .toString()

    @Test
    fun parsesWellFormedVersionOneQr() {
        val parsed = VerificationService.VerificationQR.fromUrlString(validQrUrl())

        assertNotNull(parsed)
        assertEquals(1, parsed?.v)
        assertEquals("Resa", parsed?.nickname)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertNull(VerificationService.VerificationQR.fromUrlString(validQrUrl(version = 2)))
    }

    @Test
    fun rejectsMalformedPublicKeyAndSignatureLengths() {
        assertNull(VerificationService.VerificationQR.fromUrlString(validQrUrl(noiseKey = "11".repeat(31))))
        assertNull(VerificationService.VerificationQR.fromUrlString(validQrUrl(signature = "33".repeat(63))))
    }

    @Test
    fun rejectsInvalidNonceEncoding() {
        assertNull(VerificationService.VerificationQR.fromUrlString(validQrUrl(nonce = "not-a-nonce")))
    }

    @Test
    fun rejectsNonPositiveTimestampAndBlankNickname() {
        assertNull(VerificationService.VerificationQR.fromUrlString(validQrUrl(timestamp = 0L)))
        assertNull(VerificationService.VerificationQR.fromUrlString(validQrUrl(nickname = "   ")))
    }

    @Test
    fun rejectsDuplicateRequiredParameters() {
        val url = validQrUrl() + "&v=1"
        assertNull(VerificationService.VerificationQR.fromUrlString(url))
    }

    @Test
    fun rejectsOversizedUrl() {
        assertNull(VerificationService.VerificationQR.fromUrlString(validQrUrl() + "x".repeat(2048)))
    }
}
