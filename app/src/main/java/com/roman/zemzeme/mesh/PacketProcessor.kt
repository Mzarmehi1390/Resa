package com.roman.zemzeme.mesh

import android.util.Log
import com.roman.zemzeme.protocol.ZemzemePacket
import com.roman.zemzeme.protocol.MessageType
import com.roman.zemzeme.model.RoutedPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor

/**
 * Processes incoming packets and routes them to appropriate handlers
 * 
 * Per-peer packet serialization using Kotlin coroutine actors
 * Prevents race condition where multiple threads process packets
 * from the same peer simultaneously, causing session management conflicts.
 */
class PacketProcessor(private val myPeerID: String) {
    private val debugManager by lazy { try { com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    
    companion object {
        private const val TAG = "PacketProcessor"
    }
    
    // Delegate for callbacks
    var delegate: PacketProcessorDelegate? = null
    
    // Helper function to format peer ID with nickname for logging
    private fun formatPeerForLog(peerID: String): String {
        val nickname = delegate?.getPeerNickname(peerID)
        return if (nickname != null) "$peerID ($nickname)" else peerID
    }
    
    // Packet relay manager for centralized relay decisions
    private val packetRelayManager = PacketRelayManager(myPeerID)
    
    // Coroutines
    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Per-peer actors to serialize packet processing
    // Each peer gets its own actor that processes packets sequentially
    // This prevents race conditions in session management
    private val actorsLock = Any()
    private var isShutdown = false
    
    @OptIn(ObsoleteCoroutinesApi::class)
    private fun getOrCreateActorForPeer(peerID: String) = processorScope.actor<RoutedPacket>(
        capacity = Channel.UNLIMITED
    ) {
        Log.i(TAG, "🎭 Created packet actor for peer: ${formatPeerForLog(peerID)}")
        try {
            for (packet in channel) {
                try {
                    Log.i(TAG, "📦 Processing packet type ${packet.packet.type} from ${formatPeerForLog(peerID)} (serialized)")
                    handleReceivedPacket(packet)
                    Log.i(TAG, "Completed packet type ${packet.packet.type} from ${formatPeerForLog(peerID)}")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Isolate handler failures; never bypass per-peer ordering.
                    Log.e(TAG, "Packet processing failed for peer $peerID", error)
                }
            }
        } finally {
            Log.i(TAG, "🎭 Packet actor for ${formatPeerForLog(peerID)} terminated")
        }
    }
    
    // Access this actor registry only while holding actorsLock.
    private val actors = mutableMapOf<String, kotlinx.coroutines.channels.SendChannel<RoutedPacket>>()
    
    init {
        // Set up the packet relay manager delegate immediately
        setupRelayManager()
    }
    
    /**
     * Process received packet - main entry point for all incoming packets
     * SURGICAL FIX: Route to per-peer actor for serialized processing
     */
    fun processPacket(routed: RoutedPacket) {
        Log.i(TAG, "processPacket ${routed.packet.type}")
        val peerID = routed.peerID

        if (peerID == null) {
            Log.w(TAG, "Received packet with no peer ID, skipping")
            return
        }

        // BLE callbacks may arrive concurrently. Serialize actor creation with shutdown.
        val actor = synchronized(actorsLock) {
            if (isShutdown) {
                Log.w(TAG, "Ignoring packet for $peerID after processor shutdown")
                return
            }
            actors.getOrPut(peerID) { getOrCreateActorForPeer(peerID) }
        }

        // Keep this channel unlimited until BLE has reliable application ACK/retry
        // semantics. trySend avoids one producer coroutine per incoming packet.
        val result = actor.trySend(routed)
        if (result.isFailure) {
            Log.w(TAG, "Packet for $peerID was not admitted: ${result.exceptionOrNull()?.message}")
        }
    }
    
    /**
     * Set up the packet relay manager with its delegate
     */
    fun setupRelayManager() {
        packetRelayManager.delegate = object : PacketRelayManagerDelegate {
            override fun getNetworkSize(): Int {
                return delegate?.getNetworkSize() ?: 1
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return delegate?.getBroadcastRecipient() ?: ByteArray(0)
            }
            
            override fun broadcastPacket(routed: RoutedPacket) {
                delegate?.relayPacket(routed)
            }
            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                return delegate?.sendToPeer(peerID, routed) ?: false
            }
        }
    }
    
    /**
     * Handle received packet - core protocol logic (exact same as iOS)
     */
    private suspend fun handleReceivedPacket(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Basic validation and security checks
        if (delegate?.validatePacketSecurity(packet, peerID) != true) {
            Log.i(TAG, "Packet failed security validation from ${formatPeerForLog(peerID)}")
            return
        }

        var validPacket = true
        val messageType = MessageType.fromValue(packet.type)
        Log.i(TAG, "Processing packet type ${messageType} from ${formatPeerForLog(peerID)}")
        // Verbose logging to debug manager (and chat via ChatViewModel observer)
        try {
            val mt = messageType?.name ?: packet.type.toString()
            val routeDevice = routed.relayAddress
            val nick = delegate?.getPeerNickname(peerID)
            debugManager?.logIncomingPacket(peerID, nick, mt, routeDevice)
        } catch (_: Exception) { }
        
        
        // Handle public packet types (no address check needed)
        when (messageType) {
            MessageType.ANNOUNCE -> handleAnnounce(routed)
            MessageType.MESSAGE -> handleMessage(routed)
            MessageType.FILE_TRANSFER -> handleMessage(routed) // treat same routing path; parsing happens in handler
            MessageType.LEAVE -> handleLeave(routed)
            MessageType.FRAGMENT -> handleFragment(routed)
            MessageType.REQUEST_SYNC -> handleRequestSync(routed)
            else -> {
                // Handle private packet types (address check required)
                if (packetRelayManager.isPacketAddressedToMe(packet)) {
                    when (messageType) {
                        MessageType.NOISE_HANDSHAKE -> handleNoiseHandshake(routed)
                        MessageType.NOISE_ENCRYPTED -> handleNoiseEncrypted(routed)
                        MessageType.FILE_TRANSFER -> handleMessage(routed)
                        else -> {
                            validPacket = false
                            Log.w(TAG, "Unknown message type: ${packet.type}")
                        }
                    }
                } else {
                    Log.i(TAG, "Private packet type ${messageType} not addressed to us (from: ${formatPeerForLog(peerID)} to ${packet.recipientID?.let { it.joinToString("") { b -> "%02x".format(b) } }}), skipping")
                }
            }
        }
        
        // Update last seen timestamp
        if (validPacket) {
            delegate?.updatePeerLastSeen(peerID)
            
            // CENTRALIZED RELAY LOGIC: Handle relay decisions for all packets not addressed to us
            packetRelayManager.handlePacketRelay(routed)
        }
    }
    
    /**
     * Handle Noise handshake message - SIMPLIFIED iOS-compatible version
     */
    private suspend fun handleNoiseHandshake(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.i(TAG, "Processing Noise handshake from ${formatPeerForLog(peerID)}")
        delegate?.handleNoiseHandshake(routed)
    }
    
    /**
     * Handle Noise encrypted transport message
     */
    private suspend fun handleNoiseEncrypted(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.i(TAG, "Processing Noise encrypted message from ${formatPeerForLog(peerID)}")
        delegate?.handleNoiseEncrypted(routed)
    }
    
    /**
     * Handle announce message
     */
    private suspend fun handleAnnounce(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.i(TAG, "Processing announce from ${formatPeerForLog(peerID)}")
        delegate?.handleAnnounce(routed)
    }
    
    /**
     * Handle regular message
     */
    private suspend fun handleMessage(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.i(TAG, "Processing message from ${formatPeerForLog(peerID)}")
        delegate?.handleMessage(routed)
    }
    
    /**
     * Handle leave message
     */
    private suspend fun handleLeave(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.i(TAG, "Processing leave from ${formatPeerForLog(peerID)}")
        delegate?.handleLeave(routed)
    }
    
    /**
     * Handle message fragments
     */
    private suspend fun handleFragment(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.i(TAG, "Processing fragment from ${formatPeerForLog(peerID)}")
        
        val reassembledPacket = delegate?.handleFragment(routed.packet)
        if (reassembledPacket != null) {
            Log.i(TAG, "Fragment reassembled, processing complete message")
            handleReceivedPacket(RoutedPacket(reassembledPacket, routed.peerID, routed.relayAddress))
        }
        
        // Fragment relay is now handled by centralized PacketRelayManager
    }

    /**
     * Handle REQUEST_SYNC packets (public, TTL=1)
     */
    private suspend fun handleRequestSync(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.i(TAG, "Processing REQUEST_SYNC from ${formatPeerForLog(peerID)}")
        delegate?.handleRequestSync(routed)
    }
    
    /**
     * Handle delivery acknowledgment
     */
//    private suspend fun handleDeliveryAck(routed: RoutedPacket) {
//        val peerID = routed.peerID ?: "unknown"
//        Log.i(TAG, "Processing delivery ACK from ${formatPeerForLog(peerID)}")
//        delegate?.handleDeliveryAck(routed)
//    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        val peerIDs = synchronized(actorsLock) { actors.keys.toList() }
        return buildString {
            appendLine("=== Packet Processor Debug Info ===")
            appendLine("Processor Scope Active: ${processorScope.isActive}")
            appendLine("Active Peer Actors: ${peerIDs.size}")
            appendLine("My Peer ID: $myPeerID")
            if (peerIDs.isNotEmpty()) {
                appendLine("Peer Actors:")
                peerIDs.forEach { peerID -> appendLine("  - $peerID") }
            }
        }
    }
    
    /**
     * Shutdown the processor and all peer actors
     */
    fun shutdown() {
        val peerActors = synchronized(actorsLock) {
            if (isShutdown) return
            isShutdown = true
            actors.values.toList().also { actors.clear() }
        }
        Log.i(TAG, "Shutting down PacketProcessor and ${peerActors.size} peer actors")
        peerActors.forEach { actor -> actor.close() }
        packetRelayManager.shutdown()
        processorScope.cancel()
        Log.i(TAG, "PacketProcessor shutdown complete")
    }
}

/**
 * Delegate interface for packet processor callbacks
 */
interface PacketProcessorDelegate {
    // Security validation
    fun validatePacketSecurity(packet: ZemzemePacket, peerID: String): Boolean
    
    // Peer management
    fun updatePeerLastSeen(peerID: String)
    fun getPeerNickname(peerID: String): String?
    
    // Network information
    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray
    
    // Message type handlers
    fun handleNoiseHandshake(routed: RoutedPacket): Boolean
    fun handleNoiseEncrypted(routed: RoutedPacket)
    fun handleAnnounce(routed: RoutedPacket)
    fun handleMessage(routed: RoutedPacket)
    fun handleLeave(routed: RoutedPacket)
    fun handleFragment(packet: ZemzemePacket): ZemzemePacket?
    fun handleRequestSync(routed: RoutedPacket)
    
    // Communication
    fun sendAnnouncementToPeer(peerID: String)
    fun sendCachedMessages(peerID: String)
    fun relayPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}
