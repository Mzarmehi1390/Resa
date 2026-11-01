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

    @Test
    fun parsesWellFormedVerificationChallenge() {
        val key = "ab".repeat(32)
        val nonce = ByteArray(16) { it.toByte() }
        val parsed = VerificationService.parseVerifyChallenge(
            VerificationService.buildVerifyChallenge(key, nonce)
        )

        assertNotNull(parsed)
        assertEquals(key, parsed?.first)
        org.junit.Assert.assertArrayEquals(nonce, parsed?.second)
    }

    @Test
    fun rejectsMalformedChallengeFrames() {
        val key = "ab".repeat(32).toByteArray(Charsets.US_ASCII)
        val validPrefix = byteArrayOf(0x01, 0x40) + key + byteArrayOf(0x02, 0x10) + ByteArray(16)
        assertNull(VerificationService.parseVerifyChallenge(validPrefix.copyOf(validPrefix.size - 1)))
        assertNull(VerificationService.parseVerifyChallenge(validPrefix + byteArrayOf(0x00)))
        assertNull(VerificationService.parseVerifyChallenge(byteArrayOf(0x01, 0x00, 0x02, 0x10) + ByteArray(16)))
        assertNull(VerificationService.parseVerifyChallenge(byteArrayOf(0x01, 0x40.toByte()) + key + byteArrayOf(0x02, 0xff.toByte()) + ByteArray(16)))
    }

    @Test
    fun parsesOnlyWellFormedVerificationResponses() {
        val key = "cd".repeat(32).toByteArray(Charsets.US_ASCII)
        val nonce = ByteArray(16) { (it + 1).toByte() }
        val signature = ByteArray(64) { (it + 2).toByte() }
        val frame = byteArrayOf(0x01, 0x40) + key +
            byteArrayOf(0x02, 0x10) + nonce +
            byteArrayOf(0x03, 0x40) + signature

        val parsed = VerificationService.parseVerifyResponse(frame)
        assertNotNull(parsed)
        assertEquals("cd".repeat(32), parsed?.noiseKeyHex)
        org.junit.Assert.assertArrayEquals(nonce, parsed?.nonceA)
        org.junit.Assert.assertArrayEquals(signature, parsed?.signature)

        assertNull(VerificationService.parseVerifyResponse(frame.copyOf(frame.size - 1)))
        assertNull(VerificationService.parseVerifyResponse(frame + byteArrayOf(0x00)))
    }

    @Test
    fun rejectsResponseLengthsAboveSignedByteRangeAndWrongFieldSizes() {
        val key = "ef".repeat(32).toByteArray(Charsets.US_ASCII)
        val nonce = ByteArray(16)
        val signature = ByteArray(64)
        val valid = byteArrayOf(0x01, 0x40) + key +
            byteArrayOf(0x02, 0x10) + nonce +
            byteArrayOf(0x03, 0x40) + signature

        val badSignatureLength = valid.copyOf().also {
            it[it.size - 66] = 0x3f
        }
        assertNull(VerificationService.parseVerifyResponse(badSignatureLength))
        assertNull(VerificationService.parseVerifyResponse(byteArrayOf(0x01, 0xff.toByte())))
    }

}
