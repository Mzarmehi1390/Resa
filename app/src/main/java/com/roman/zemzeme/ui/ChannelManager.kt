package com.roman.zemzeme.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.roman.zemzeme.model.ZemzemeMessage
import com.roman.zemzeme.security.ChannelKeyCommitmentStore
import com.roman.zemzeme.util.DebugLogger
import java.util.*

/**
 * Protected channels require a key in the current process before access is granted.
 * Being the locally recorded creator is not proof that the key is available after restart.
 */
internal object ChannelAccessPolicy {
    fun requiresPasswordVerification(passwordProtected: Boolean, hasSessionKey: Boolean): Boolean =
        passwordProtected && !hasSessionKey

    fun blocksPlaintextSend(passwordProtected: Boolean, hasSessionKey: Boolean): Boolean =
        passwordProtected || hasSessionKey
}

/**
 * Handles channel management including creation, joining, leaving, and encryption
 */
class ChannelManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val coroutineScope: CoroutineScope,
    private val commitmentStore: ChannelKeyCommitmentStore
) {
    
    // Channel encryption and security
    private val channelKeys = mutableMapOf<String, SecretKeySpec>()
    private val channelPasswords = mutableMapOf<String, String>()
    private val channelKeyCommitments = mutableMapOf<String, String>()
    private val retentionEnabledChannels = mutableSetOf<String>()

    init {
        // Missing/corrupt encrypted storage returns no commitments; password checks fail closed.
        commitmentStore.getKnownChannels()?.let(channelKeyCommitments::putAll)
    }
    
    // MARK: - Channel Lifecycle
    
    fun joinChannel(channel: String, password: String? = null, myPeerID: String): Boolean {
        val channelTag = if (channel.startsWith("#")) channel else "#$channel"
        
        // Check if already joined
        if (state.getJoinedChannelsValue().contains(channelTag)) {
            if (ChannelAccessPolicy.requiresPasswordVerification(
                    state.getPasswordProtectedChannelsValue().contains(channelTag),
                    channelKeys.containsKey(channelTag)
                )) {
                // Need password verification
                if (password != null) {
                    return verifyChannelPassword(channelTag, password)
                } else {
                    state.setPasswordPromptChannel(channelTag)
                    state.setShowPasswordPrompt(true)
                    return false
                }
            }
            switchToChannel(channelTag)
            return true
        }
        
        // If password protected and no key yet
        if (ChannelAccessPolicy.requiresPasswordVerification(
                state.getPasswordProtectedChannelsValue().contains(channelTag),
                channelKeys.containsKey(channelTag)
            )) {
            // Creator status is local metadata, not proof of possession of the channel key.
            if (password != null) {
                if (!verifyChannelPassword(channelTag, password)) {
                    return false
                }
            } else {
                state.setPasswordPromptChannel(channelTag)
                state.setShowPasswordPrompt(true)
                return false
            }
        }
        
        // Join the channel
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.add(channelTag)
        state.setJoinedChannels(updatedChannels)
        
        // Set as creator if new channel
        if (!dataManager.channelCreators.containsKey(channelTag) && !state.getPasswordProtectedChannelsValue().contains(channelTag)) {
            dataManager.addChannelCreator(channelTag, myPeerID)
        }
        
        // Add ourselves as member
        dataManager.addChannelMember(channelTag, myPeerID)
        
        // Initialize channel messages if needed
        if (!state.getChannelMessagesValue().containsKey(channelTag)) {
            val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
            updatedChannelMessages[channelTag] = emptyList()
            state.setChannelMessages(updatedChannelMessages)
        }
        
        DebugLogger.log(
            action = "GROUP_OPEN",
            srcName = null,
            srcId = myPeerID,
            destName = channelTag,
            destId = channelTag,
            protocol = "BLE_MESH",
            content = if (password != null) "[password-protected]" else "[open]"
        )
        switchToChannel(channelTag)
        saveChannelData()
        return true
    }
    
    fun leaveChannel(channel: String) {
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.remove(channel)
        state.setJoinedChannels(updatedChannels)
        
        // Exit channel if currently in it
        if (state.getCurrentChannelValue() == channel) {
            state.setCurrentChannel(null)
        }
        
        // Cleanup
        messageManager.removeChannelMessages(channel)
        dataManager.removeChannelMembers(channel)
        channelKeys.remove(channel)
        channelPasswords.remove(channel)
        dataManager.removeChannelCreator(channel)
        
        saveChannelData()
    }
    
    fun switchToChannel(channel: String?) {
        state.setCurrentChannel(channel)
        state.setSelectedPrivateChatPeer(null)
        
        // Clear unread count
        channel?.let { ch ->
            messageManager.clearChannelUnreadCount(ch)
        }
    }
    
    // MARK: - Channel Password and Encryption
    
    private fun verifyChannelPassword(channel: String, password: String): Boolean {
        // Fail closed when no trusted commitment is available. In particular,
        // never treat the local "password protected" marker as proof of a password.
        val expectedCommitment = channelKeyCommitments[channel] ?: return false
        return try {
            val candidateKey = deriveChannelKey(password, channel)
            val matches = com.roman.zemzeme.security.ChannelKeyCommitment.matches(
                candidateKey.encoded,
                expectedCommitment
            )
            if (!matches) return false

            // Retain the verified key for this process so the caller can use it
            // for channel encryption. The password is not persisted.
            channelKeys[channel] = candidateKey
            channelPasswords.remove(channel)
            true
        } catch (_: Exception) {
            false
        }
    }
    
    private fun deriveChannelKey(password: String, channelName: String): SecretKeySpec {
        // PBKDF2 key derivation (same as iOS version)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            channelName.toByteArray(),
            100000, // 100,000 iterations (same as iOS)
            256 // 256-bit key
        )
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }
    
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return decryptChannelMessage(encryptedContent, channel, null)
    }
    
    private fun decryptChannelMessage(encryptedContent: ByteArray, channel: String, testKey: SecretKeySpec?): String? {
        val key = testKey ?: channelKeys[channel] ?: return null
        
        try {
            if (encryptedContent.size < 16) return null // 12 bytes IV + minimum ciphertext
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = encryptedContent.sliceArray(0..11)
            val ciphertext = encryptedContent.sliceArray(12 until encryptedContent.size)
            
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            
            val decryptedData = cipher.doFinal(ciphertext)
            return String(decryptedData, Charsets.UTF_8)
            
        } catch (e: Exception) {
            return null
        }
    }
    
    fun sendEncryptedChannelMessage(
        content: String, 
        mentions: List<String>, 
        channel: String, 
        senderNickname: String?, 
        myPeerID: String,
        onEncryptedPayload: (ByteArray) -> Unit,
        onFallback: () -> Unit
    ) {
        // TODO: REIMPLEMENT – REMOVED FOR NOW
        return
    }
    
    // MARK: - Channel Management
    
    fun addChannelMessage(channel: String, message: ZemzemeMessage, senderPeerID: String?) {
        messageManager.addChannelMessage(channel, message)
        
        // Track as channel member
        senderPeerID?.let { peerID ->
            dataManager.addChannelMember(channel, peerID)
        }
    }
    
    fun removeChannelMember(channel: String, peerID: String) {
        dataManager.removeChannelMember(channel, peerID)
    }
    
    fun cleanupDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        dataManager.cleanupAllDisconnectedMembers(connectedPeers, myPeerID)
    }
    
    // MARK: - Channel Information
    
    fun isChannelPasswordProtected(channel: String): Boolean {
        return state.getPasswordProtectedChannelsValue().contains(channel)
    }
    
    fun hasChannelKey(channel: String): Boolean {
        return channelKeys.containsKey(channel)
    }

    /**
     * Plaintext is never allowed when a channel is protected or key material exists.
     * This is shared by text and media paths until encrypted transport is complete.
     */
    fun canSendPlaintextToChannel(channel: String): Boolean {
        return !ChannelAccessPolicy.blocksPlaintextSend(
            isChannelPasswordProtected(channel),
            hasChannelKey(channel)
        )
    }
    
    fun getChannelPassword(channel: String): String? {
        return channelPasswords[channel]
    }
    
    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return dataManager.isChannelCreator(channel, peerID)
    }
    
    fun getJoinedChannelsList(): List<String> {
        return state.getJoinedChannelsValue().toList().sorted()
    }
    
    // MARK: - Data Persistence
    
    private fun saveChannelData() {
        dataManager.saveChannelData(state.getJoinedChannelsValue(), state.getPasswordProtectedChannelsValue())
    }
    
    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        return dataManager.loadChannelData()
    }
    
    // MARK: - Password Management
    
    fun hidePasswordPrompt() {
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)
    }

    fun setChannelPassword(channel: String, password: String): Boolean {
        if (channel.isBlank() || password.isEmpty()) return false

        return try {
            val derivedKey = deriveChannelKey(password, channel)
            val commitment = com.roman.zemzeme.security.ChannelKeyCommitment.create(derivedKey.encoded)

            // Persist the verifier before publishing the protected marker. If secure
            // storage fails, leave the channel unchanged rather than creating a lockout.
            if (!commitmentStore.put(channel, commitment)) return false

            channelPasswords[channel] = password
            channelKeys[channel] = derivedKey
            channelKeyCommitments[channel] = commitment

            state.setPasswordProtectedChannels(
                state.getPasswordProtectedChannelsValue().toMutableSet().apply { add(channel) }
            )
            dataManager.saveChannelData(
                state.getJoinedChannelsValue(),
                state.getPasswordProtectedChannelsValue()
            )
            true
        } catch (_: Exception) {
            false
        }
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllChannels() {
        state.setJoinedChannels(emptySet())
        state.setCurrentChannel(null)
        state.setPasswordProtectedChannels(emptySet())
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)
        
        channelKeys.clear()
        channelPasswords.clear()
        channelKeyCommitments.clear()
        commitmentStore.clear()
        retentionEnabledChannels.clear()
    }
}
