package com.roman.zemzeme.mesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local, authenticated storage for verified peer public keys.
 *
 * The records contain public identity material, not private keys. AES-GCM protects
 * the trust database against offline disclosure and detects modification. If the
 * keystore key is unavailable or stored data cannot be authenticated, the store
 * fails closed and will not silently replace the existing trust database.
 */
internal data class PeerTrustRecord(
    val peerID: String,
    val nickname: String,
    val noisePublicKey: ByteArray,
    val signingPublicKey: ByteArray
)

internal object PeerTrustRecordCodec {
    private const val MAGIC = 0x52535452 // RSTR
    private const val VERSION = 1
    private const val MAX_RECORDS = 4096
    private const val MAX_NICKNAME_BYTES = 256
    private const val KEY_BYTES = 32

    fun encode(records: Collection<PeerTrustRecord>): ByteArray {
        require(records.size <= MAX_RECORDS) { "Too many peer trust records" }
        val ids = HashSet<String>()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeByte(VERSION)
            data.writeShort(records.size)
            records.forEach { record ->
                require(isValidPeerId(record.peerID)) { "Invalid peer ID" }
                require(ids.add(record.peerID)) { "Duplicate peer ID" }
                val nickname = record.nickname.toByteArray(Charsets.UTF_8)
                require(nickname.isNotEmpty() && nickname.size <= MAX_NICKNAME_BYTES) {
                    "Invalid nickname length"
                }
                require(record.noisePublicKey.size == KEY_BYTES) { "Invalid Noise key length" }
                require(record.signingPublicKey.size == KEY_BYTES) { "Invalid signing key length" }
                writeBytes(data, record.peerID.toByteArray(Charsets.US_ASCII))
                writeBytes(data, nickname)
                data.write(record.noisePublicKey)
                data.write(record.signingPublicKey)
            }
        }
        return output.toByteArray()
    }

    fun decode(encoded: ByteArray): List<PeerTrustRecord> {
        require(encoded.size >= 7) { "Truncated trust database" }
        val input = DataInputStream(ByteArrayInputStream(encoded))
        require(input.readInt() == MAGIC) { "Invalid trust database magic" }
        require(input.readUnsignedByte() == VERSION) { "Unsupported trust database version" }
        val count = input.readUnsignedShort()
        require(count <= MAX_RECORDS) { "Too many trust records" }
        val ids = HashSet<String>()
        val records = ArrayList<PeerTrustRecord>(count)
        repeat(count) {
            val peerIdBytes = readBytes(input, 16)
            val peerID = String(peerIdBytes, Charsets.US_ASCII)
            require(isValidPeerId(peerID) && ids.add(peerID)) { "Invalid or duplicate peer ID" }
            val nicknameBytes = readBytes(input, MAX_NICKNAME_BYTES)
            require(nicknameBytes.isNotEmpty()) { "Empty nickname" }
            val nickname = Charsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(nicknameBytes)).toString()
            require(nickname.isNotBlank()) { "Empty nickname" }
            val noiseKey = ByteArray(KEY_BYTES).also(input::readFully)
            val signingKey = ByteArray(KEY_BYTES).also(input::readFully)
            records += PeerTrustRecord(peerID, nickname, noiseKey, signingKey)
        }
        require(input.available() == 0) { "Trailing trust database data" }
        return records
    }

    private fun writeBytes(output: DataOutputStream, value: ByteArray) {
        require(value.isNotEmpty() && value.size <= 0xFFFF)
        output.writeShort(value.size)
        output.write(value)
    }

    private fun readBytes(input: DataInputStream, max: Int): ByteArray {
        val size = input.readUnsignedShort()
        require(size in 1..max) { "Invalid field length" }
        return ByteArray(size).also(input::readFully)
    }

    private fun isValidPeerId(value: String): Boolean =
        value.length == 16 && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
}

/**
 * Encrypted persistent trust store backed by Android Keystore AES-256-GCM.
 * All operations are synchronized because updates rewrite one authenticated snapshot.
 */
internal class PeerTrustStore(context: Context) {
    companion object {
        private const val TAG = "PeerTrustStore"
        private const val PREFS = "resa_peer_trust_v1"
        private const val DATA_KEY = "encrypted_records"
        private const val KEY_ALIAS = "resa.peer-trust-store.v1"
        private val AAD = "Resa/peer-trust-store/v1".toByteArray(Charsets.UTF_8)
        private const val NONCE_BYTES = 12
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val records = LinkedHashMap<String, PeerTrustRecord>()
    private var healthy = true

    init {
        synchronized(this) {
            val stored = preferences.getString(DATA_KEY, null)
            if (stored != null) {
                try {
                    val encrypted = Base64.decode(stored, Base64.NO_WRAP)
                    val plaintext = decrypt(encrypted)
                    PeerTrustRecordCodec.decode(plaintext).forEach { records[it.peerID] = it }
                } catch (error: Exception) {
                    healthy = false
                    Log.e(TAG, "Stored peer trust data could not be authenticated; refusing trust updates", error)
                }
            }
        }
    }

    @Synchronized
    fun get(peerID: String): PeerTrustRecord? = if (healthy) records[peerID]?.copyRecord() else null

    @Synchronized
    fun all(): List<PeerTrustRecord>? =
        if (healthy) records.values.map { it.copyRecord() } else null

    /**
     * Returns false on key mismatch, keystore failure, corrupt storage or failed disk commit.
     * Existing key material is never overwritten by this method.
     */
    @Synchronized
    fun remember(record: PeerTrustRecord): Boolean {
        if (!healthy) return false
        val current = records[record.peerID]
        if (current != null &&
            (!current.noisePublicKey.contentEquals(record.noisePublicKey) ||
                !current.signingPublicKey.contentEquals(record.signingPublicKey))
        ) return false

        val next = LinkedHashMap(records)
        next[record.peerID] = record.copyRecord()
        return try {
            val plaintext = PeerTrustRecordCodec.encode(next.values)
            val encrypted = encrypt(plaintext)
            val committed = preferences.edit()
                .putString(DATA_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
            if (committed) {
                records.clear()
                records.putAll(next)
            }
            committed
        } catch (error: Exception) {
            Log.e(TAG, "Unable to persist peer trust record", error)
            false
        }
    }

    @Synchronized
    fun clear(): Boolean {
        if (!healthy) return false
        val committed = preferences.edit().remove(DATA_KEY).commit()
        if (committed) records.clear()
        return committed
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    private fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > NONCE_BYTES + 16) { "Truncated encrypted trust data" }
        val nonce = encrypted.copyOfRange(0, NONCE_BYTES)
        val ciphertext = encrypted.copyOfRange(NONCE_BYTES, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
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

    private fun PeerTrustRecord.copyRecord() = copy(
        noisePublicKey = noisePublicKey.copyOf(),
        signingPublicKey = signingPublicKey.copyOf()
    )
}
