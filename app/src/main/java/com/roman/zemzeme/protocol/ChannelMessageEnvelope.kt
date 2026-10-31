package com.roman.zemzeme.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/**
 * Strict, versioned framing for encrypted channel-message payloads.
 *
 * The ciphertext is opaque to this class. Callers must encrypt it with an
 * authenticated cipher (currently intended: AES-GCM) before encoding, and
 * authenticate/decrypt it only after decoding. This codec does not distribute
 * keys, authenticate senders, or provide encryption by itself.
 */
data class ChannelMessageEnvelope(
    val channelId: String,
    val messageId: String,
    val ciphertext: ByteArray
) {
    companion object {
        private val MAGIC = byteArrayOf(0x52, 0x53, 0x43, 0x48) // "RSCH"
        const val VERSION: Int = 1

        private const val HEADER_SIZE = 13
        private const val MAX_CHANNEL_ID_BYTES = 128
        private const val MAX_MESSAGE_ID_BYTES = 64
        private const val MIN_GCM_CIPHERTEXT_BYTES = 28 // 12-byte nonce + 16-byte tag
        private const val MAX_CIPHERTEXT_BYTES = 65_536

        fun encode(envelope: ChannelMessageEnvelope): ByteArray {
            val channelBytes = envelope.channelId.toByteArray(Charsets.UTF_8)
            val messageIdBytes = envelope.messageId.toByteArray(Charsets.US_ASCII)
            validate(envelope.channelId, channelBytes, envelope.messageId, messageIdBytes, envelope.ciphertext)

            val buffer = ByteBuffer.allocate(
                HEADER_SIZE + channelBytes.size + messageIdBytes.size + envelope.ciphertext.size
            ).order(ByteOrder.BIG_ENDIAN)

            buffer.put(MAGIC)
            buffer.put(VERSION.toByte())
            buffer.putShort(channelBytes.size.toShort())
            buffer.putShort(messageIdBytes.size.toShort())
            buffer.putInt(envelope.ciphertext.size)
            buffer.put(channelBytes)
            buffer.put(messageIdBytes)
            buffer.put(envelope.ciphertext)
            return buffer.array()
        }

        /**
         * Identifies frames using the reserved Resa channel-envelope magic.
         * This deliberately does not imply that the frame is valid or trusted.
         */
        fun hasMagicPrefix(encoded: ByteArray): Boolean =
            encoded.size >= MAGIC.size && MAGIC.indices.all { encoded[it] == MAGIC[it] }

        fun decode(encoded: ByteArray): ChannelMessageEnvelope? {
            if (encoded.size < HEADER_SIZE) return null

            return try {
                val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
                for (expected in MAGIC) {
                    if (buffer.get() != expected) return null
                }

                val version = buffer.get().toInt() and 0xff
                if (version != VERSION) return null

                val channelLength = buffer.short.toInt() and 0xffff
                val messageIdLength = buffer.short.toInt() and 0xffff
                val ciphertextLength = buffer.int

                if (channelLength !in 1..MAX_CHANNEL_ID_BYTES) return null
                if (messageIdLength !in 1..MAX_MESSAGE_ID_BYTES) return null
                if (ciphertextLength !in MIN_GCM_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) return null

                val expectedSize = HEADER_SIZE.toLong() + channelLength + messageIdLength + ciphertextLength
                if (expectedSize != encoded.size.toLong()) return null

                val channelBytes = ByteArray(channelLength).also(buffer::get)
                val messageIdBytes = ByteArray(messageIdLength).also(buffer::get)
                val ciphertext = ByteArray(ciphertextLength).also(buffer::get)

                val channelId = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(channelBytes)).toString()
                val messageId = String(messageIdBytes, Charsets.US_ASCII)

                validate(channelId, channelBytes, messageId, messageIdBytes, ciphertext)
                ChannelMessageEnvelope(channelId, messageId, ciphertext)
            } catch (_: Exception) {
                null
            }
        }

        private fun validate(
            channelId: String,
            channelBytes: ByteArray,
            messageId: String,
            messageIdBytes: ByteArray,
            ciphertext: ByteArray
        ) {
            require(channelId.isNotBlank() && channelBytes.size <= MAX_CHANNEL_ID_BYTES)
            require(channelId.none { it.isISOControl() })
            require(messageId.isNotBlank() && messageIdBytes.size <= MAX_MESSAGE_ID_BYTES)
            require(messageId.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' })
            require(ciphertext.size in MIN_GCM_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ChannelMessageEnvelope &&
            channelId == other.channelId &&
            messageId == other.messageId &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int =
        31 * (31 * channelId.hashCode() + messageId.hashCode()) + ciphertext.contentHashCode()
}
