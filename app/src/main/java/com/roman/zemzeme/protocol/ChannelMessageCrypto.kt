package com.roman.zemzeme.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM protection for channel envelopes.
 *
 * Channel and message identifiers are authenticated as additional data, so an
 * envelope cannot be moved to another channel or assigned a different message
 * identifier without causing decryption to fail.
 *
 * This helper does not establish trust in the key, distribute keys, prevent
 * replay, or connect itself to any transport.
 */
object ChannelMessageCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private const val TAG_SIZE_BYTES = TAG_SIZE_BITS / 8
    private const val MIN_AES_KEY_SIZE_BYTES = 16
    private const val MAX_AES_KEY_SIZE_BYTES = 32
    private val DOMAIN = "RESA-CHANNEL-MESSAGE".toByteArray(Charsets.US_ASCII)

    /**
     * Encrypts plaintext into the opaque ciphertext field expected by
     * [ChannelMessageEnvelope]. The field is nonce || GCM(ciphertext + tag).
     */
    fun encrypt(
        plaintext: ByteArray,
        key: SecretKey,
        channelId: String,
        messageId: String
    ): ChannelMessageEnvelope {
        validateKey(key)
        // Reuse the envelope's identifier and size validation before encryption.
        val placeholder = ByteArray(NONCE_SIZE_BYTES + TAG_SIZE_BYTES)
        ChannelMessageEnvelope.encode(
            ChannelMessageEnvelope(channelId, messageId, placeholder)
        )

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv
        require(nonce.size == NONCE_SIZE_BYTES) { "Unexpected AES-GCM nonce size" }
        cipher.updateAAD(associatedData(channelId, messageId))
        val encrypted = cipher.doFinal(plaintext)

        val envelope = ChannelMessageEnvelope(
            channelId = channelId,
            messageId = messageId,
            ciphertext = nonce + encrypted
        )
        // Enforce the same maximum frame size as the transport codec.
        ChannelMessageEnvelope.encode(envelope)
        return envelope
    }

    /**
     * Returns null for invalid framing, a wrong key, or failed authentication.
     */
    fun decrypt(envelope: ChannelMessageEnvelope, key: SecretKey): ByteArray? {
        return try {
            validateKey(key)
            // Validate metadata and ciphertext bounds before allocating or decrypting.
            ChannelMessageEnvelope.encode(envelope)
            if (envelope.ciphertext.size < NONCE_SIZE_BYTES + TAG_SIZE_BYTES) return null

            val nonce = envelope.ciphertext.copyOfRange(0, NONCE_SIZE_BYTES)
            val encrypted = envelope.ciphertext.copyOfRange(NONCE_SIZE_BYTES, envelope.ciphertext.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_SIZE_BITS, nonce)
            )
            cipher.updateAAD(associatedData(envelope.channelId, envelope.messageId))
            cipher.doFinal(encrypted)
        } catch (_: Exception) {
            null
        }
    }

    private fun associatedData(channelId: String, messageId: String): ByteArray {
        val channel = channelId.toByteArray(Charsets.UTF_8)
        val message = messageId.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(
            DOMAIN.size + 1 + 2 + channel.size + 2 + message.size
        ).order(ByteOrder.BIG_ENDIAN).apply {
            put(DOMAIN)
            put(ChannelMessageEnvelope.VERSION.toByte())
            putShort(channel.size.toShort())
            put(channel)
            putShort(message.size.toShort())
            put(message)
        }.array()
    }

    private fun validateKey(key: SecretKey) {
        require(key.algorithm.equals("AES", ignoreCase = true)) { "AES key required" }
        // Android Keystore keys may be non-exportable; let Cipher validate
        // those keys rather than requiring raw key bytes to be extractable.
        val encoded = key.encoded ?: return
        require(encoded.size == MIN_AES_KEY_SIZE_BYTES ||
            encoded.size == 24 ||
            encoded.size == MAX_AES_KEY_SIZE_BYTES
        ) {
            "AES key must be 128, 192, or 256 bits"
        }
    }
}
