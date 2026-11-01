package com.roman.zemzeme.services

import android.net.Uri
import android.util.Base64
import com.roman.zemzeme.crypto.EncryptionService
import com.roman.zemzeme.util.AppConstants
import com.roman.zemzeme.util.dataFromHexString
import com.roman.zemzeme.util.hexEncodedString
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import androidx.core.net.toUri
import java.lang.ref.WeakReference

/**
 * QR verification helpers: schema, signing, and basic challenge/response helpers.
 */
object VerificationService {
    private const val CONTEXT = "zemzeme-verify-v1"
    private const val RESPONSE_CONTEXT = "zemzeme-verify-resp-v1"
    private const val QR_VERSION = 1
    private const val MAX_QR_URL_LENGTH = 2048
    private const val ED25519_KEY_HEX_LENGTH = 64
    private const val ED25519_SIGNATURE_HEX_LENGTH = 128
    private const val NONCE_BYTE_LENGTH = 16
    private const val CLOCK_SKEW_TOLERANCE_SECONDS = 60L

    private var encryptionServiceRef: WeakReference<EncryptionService>? = null

    fun configure(encryptionService: EncryptionService) {
        this.encryptionServiceRef = WeakReference(encryptionService)
    }

    data class VerificationQR(
        val v: Int,
        val noiseKeyHex: String,
        val signKeyHex: String,
        val npub: String?,
        val nickname: String,
        val ts: Long,
        val nonceB64: String,
        val sigHex: String
    ) {
        fun canonicalBytes(): ByteArray {
            val out = ByteArrayOutputStream()

            fun appendField(value: String) {
                val data = value.toByteArray(Charsets.UTF_8)
                require(data.size <= 255) { "Verification QR field exceeds protocol limit" }
                out.write(data.size)
                out.write(data)
            }

            appendField(CONTEXT)
            appendField(v.toString())
            appendField(noiseKeyHex.lowercase())
            appendField(signKeyHex.lowercase())
            appendField(npub ?: "")
            appendField(nickname)
            appendField(ts.toString())
            appendField(nonceB64)
            return out.toByteArray()
        }

        fun toUrlString(): String {
            val builder = Uri.Builder()
                .scheme("zemzeme")
                .authority("verify")
                .appendQueryParameter("v", v.toString())
                .appendQueryParameter("noise", noiseKeyHex)
                .appendQueryParameter("sign", signKeyHex)
                .appendQueryParameter("nick", nickname)
                .appendQueryParameter("ts", ts.toString())
                .appendQueryParameter("nonce", nonceB64)
                .appendQueryParameter("sig", sigHex)
            if (npub != null) {
                builder.appendQueryParameter("npub", npub)
            }
            return builder.build().toString()
        }

        companion object {
            fun fromUrlString(urlString: String): VerificationQR? {
                if (urlString.length > MAX_QR_URL_LENGTH) return null
                val uri = runCatching { urlString.toUri() }.getOrNull() ?: return null
                if (uri.scheme != "zemzeme" || uri.host != "verify" ||
                    !uri.path.isNullOrEmpty() || uri.fragment != null
                ) return null

                fun singleParameter(name: String): String? {
                    val values = uri.getQueryParameters(name)
                    return values.singleOrNull()
                }

                val v = singleParameter("v")?.toIntOrNull() ?: return null
                if (v != QR_VERSION) return null
                val noise = singleParameter("noise") ?: return null
                val sign = singleParameter("sign") ?: return null
                val nick = singleParameter("nick") ?: return null
                val ts = singleParameter("ts")?.toLongOrNull() ?: return null
                val nonce = singleParameter("nonce") ?: return null
                val sig = singleParameter("sig") ?: return null
                val npubValues = uri.getQueryParameters("npub")
                if (npubValues.size > 1) return null
                val npub = npubValues.singleOrNull()

                val qr = VerificationQR(
                    v = v,
                    noiseKeyHex = noise,
                    signKeyHex = sign,
                    npub = npub,
                    nickname = nick,
                    ts = ts,
                    nonceB64 = nonce,
                    sigHex = sig
                )
                return qr.takeIf { it.hasValidStructure() }
            }
        }
    }

    private fun VerificationQR.hasValidStructure(): Boolean {
        if (v != QR_VERSION || ts <= 0L) return false
        if (noiseKeyHex.length != ED25519_KEY_HEX_LENGTH ||
            !noiseKeyHex.matches(Regex("[0-9a-fA-F]{64}"))
        ) return false
        if (signKeyHex.length != ED25519_KEY_HEX_LENGTH ||
            !signKeyHex.matches(Regex("[0-9a-fA-F]{64}"))
        ) return false
        if (sigHex.length != ED25519_SIGNATURE_HEX_LENGTH ||
            !sigHex.matches(Regex("[0-9a-fA-F]{128}"))
        ) return false
        if (nonceB64.length != 22 ||
            !nonceB64.matches(Regex("[A-Za-z0-9_-]{22}"))
        ) return false
        val nonce = runCatching {
            Base64.decode(nonceB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return false
        if (nonce.size != NONCE_BYTE_LENGTH) return false
        if (nickname.isBlank() || nickname.toByteArray(Charsets.UTF_8).size > 255) return false
        if ((npub?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 255) return false
        return true
    }

    fun buildMyQRString(nickname: String, npub: String?): String? {
        if (nickname.isBlank() || nickname.toByteArray(Charsets.UTF_8).size > 255 ||
            (npub?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 255
        ) return null
        val service = encryptionServiceRef?.get() ?: return null
        val cache = Cache.last
        if (cache != null && cache.nickname == nickname && cache.npub == npub) {
            if (System.currentTimeMillis() - cache.builtAtMs < 60_000L) {
                return cache.value
            }
        }

        val noiseKey = service.getStaticPublicKey()?.hexEncodedString() ?: return null
        val signKey = service.getSigningPublicKey()?.hexEncodedString() ?: return null
        val ts = System.currentTimeMillis() / 1000L
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        val nonceB64 = Base64.encodeToString(
            nonce,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val payload = VerificationQR(
            v = 1,
            noiseKeyHex = noiseKey,
            signKeyHex = signKey,
            npub = npub,
            nickname = nickname,
            ts = ts,
            nonceB64 = nonceB64,
            sigHex = ""
        )

        val signature = service.signData(payload.canonicalBytes()) ?: return null
        val signed = payload.copy(sigHex = signature.hexEncodedString())
        val out = signed.toUrlString()
        Cache.last = CacheEntry(nickname, npub, System.currentTimeMillis(), out)
        return out
    }

    fun verifyScannedQR(
        urlString: String,
        maxAgeSeconds: Long = AppConstants.Verification.QR_MAX_AGE_SECONDS
    ): VerificationQR? {
        val service = encryptionServiceRef?.get() ?: return null
        val qr = VerificationQR.fromUrlString(urlString) ?: return null
        if (maxAgeSeconds < 0L) return null
        val now = System.currentTimeMillis() / 1000L
        if (qr.ts > now + CLOCK_SKEW_TOLERANCE_SECONDS) return null
        if (now - qr.ts > maxAgeSeconds) return null

        val sig = qr.sigHex.dataFromHexString() ?: return null
        val signKey = qr.signKeyHex.dataFromHexString() ?: return null
        val ok = service.verifyEd25519Signature(sig, qr.canonicalBytes(), signKey)
        return if (ok) qr else null
    }

    private fun isNoiseKeyHex(value: String): Boolean =
        value.length == ED25519_KEY_HEX_LENGTH && value.matches(Regex("[0-9a-fA-F]{64}"))

    private fun isValidNonce(value: ByteArray): Boolean = value.size == NONCE_BYTE_LENGTH

    fun buildVerifyChallenge(noiseKeyHex: String, nonceA: ByteArray): ByteArray {
        require(isNoiseKeyHex(noiseKeyHex)) { "Invalid Noise public key" }
        require(isValidNonce(nonceA)) { "Invalid verification nonce" }
        val noiseData = noiseKeyHex.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(noiseData.size)
        out.write(noiseData)
        out.write(0x02)
        out.write(nonceA.size)
        out.write(nonceA)
        return out.toByteArray()
    }

    fun buildVerifyResponse(noiseKeyHex: String, nonceA: ByteArray): ByteArray? {
        if (!isNoiseKeyHex(noiseKeyHex) || !isValidNonce(nonceA)) return null
        val service = encryptionServiceRef?.get() ?: return null
        val noiseData = noiseKeyHex.toByteArray(Charsets.US_ASCII)
        val msg = ByteArrayOutputStream()
        msg.write(RESPONSE_CONTEXT.toByteArray(Charsets.UTF_8))
        msg.write(noiseData.size)
        msg.write(noiseData)
        msg.write(nonceA)
        val sig = service.signData(msg.toByteArray()) ?: return null
        if (sig.size != 64) return null

        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(noiseData.size)
        out.write(noiseData)
        out.write(0x02)
        out.write(nonceA.size)
        out.write(nonceA)
        out.write(0x03)
        out.write(sig.size)
        out.write(sig)
        return out.toByteArray()
    }

    fun parseVerifyChallenge(data: ByteArray): Pair<String, ByteArray>? {
        var idx = 0

        fun take(n: Int): ByteArray? {
            if (n < 0 || idx > data.size - n) return null
            val out = data.copyOfRange(idx, idx + n)
            idx += n
            return out
        }

        val t1 = take(1) ?: return null
        if ((t1[0].toInt() and 0xff) != 0x01) return null
        val l1 = take(1)?.get(0)?.toInt()?.and(0xff) ?: return null
        if (l1 != ED25519_KEY_HEX_LENGTH) return null
        val noiseBytes = take(l1) ?: return null
        if (noiseBytes.any { (it.toInt() and 0xff).toChar() !in "0123456789abcdefABCDEF" }) return null
        val noise = noiseBytes.toString(Charsets.US_ASCII)

        val t2 = take(1) ?: return null
        if ((t2[0].toInt() and 0xff) != 0x02) return null
        val l2 = take(1)?.get(0)?.toInt()?.and(0xff) ?: return null
        if (l2 != NONCE_BYTE_LENGTH) return null
        val nonce = take(l2) ?: return null
        if (idx != data.size) return null

        return noise to nonce
    }

    data class VerifyResponse(val noiseKeyHex: String, val nonceA: ByteArray, val signature: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as VerifyResponse

            if (noiseKeyHex != other.noiseKeyHex) return false
            if (!nonceA.contentEquals(other.nonceA)) return false
            if (!signature.contentEquals(other.signature)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = noiseKeyHex.hashCode()
            result = 31 * result + nonceA.contentHashCode()
            result = 31 * result + signature.contentHashCode()
            return result
        }
    }

    fun parseVerifyResponse(data: ByteArray): VerifyResponse? {
        var idx = 0

        fun take(n: Int): ByteArray? {
            if (n < 0 || idx > data.size - n) return null
            val out = data.copyOfRange(idx, idx + n)
            idx += n
            return out
        }

        val t1 = take(1) ?: return null
        if ((t1[0].toInt() and 0xff) != 0x01) return null
        val l1 = take(1)?.get(0)?.toInt()?.and(0xff) ?: return null
        if (l1 != ED25519_KEY_HEX_LENGTH) return null
        val noiseBytes = take(l1) ?: return null
        if (noiseBytes.any { (it.toInt() and 0xff).toChar() !in "0123456789abcdefABCDEF" }) return null
        val noise = noiseBytes.toString(Charsets.US_ASCII)

        val t2 = take(1) ?: return null
        if ((t2[0].toInt() and 0xff) != 0x02) return null
        val l2 = take(1)?.get(0)?.toInt()?.and(0xff) ?: return null
        if (l2 != NONCE_BYTE_LENGTH) return null
        val nonce = take(l2) ?: return null

        val t3 = take(1) ?: return null
        if ((t3[0].toInt() and 0xff) != 0x03) return null
        val l3 = take(1)?.get(0)?.toInt()?.and(0xff) ?: return null
        if (l3 != 64) return null
        val sig = take(l3) ?: return null
        if (idx != data.size) return null

        return VerifyResponse(noise, nonce, sig)
    }

    fun verifyResponseSignature(
        noiseKeyHex: String,
        nonceA: ByteArray,
        signature: ByteArray,
        signerPublicKeyHex: String
    ): Boolean {
        if (!isNoiseKeyHex(noiseKeyHex) || !isValidNonce(nonceA) ||
            signature.size != 64 || !isNoiseKeyHex(signerPublicKeyHex)
        ) return false
        val service = encryptionServiceRef?.get() ?: return false
        val noiseData = noiseKeyHex.toByteArray(Charsets.US_ASCII)
        val msg = ByteArrayOutputStream()
        msg.write(RESPONSE_CONTEXT.toByteArray(Charsets.UTF_8))
        msg.write(noiseData.size)
        msg.write(noiseData)
        msg.write(nonceA)
        val signerKey = signerPublicKeyHex.dataFromHexString() ?: return false
        return service.verifyEd25519Signature(signature, msg.toByteArray(), signerKey)
    }

    private data class CacheEntry(
        val nickname: String,
        val npub: String?,
        val builtAtMs: Long,
        val value: String
    )

    private object Cache {
        var last: CacheEntry? = null
    }
}
