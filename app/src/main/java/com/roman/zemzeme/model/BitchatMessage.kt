package com.roman.zemzeme.model

import android.os.Parcelable
import com.google.gson.GsonBuilder
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.*

@Parcelize
enum class ZemzemeMessageType : Parcelable {
    Message,
    Audio,
    Image,
    File
}

/**
 * Delivery status for messages - exact same as iOS version
 */
sealed class DeliveryStatus : Parcelable {
    @Parcelize
    object Sending : DeliveryStatus()

    @Parcelize
    object Sent : DeliveryStatus()

    @Parcelize
    data class Delivered(val to: String, val at: Date) : DeliveryStatus()

    @Parcelize
    data class Read(val by: String, val at: Date) : DeliveryStatus()

    @Parcelize
    data class Failed(val reason: String) : DeliveryStatus()

    @Parcelize
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()

    fun getDisplayText(): String {
        return when (this) {
            is Sending -> "Sending..."
            is Sent -> "Sent"
            is Delivered -> "Delivered to ${this.to}"
            is Read -> "Read by ${this.by}"
            is Failed -> "Failed: ${this.reason}"
            is PartiallyDelivered -> "Delivered to ${this.reached}/${this.total}"
        }
    }
}

/**
 * ZemzemeMessage - 100% compatible with iOS version
 */
@Parcelize
data class ZemzemeMessage(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val sender: String,
    val content: String,
    val type: ZemzemeMessageType = ZemzemeMessageType.Message,
    val timestamp: Date,
    val isRelay: Boolean = false,
    val originalSender: String? = null,
    val isPrivate: Boolean = false,
    val recipientNickname: String? = null,
    val senderPeerID: String? = null,
    val mentions: List<String>? = null,
    val channel: String? = null,
    val encryptedContent: ByteArray? = null,
    val isEncrypted: Boolean = false,
    val deliveryStatus: DeliveryStatus? = null,
    val powDifficulty: Int? = null
) : Parcelable {

    /**
     * Convert message to binary payload format - exactly same as iOS version
     */
    fun toBinaryPayload(): ByteArray? {
        try {
            // Keep the existing wire-format ceiling, but calculate the exact size
            // and reject values that cannot be represented without truncation.
            val idBytes = id.toByteArray(Charsets.UTF_8)
            val senderBytes = sender.toByteArray(Charsets.UTF_8)
            val contentBytes = if (isEncrypted) {
                encryptedContent ?: return null
            } else {
                content.toByteArray(Charsets.UTF_8)
            }

            fun encodeOptional(value: String?): ByteArray? {
                if (value == null) return null
                val bytes = value.toByteArray(Charsets.UTF_8)
                return bytes.takeIf { it.size <= MAX_OPTIONAL_FIELD_BYTES }
            }

            if (idBytes.size > MAX_OPTIONAL_FIELD_BYTES ||
                senderBytes.size > MAX_OPTIONAL_FIELD_BYTES ||
                contentBytes.size > MAX_CONTENT_FIELD_BYTES
            ) return null

            val originalSenderBytes = encodeOptional(originalSender)
                ?: if (originalSender == null) null else return null
            val recipientNicknameBytes = encodeOptional(recipientNickname)
                ?: if (recipientNickname == null) null else return null
            val senderPeerIDBytes = encodeOptional(senderPeerID)
                ?: if (senderPeerID == null) null else return null
            val channelBytes = encodeOptional(channel)
                ?: if (channel == null) null else return null
            val mentionBytes = mentions?.map { mention ->
                encodeOptional(mention) ?: return null
            }
            if (mentionBytes != null && mentionBytes.size > MAX_OPTIONAL_FIELD_BYTES) return null

            var payloadSize = 1 + 8 + 1 + idBytes.size + 1 + senderBytes.size +
                2 + contentBytes.size
            fun addOptionalSize(bytes: ByteArray?) {
                if (bytes != null) payloadSize += 1 + bytes.size
            }
            addOptionalSize(originalSenderBytes)
            addOptionalSize(recipientNicknameBytes)
            addOptionalSize(senderPeerIDBytes)
            if (mentionBytes != null) {
                payloadSize += 1 + mentionBytes.sumOf { 1 + it.size }
            }
            addOptionalSize(channelBytes)

            if (payloadSize > MAX_BINARY_PAYLOAD_BYTES) return null

            var flags: UByte = 0u
            if (isRelay) flags = flags or 0x01u
            if (isPrivate) flags = flags or 0x02u
            if (originalSender != null) flags = flags or 0x04u
            if (recipientNickname != null) flags = flags or 0x08u
            if (senderPeerID != null) flags = flags or 0x10u
            if (!mentionBytes.isNullOrEmpty()) flags = flags or 0x20u
            if (channel != null) flags = flags or 0x40u
            if (isEncrypted) flags = flags or 0x80u

            val buffer = ByteBuffer.allocate(payloadSize).apply { order(ByteOrder.BIG_ENDIAN) }
            buffer.put(flags.toByte())
            buffer.putLong(timestamp.time)

            buffer.put(idBytes.size.toByte())
            buffer.put(idBytes)
            buffer.put(senderBytes.size.toByte())
            buffer.put(senderBytes)
            buffer.putShort(contentBytes.size.toShort())
            buffer.put(contentBytes)

            fun putOptional(bytes: ByteArray?) {
                if (bytes != null) {
                    buffer.put(bytes.size.toByte())
                    buffer.put(bytes)
                }
            }
            putOptional(originalSenderBytes)
            putOptional(recipientNicknameBytes)
            putOptional(senderPeerIDBytes)

            if (!mentionBytes.isNullOrEmpty()) {
                buffer.put(mentionBytes.size.toByte())
                mentionBytes.forEach { mention ->
                    buffer.put(mention.size.toByte())
                    buffer.put(mention)
                }
            }
            putOptional(channelBytes)

            return buffer.array()
        } catch (e: Exception) {
            return null
        }
    }

    companion object {
        private const val MAX_BINARY_PAYLOAD_BYTES = 4096
        private const val MAX_OPTIONAL_FIELD_BYTES = 255
        private const val MAX_CONTENT_FIELD_BYTES = 65535

        private fun decodeUtf8(bytes: ByteArray): String? {
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                null
            }
        }

        /**
         * Parse message from binary payload - exactly same logic as iOS version
         */
        fun fromBinaryPayload(data: ByteArray): ZemzemeMessage? {
            try {
                if (data.size < 13 || data.size > MAX_BINARY_PAYLOAD_BYTES) return null

                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                // Flags
                val flags = buffer.get().toUByte()
                val isRelay = (flags and 0x01u) != 0u.toUByte()
                val isPrivate = (flags and 0x02u) != 0u.toUByte()
                val hasOriginalSender = (flags and 0x04u) != 0u.toUByte()
                val hasRecipientNickname = (flags and 0x08u) != 0u.toUByte()
                val hasSenderPeerID = (flags and 0x10u) != 0u.toUByte()
                val hasMentions = (flags and 0x20u) != 0u.toUByte()
                val hasChannel = (flags and 0x40u) != 0u.toUByte()
                val isEncrypted = (flags and 0x80u) != 0u.toUByte()

                // Timestamp
                val timestampMillis = buffer.getLong()
                val timestamp = Date(timestampMillis)

                // ID
                val idLength = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < idLength) return null
                val idBytes = ByteArray(idLength)
                buffer.get(idBytes)
                val id = decodeUtf8(idBytes) ?: return null

                // Sender
                val senderLength = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < senderLength) return null
                val senderBytes = ByteArray(senderLength)
                buffer.get(senderBytes)
                val sender = decodeUtf8(senderBytes) ?: return null

                // Content
                val contentLength = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < contentLength) return null

                val content: String
                val encryptedContent: ByteArray?

                if (isEncrypted) {
                    val encryptedBytes = ByteArray(contentLength)
                    buffer.get(encryptedBytes)
                    encryptedContent = encryptedBytes
                    content = "" // Empty placeholder
                } else {
                    val contentBytes = ByteArray(contentLength)
                    buffer.get(contentBytes)
                    content = decodeUtf8(contentBytes) ?: return null
                    encryptedContent = null
                }

                // Optional fields. Every field announced by a flag must be complete;
                // accepting a truncated field would silently change message semantics.
                fun readOptionalString(fieldName: String): String {
                    if (!buffer.hasRemaining()) throw IllegalArgumentException("Missing $fieldName length")
                    val length = buffer.get().toInt() and 0xFF
                    if (buffer.remaining() < length) {
                        throw IllegalArgumentException("Truncated $fieldName")
                    }
                    val bytes = ByteArray(length)
                    buffer.get(bytes)
                    return decodeUtf8(bytes) ?: throw IllegalArgumentException("Invalid UTF-8 in $fieldName")
                }

                val originalSender = if (hasOriginalSender) {
                    readOptionalString("original sender")
                } else null

                val recipientNickname = if (hasRecipientNickname) {
                    readOptionalString("recipient nickname")
                } else null

                val senderPeerID = if (hasSenderPeerID) {
                    readOptionalString("sender peer ID")
                } else null

                // Mentions array
                val mentions = if (hasMentions) {
                    if (!buffer.hasRemaining()) {
                        throw IllegalArgumentException("Missing mentions count")
                    }
                    val mentionCount = buffer.get().toInt() and 0xFF
                    val mentionList = mutableListOf<String>()
                    repeat(mentionCount) {
                        mentionList.add(readOptionalString("mention"))
                    }
                    mentionList.takeIf { it.isNotEmpty() }
                } else null

                // Channel
                val channel = if (hasChannel) {
                    readOptionalString("channel")
                } else null

                // The binary payload is a complete message, not a stream. Trailing
                // bytes indicate corruption, concatenation, or an incompatible format.
                if (buffer.hasRemaining()) {
                    throw IllegalArgumentException("Unexpected trailing message bytes")
                }

                return ZemzemeMessage(
                    id = id,
                    sender = sender,
                    content = content,
                    type = ZemzemeMessageType.Message,
                    timestamp = timestamp,
                    isRelay = isRelay,
                    originalSender = originalSender,
                    isPrivate = isPrivate,
                    recipientNickname = recipientNickname,
                    senderPeerID = senderPeerID,
                    mentions = mentions,
                    channel = channel,
                    encryptedContent = encryptedContent,
                    isEncrypted = isEncrypted
                )

            } catch (e: Exception) {
                return null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ZemzemeMessage

        if (id != other.id) return false
        if (sender != other.sender) return false
        if (content != other.content) return false
        if (type != other.type) return false
        if (timestamp != other.timestamp) return false
        if (isRelay != other.isRelay) return false
        if (originalSender != other.originalSender) return false
        if (isPrivate != other.isPrivate) return false
        if (recipientNickname != other.recipientNickname) return false
        if (senderPeerID != other.senderPeerID) return false
        if (mentions != other.mentions) return false
        if (channel != other.channel) return false
        if (encryptedContent != null) {
            if (other.encryptedContent == null) return false
            if (!encryptedContent.contentEquals(other.encryptedContent)) return false
        } else if (other.encryptedContent != null) return false
        if (isEncrypted != other.isEncrypted) return false
        if (deliveryStatus != other.deliveryStatus) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isRelay.hashCode()
        result = 31 * result + (originalSender?.hashCode() ?: 0)
        result = 31 * result + isPrivate.hashCode()
        result = 31 * result + (recipientNickname?.hashCode() ?: 0)
        result = 31 * result + (senderPeerID?.hashCode() ?: 0)
        result = 31 * result + (mentions?.hashCode() ?: 0)
        result = 31 * result + (channel?.hashCode() ?: 0)
        result = 31 * result + (encryptedContent?.contentHashCode() ?: 0)
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + (deliveryStatus?.hashCode() ?: 0)
        return result
    }
}


