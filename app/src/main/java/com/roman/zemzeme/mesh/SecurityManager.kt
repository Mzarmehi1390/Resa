package com.roman.zemzeme.mesh

import android.util.Log
import com.roman.zemzeme.crypto.EncryptionService
import com.roman.zemzeme.protocol.ZemzemePacket
import com.roman.zemzeme.protocol.MessageType
import com.roman.zemzeme.model.RoutedPacket
import com.roman.zemzeme.util.toHexString
import kotlinx.coroutines.*
import java.util.*
import java.security.MessageDigest
import kotlin.collections.mutableSetOf

/**
 * Manages security aspects of the mesh network including duplicate detection,
 * replay attack protection, and key exchange handling
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class SecurityManager(private val encryptionService: EncryptionService, private val myPeerID: String) {
    
    companion object {
        private const val TAG = "SecurityManager"
        private const val MESSAGE_TIMEOUT = com.roman.zemzeme.util.AppConstants.Security.MESSAGE_TIMEOUT_MS // 5 minutes (same as iOS)
        private const val CLEANUP_INTERVAL = com.roman.zemzeme.util.AppConstants.Security.CLEANUP_INTERVAL_MS // 5 minutes
        private const val MAX_PROCESSED_MESSAGES = com.roman.zemzeme.util.AppConstants.Security.MAX_PROCESSED_MESSAGES
        private const val MAX_PROCESSED_KEY_EXCHANGES = com.roman.zemzeme.util.AppConstants.Security.MAX_PROCESSED_KEY_EXCHANGES
    }
    
    // Security tracking
    private val processedMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val handshakeReplayGuard = HandshakeReplayGuard()
    private val messageTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    
    // Delegate for callbacks
    var delegate: SecurityManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Validate packet security (timestamp, replay attacks, duplicates, signatures)
     */
    fun validatePacket(packet: ZemzemePacket, peerID: String): Boolean {
        // Skip validation for our own packets
        if (peerID == myPeerID) {
            Log.d(TAG, "Skipping validation for our own packet")
            return false
        }

        val messageType = MessageType.fromValue(packet.type)
        val messageID = generateMessageID(packet, peerID)
        val isFreshAnnounce = messageType == MessageType.ANNOUNCE &&
                packet.ttl >= com.roman.zemzeme.util.AppConstants.MESSAGE_TTL_HOPS

        // Check duplicates before doing expensive signature verification. Do not
        // reserve the ID yet: an unauthenticated packet must never poison the
        // replay cache and block a later correctly signed copy.
        val wasAlreadyProcessed = synchronized(processedMessages) {
            processedMessages.contains(messageID)
        }
        if (wasAlreadyProcessed && !isFreshAnnounce) {
            Log.d(TAG, "Dropping duplicate packet: $messageID")
            return false
        }

        // Signature verification must succeed before this packet is recorded.
        if (!verifyPacketSignature(packet, peerID)) {
            Log.w(TAG, "Dropping packet from $peerID due to signature verification failure")
            return false
        }

        // Make the final duplicate check and insertion atomic. This prevents two
        // concurrent receive callbacks from both accepting the same packet.
        synchronized(processedMessages) {
            if (processedMessages.contains(messageID) && !isFreshAnnounce) {
                Log.d(TAG, "Dropping concurrently received duplicate packet: $messageID")
                return false
            }
            processedMessages.add(messageID)
            messageTimestamps[messageID] = System.currentTimeMillis()
        }

        Log.d(TAG, "Packet validation passed for $peerID, messageID: $messageID")
        return true
    }
    
    /**
     * Handle Noise handshake packet - SIMPLIFIED iOS-compatible version
     * Single handshake type with automatic response handling
     */
    suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Reject irrelevant or malformed packets before touching an active session.
        if (packet.recipientID?.toHexString() != myPeerID) {
            Log.d(TAG, "Skipping handshake not addressed to us: $peerID")
            return false
        }
        if (peerID == myPeerID || packet.payload.isEmpty()) {
            return false
        }

        // Hash the complete payload. A short contentHashCode prefix can collide,
        // causing unrelated handshake messages to be treated as duplicates.
        val payloadDigest = MessageDigest.getInstance("SHA-256").digest(packet.payload)
        val exchangeKey = "$peerID-${payloadDigest.joinToString("") { "%02x".format(it) }}"

        // Reserve the exchange atomically. A failed attempt must release the
        // reservation so a legitimate retry can be processed.
        val reserved = handshakeReplayGuard.tryBegin(exchangeKey)
        if (!reserved) {
            Log.d(TAG, "Ignoring duplicate or concurrently processed handshake from $peerID")
            return false
        }

        var handshakeProcessed = false
        try {
            // Only a new, non-duplicate authenticated exchange may replace an
            // established session. Replayed payloads are rejected above first.
            if (encryptionService.hasEstablishedSession(peerID)) {
                Log.d(TAG, "Replacing established Noise session for fresh handshake from $peerID")
                encryptionService.removePeer(peerID)
            }

            val response = encryptionService.processHandshakeMessage(packet.payload, peerID)

            // Processing succeeded (null is a valid final XX step with no response).
            // Commit the replay marker before invoking external callbacks.
            handshakeReplayGuard.markProcessed(exchangeKey)
            handshakeProcessed = true

            if (response != null) {
                Log.d(TAG, "Successfully processed Noise handshake from $peerID, sending response")
                delegate?.sendHandshakeResponse(peerID, response)
            }

            if (encryptionService.hasEstablishedSession(peerID)) {
                Log.d(TAG, "Noise handshake completed with $peerID")
                delegate?.onKeyExchangeCompleted(peerID, packet.payload)
            }
            return true
        } catch (e: Exception) {
            if (!handshakeProcessed) {
                handshakeReplayGuard.abort(exchangeKey)
            }
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
            return false
        }
    }

    /**
     * Verify packet signature
     */
    /**
     * Verify a packet using the same strict Ed25519 policy as the primary ingress path.
     * In particular, missing signatures on ANNOUNCE, MESSAGE and FILE_TRANSFER
     * packets must never be treated as successful verification.
     */
    fun verifySignature(packet: ZemzemePacket, peerID: String): Boolean =
        verifyPacketSignature(packet, peerID)
    
    /**
     * Sign packet payload
     */
    fun signPacket(payload: ByteArray): ByteArray? {
        return encryptionService.signData(payload)
    }
    
    /**
     * Encrypt payload for specific peer
     */
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
        return try {
            encryptionService.encrypt(data, recipientPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $recipientPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Decrypt payload from specific peer
     */
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
        return try {
            encryptionService.decrypt(encryptedData, senderPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $senderPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Get combined public key data for key exchange
     */
    fun getCombinedPublicKeyData(): ByteArray {
        return encryptionService.getCombinedPublicKeyData()
    }
    
    /**
     * Generate message ID for duplicate detection
     */
    private fun generateMessageID(packet: ZemzemePacket, peerID: String): String {
        // Use a cryptographic digest of the complete payload. ByteArray.contentHashCode()
        // is only 32-bit and the old non-fragment path ignored everything after byte 64,
        // allowing distinct packets to collide in the replay cache.
        val payloadDigest = MessageDigest.getInstance("SHA-256").digest(packet.payload)
            .joinToString("") { "%02x".format(it) }
        return "${packet.timestamp}-$peerID-${packet.type}-$payloadDigest"
    }
    
    /**
     * Verify packet signature using peer's signing public key
     * Returns true only if signature is present and valid
     */
    private fun verifyPacketSignature(packet: ZemzemePacket, peerID: String): Boolean {
        try {
            // Handshake messages must be signed too: Noise authenticates key
            // possession, while this signature binds the handshake packet to
            // the already-verified mesh signing identity.
            if (MessageType.fromValue(packet.type) !in setOf(
                    MessageType.ANNOUNCE,
                    MessageType.MESSAGE,
                    MessageType.FILE_TRANSFER,
                    MessageType.NOISE_HANDSHAKE
                )) {
                return true
            }
            // 1. Mandatory Signature Check
            if (packet.signature == null) {
                Log.w(TAG, "❌ Signature check for $peerID: NO_SIGNATURE (packet type ${packet.type})")
                return false
            }
            
            // 2. Get Signing Public Key
            var signingPublicKey: ByteArray? = null
            
            if (MessageType.fromValue(packet.type) == MessageType.ANNOUNCE) {
                // Special Case: ANNOUNCE packets carry their own signing key
                try {
                    val announcement = com.roman.zemzeme.model.IdentityAnnouncement.decode(packet.payload)
                    signingPublicKey = announcement?.signingPublicKey
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode announcement for key extraction: ${e.message}")
                }
            } else {
                // Standard Case: Get key from known peer info
                val peerInfo = delegate?.getPeerInfo(peerID)
                signingPublicKey = peerInfo?.signingPublicKey
            }
            
            if (signingPublicKey == null) {
                // If we don't have a key (and it's not an announce), we can't verify.
                // For security, we must reject packets from unknown peers unless it's an announce.
                Log.w(TAG, "❌ Signature check for $peerID: NO_SIGNING_KEY_AVAILABLE (packet type ${packet.type})")
                return false
            }
            
            // 3. Get Canonical Data
            val packetDataForSigning = packet.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "❌ Signature check for $peerID: ENCODING_ERROR (packet type ${packet.type})")
                return false
            }
            
            // 4. Verify Signature
            val signature = packet.signature!!
            val isSignatureValid = encryptionService.verifyEd25519Signature(
                signature,
                packetDataForSigning,
                signingPublicKey
            )
            
            if (isSignatureValid) {
                // Log.v(TAG, "✅ Signature verified for $peerID (type ${packet.type})")
                return true
            } else {
                Log.w(TAG, "❌ Signature INVALID for $peerID (type ${packet.type})")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Signature verification error for $peerID: ${e.message}")
            return false
        }
    }
    
    /**
     * Check if we have encryption keys for a peer
     */
    fun hasKeysForPeer(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Security Manager Debug Info ===")
            appendLine("Processed Messages: ${processedMessages.size}")
            appendLine("Processed Key Exchanges: ${handshakeReplayGuard.processedCount()}")
            appendLine("Message Timestamps: ${messageTimestamps.size}")
            
            val exchangeHistory = handshakeReplayGuard.processedSnapshot(10)
            if (exchangeHistory.isNotEmpty()) {
                appendLine("Key Exchange History:")
                exchangeHistory.forEach { exchange ->
                    appendLine("  - $exchange")
                }
                val remaining = handshakeReplayGuard.processedCount() - exchangeHistory.size
                if (remaining > 0) {
                    appendLine("  ... and $remaining more")
                }
            }
        }
    }
    
    /**
     * Start periodic cleanup
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldData()
            }
        }
    }
    
    /**
     * Clean up old processed messages and timestamps
     */
    private fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - MESSAGE_TIMEOUT
        var removedCount = 0
        
        // Clean up old message timestamps and corresponding processed messages
        val messagesToRemove = messageTimestamps.entries.filter { (_, timestamp) ->
            timestamp < cutoffTime
        }.map { it.key }
        
        messagesToRemove.forEach { messageId ->
            messageTimestamps.remove(messageId)
            if (processedMessages.remove(messageId)) {
                removedCount++
            }
        }
        
        // Limit the size of processed messages set
        if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
            val excess = processedMessages.size - MAX_PROCESSED_MESSAGES
            val toRemove = processedMessages.take(excess)
            processedMessages.removeAll(toRemove.toSet())
            removeFromMessageTimestamps(toRemove)
            removedCount += excess
        }
        
        // Bound completed handshake replay markers without disturbing in-flight work.
        handshakeReplayGuard.trimTo(MAX_PROCESSED_KEY_EXCHANGES)
        
        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount old processed messages")
        }
    }
    
    /**
     * Helper to remove entries from messageTimestamps
     */
    private fun removeFromMessageTimestamps(messageIds: List<String>) {
        messageIds.forEach { messageId ->
            messageTimestamps.remove(messageId)
        }
    }
    
    /**
     * Clear all security data
     */
    fun clearAllData() {
        processedMessages.clear()
        handshakeReplayGuard.clear()
        messageTimestamps.clear()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllData()
    }
}

/**
 * Delegate interface for security manager callbacks
 */
interface SecurityManagerDelegate {
    fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray)
    fun sendHandshakeResponse(peerID: String, response: ByteArray)
    fun getPeerInfo(peerID: String): PeerInfo? // NEW: For signature verification
}
