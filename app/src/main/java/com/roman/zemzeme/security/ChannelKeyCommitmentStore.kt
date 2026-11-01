package com.roman.zemzeme.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted, device-local persistence for channel key commitments.
 *
 * This restores password verification on the same installation after process
 * death. It does not distribute commitments or keys to other channel members.
 */
internal object ChannelKeyCommitmentStoreCodec {
    private const val MAGIC = 0x5253434B // RSCK
    private const val VERSION = 1
    private const val MAX_ENTRIES = 1024
    private const val MAX_CHANNEL_BYTES = 128

    fun encode(entries: Map<String, String>): ByteArray {
        require(entries.size <= MAX_ENTRIES) { "Too many channel commitments" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeByte(VERSION)
            data.writeShort(entries.size)
            entries.toSortedMap().forEach { (channel, commitment) ->
                val channelBytes = channel.toByteArray(Charsets.UTF_8)
                require(channel.isNotBlank() && channelBytes.size <= MAX_CHANNEL_BYTES)
                require(channel.none { it.isISOControl() })
                require(commitment.matches(Regex("^[a-fA-F0-9]{64}$")))
                data.writeShort(channelBytes.size)
                data.write(channelBytes)
                data.write(commitment.lowercase().toByteArray(Charsets.US_ASCII))
            }
        }
        return output.toByteArray()
    }

    fun decode(encoded: ByteArray): Map<String, String> {
        require(encoded.size >= 7) { "Truncated commitment store" }
        val input = DataInputStream(ByteArrayInputStream(encoded))
        require(input.readInt() == MAGIC) { "Invalid commitment store magic" }
        require(input.readUnsignedByte() == VERSION) { "Unsupported commitment store version" }
        val count = input.readUnsignedShort()
        require(count <= MAX_ENTRIES) { "Too many channel commitments" }

        val result = LinkedHashMap<String, String>()
        repeat(count) {
            val channelLength = input.readUnsignedShort()
            require(channelLength in 1..MAX_CHANNEL_BYTES) { "Invalid channel length" }
            val channelBytes = ByteArray(channelLength).also(input::readFully)
            val channel = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(channelBytes)).toString()
            require(channel.isNotBlank() && channel.none { it.isISOControl() })
            val commitmentBytes = ByteArray(64).also(input::readFully)
            val commitment = String(commitmentBytes, Charsets.US_ASCII)
            require(commitment.matches(Regex("^[a-f0-9]{64}$")))
            require(result.put(channel, commitment) == null) { "Duplicate channel commitment" }
        }
        require(input.available() == 0) { "Trailing commitment store data" }
        return result
    }
}

/**
 * AES-GCM encrypted preferences backed by a non-exportable Android Keystore key.
 * Authentication or storage failures permanently fail closed for this instance.
 */
internal class ChannelKeyCommitmentStore(context: Context) {
    companion object {
        private const val TAG = "ChannelKeyCommitStore"
        private const val PREFS = "resa_channel_commitments_v1"
        private const val DATA_KEY = "encrypted_commitments"
        private const val KEY_ALIAS = "resa.channel-commitments.v1"
        private val AAD = "Resa/channel-key-commitments/v1".toByteArray(Charsets.UTF_8)
        private const val NONCE_BYTES = 12
        private const val MAX_STORED_CHARS = 256 * 1024
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val entries = LinkedHashMap<String, String>()
    private var healthy = true

    init {
        synchronized(this) {
            val stored = preferences.getString(DATA_KEY, null)
            if (stored != null) {
                try {
                    require(stored.length <= MAX_STORED_CHARS) { "Commitment store is too large" }
                    val encrypted = Base64.decode(stored, Base64.NO_WRAP)
                    entries.putAll(ChannelKeyCommitmentStoreCodec.decode(decrypt(encrypted)))
                } catch (error: Exception) {
                    healthy = false
                    Log.e(TAG, "Stored channel commitments failed authentication; refusing updates", error)
                }
            }
        }
    }

    @Synchronized
    fun get(channel: String): String? = if (healthy) entries[channel] else null

    @Synchronized
    fun put(channel: String, commitment: String): Boolean {
        if (!healthy || channel.isBlank() ||
            !commitment.matches(Regex("^[a-fA-F0-9]{64}$"))
        ) return false

        val next = LinkedHashMap(entries)
        next[channel] = commitment.lowercase()
        return persist(next)
    }

    @Synchronized
    fun remove(channel: String): Boolean {
        if (!healthy || !entries.containsKey(channel)) return healthy
        val next = LinkedHashMap(entries)
        next.remove(channel)
        return persist(next)
    }

    @Synchronized
    fun clear(): Boolean {
        if (!healthy) return false
        val committed = preferences.edit().remove(DATA_KEY).commit()
        if (committed) entries.clear()
        return committed
    }

    private fun persist(next: LinkedHashMap<String, String>): Boolean = try {
        val plaintext = ChannelKeyCommitmentStoreCodec.encode(next)
        val encrypted = encrypt(plaintext)
        val committed = preferences.edit()
            .putString(DATA_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        if (committed) {
            entries.clear()
            entries.putAll(next)
        }
        committed
    } catch (error: Exception) {
        Log.e(TAG, "Unable to persist channel key commitment", error)
        false
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        require(cipher.iv.size == NONCE_BYTES) { "Unexpected AES-GCM nonce length" }
        return cipher.iv + ciphertext
    }

    private fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > NONCE_BYTES + 16) { "Truncated encrypted commitments" }
        val nonce = encrypted.copyOfRange(0, NONCE_BYTES)
        val ciphertext = encrypted.copyOfRange(NONCE_BYTES, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
    }

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Channel commitment Keystore key is missing")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
