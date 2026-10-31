package com.roman.zemzeme.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class ChannelMessageCryptoTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val otherKey = SecretKeySpec(ByteArray(32) { (it + 2).toByte() }, "AES")

    @Test
    fun encryptDecryptRoundTrip() {
        val plaintext = "رسا؛ پیام آزمایشی".toByteArray(Charsets.UTF_8)
        val envelope = ChannelMessageCrypto.encrypt(plaintext, key, "#general", "msg_001")

        assertNotNull(ChannelMessageEnvelope.decode(ChannelMessageEnvelope.encode(envelope)))
        assertArrayEquals(plaintext, ChannelMessageCrypto.decrypt(envelope, key))
    }

    @Test
    fun decryptionRejectsDifferentKey() {
        val envelope = ChannelMessageCrypto.encrypt(
            "secret".toByteArray(), key, "#general", "msg_002"
        )

        assertNull(ChannelMessageCrypto.decrypt(envelope, otherKey))
    }

    @Test
    fun decryptionRejectsChangedChannelOrMessageId() {
        val envelope = ChannelMessageCrypto.encrypt(
            "secret".toByteArray(), key, "#general", "msg_003"
        )

        assertNull(ChannelMessageCrypto.decrypt(envelope.copy(channelId = "#other"), key))
        assertNull(ChannelMessageCrypto.decrypt(envelope.copy(messageId = "msg_004"), key))
    }

    @Test
    fun decryptionRejectsModifiedCiphertext() {
        val envelope = ChannelMessageCrypto.encrypt(
            "secret".toByteArray(), key, "#general", "msg_005"
        )
        val changed = envelope.ciphertext.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }

        assertNull(ChannelMessageCrypto.decrypt(envelope.copy(ciphertext = changed), key))
    }

    @Test
    fun emptyPlaintextIsSupported() {
        val envelope = ChannelMessageCrypto.encrypt(
            ByteArray(0), key, "#general", "msg_006"
        )

        assertArrayEquals(ByteArray(0), ChannelMessageCrypto.decrypt(envelope, key))
    }
}
