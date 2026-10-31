package com.roman.zemzeme.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.roman.zemzeme.util.*

/**
 * Identity announcement structure with TLV encoding
 * Compatible with iOS AnnouncementPacket TLV format
 */
@Parcelize
data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,    // Noise static public key (Curve25519.KeyAgreement)
    val signingPublicKey: ByteArray   // Ed25519 public key for signing
) : Parcelable {

    /**
     * TLV types matching iOS implementation
     */
    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u);  // NEW: Ed25519 signing public key
        
        companion object {
            fun fromValue(value: UByte): TLVType? {
                return values().find { it.value == value }
            }
        }
    }

    /**
     * Encode to TLV binary data matching iOS implementation
     */
    fun encode(): ByteArray? {
        val nicknameData = nickname.toByteArray(Charsets.UTF_8)

        // The identity keys are fixed-size protocol fields (Curve25519 and Ed25519).
        if (nicknameData.isEmpty() || nicknameData.size > 255 ||
            noisePublicKey.size != 32 || signingPublicKey.size != 32
        ) {
            return null
        }
        
        val result = mutableListOf<Byte>()
        
        // TLV for nickname
        result.add(TLVType.NICKNAME.value.toByte())
        result.add(nicknameData.size.toByte())
        result.addAll(nicknameData.toList())
        
        // TLV for noise public key
        result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte())
        result.add(noisePublicKey.size.toByte())
        result.addAll(noisePublicKey.toList())
        
        // TLV for signing public key
        result.add(TLVType.SIGNING_PUBLIC_KEY.value.toByte())
        result.add(signingPublicKey.size.toByte())
        result.addAll(signingPublicKey.toList())
        
        return result.toByteArray()
    }
    
    companion object {
        /**
         * Decode from TLV binary data matching iOS implementation
         */
        fun decode(data: ByteArray): IdentityAnnouncement? {
            if (data.isEmpty()) return null

            var offset = 0
            var nickname: String? = null
            var noisePublicKey: ByteArray? = null
            var signingPublicKey: ByteArray? = null

            while (offset < data.size) {
                // Every TLV needs a complete type/length header.
                if (data.size - offset < 2) return null

                val type = TLVType.fromValue(data[offset].toUByte())
                val length = data[offset + 1].toUByte().toInt()
                offset += 2

                if (length > data.size - offset) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    TLVType.NICKNAME -> {
                        if (nickname != null || value.isEmpty()) return null
                        nickname = try {
                            Charsets.UTF_8.newDecoder()
                                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(value))
                                .toString()
                        } catch (_: java.nio.charset.CharacterCodingException) {
                            return null
                        }
                    }
                    TLVType.NOISE_PUBLIC_KEY -> {
                        if (noisePublicKey != null || value.size != 32) return null
                        noisePublicKey = value
                    }
                    TLVType.SIGNING_PUBLIC_KEY -> {
                        if (signingPublicKey != null || value.size != 32) return null
                        signingPublicKey = value
                    }
                    null -> {
                        // Unknown TLVs remain skippable for forward compatibility.
                    }
                }
            }

            val decodedNickname = nickname ?: return null
            val decodedNoiseKey = noisePublicKey ?: return null
            val decodedSigningKey = signingPublicKey ?: return null
            return IdentityAnnouncement(decodedNickname, decodedNoiseKey, decodedSigningKey)
        }
    }
    
    // Override equals and hashCode since we use ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as IdentityAnnouncement
        
        if (nickname != other.nickname) return false
        if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        return result
    }
    
    override fun toString(): String {
        return "IdentityAnnouncement(nickname='$nickname', noisePublicKey=${noisePublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., signingPublicKey=${signingPublicKey.joinToString("") { "%02x".format(it) }.take(16)}...)"
    }
}
