package com.roman.zemzeme.mesh

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.roman.zemzeme.protocol.ZemzemePacket
import com.roman.zemzeme.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages GATT server operations, advertising, and server-side connections
 */
class BluetoothGattServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?,
    private val myPeerID: String
) {
    
    companion object {
        private const val TAG = "BluetoothGattServerManager"
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    @Volatile
    private var gattServer: BluetoothGattServer? = null
    @Volatile
    private var characteristic: BluetoothGattCharacteristic? = null
    private val lifecycleGeneration = AtomicLong(0L)
    private val serverLifecycleLock = Any()
    private var advertiseCallback: AdvertiseCallback? = null
    @Volatile
    private var notificationDispatcher: GattServerNotificationDispatcher<BluetoothDevice>? = null

    // State management
    @Volatile
    private var isActive = false

    /**
     * Disconnect a specific device (used by ConnectionManager to enforce overall limits)
     */
    fun disconnectDevice(device: BluetoothDevice) {
        try {
            gattServer?.cancelConnection(device)
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting device ${device.address}: ${e.message}")
        }
    }
    
    /**
     * Start GATT server
     */
    fun start(): Boolean {
        // Respect debug setting
        try {
            if (!com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value) {
                Log.i(TAG, "Server start skipped: GATT Server disabled in debug settings")
                return false
            }
        } catch (_: Exception) { }

        if (isActive) {
            Log.d(TAG, "GATT server already active; start is a no-op")
            return true
        }
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return false
        }
        
        val startGeneration = synchronized(serverLifecycleLock) {
            isActive = true
            lifecycleGeneration.incrementAndGet()
        }

        connectionScope.launch {
            setupGattServer(startGeneration)
            if (isCurrentServerGeneration(startGeneration)) {
                delay(300) // Brief delay to ensure GATT server is ready
                if (isCurrentServerGeneration(startGeneration)) startAdvertising()
            }
        }
        
        return true
    }
    
    /**
     * Stop GATT server
     */
    fun stop() {
        synchronized(serverLifecycleLock) {
            lifecycleGeneration.incrementAndGet()
            isActive = false
            notificationDispatcher?.shutdown()
            notificationDispatcher = null

            val server = gattServer
            try {
                val servers = connectionTracker.getConnectedDevices().values.filter { !it.isClient }
                servers.forEach { connection ->
                    try { server?.cancelConnection(connection.device) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }

            try {
                server?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing GATT server: ${e.message}")
            }
            gattServer = null
            characteristic = null
        }
        stopAdvertising()
        Log.i(TAG, "GATT server stopped")
    }

    /** Waits for the Android onNotificationSent callback, not just API admission. */
    suspend fun sendNotification(
        device: BluetoothDevice,
        payload: ByteArray,
        expectedServer: BluetoothGattServer
    ): Boolean {
        val dispatcher = synchronized(serverLifecycleLock) {
            if (gattServer !== expectedServer) null else notificationDispatcher
        } ?: return false
        return when (val outcome = dispatcher.send(device, payload)) {
            GattServerNotificationDispatcher.Outcome.Sent -> true
            is GattServerNotificationDispatcher.Outcome.Failed -> {
                Log.w(TAG, "GATT notification to ${device.address} failed: ${outcome.reason}, status=${outcome.status}")
                if (
                    outcome.reason == GattCallbackLaneState.FailureReason.TIMEOUT ||
                    outcome.reason == GattCallbackLaneState.FailureReason.DISCONNECTED
                ) {
                    scheduleServerRecovery(expectedServer)
                }
                false
            }
        }
    }

    /** Returns true when queued locally; it does not imply remote delivery. */
    fun enqueueNotification(
        device: BluetoothDevice,
        payload: ByteArray,
        expectedServer: BluetoothGattServer,
        onComplete: (GattServerNotificationDispatcher.Outcome) -> Unit
    ): Boolean {
        val dispatcher = synchronized(serverLifecycleLock) {
            if (gattServer !== expectedServer) null else notificationDispatcher
        } ?: return false
        return dispatcher.enqueue(device, payload, onComplete)
    }
    
    /**
     * Get GATT server instance
     */
    fun getGattServer(): BluetoothGattServer? = gattServer
    
    /**
     * Get characteristic instance
     */
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic
    
    /**
     * Setup GATT server with proper sequencing
     */
    @Suppress("DEPRECATION")
    private fun isCurrentServerGeneration(generation: Long): Boolean {
        return isActive && lifecycleGeneration.get() == generation
    }

    private fun setupGattServer(setupGeneration: Long) {
        if (!permissionManager.hasBluetoothPermissions()) return
        if (!isCurrentServerGeneration(setupGeneration)) return

        val callbackServer = AtomicReference<BluetoothGattServer?>()
        val callbackCharacteristic = AtomicReference<BluetoothGattCharacteristic?>()
        val dispatcher = GattServerNotificationDispatcher(
            connectionScope,
            object : GattServerNotificationDispatcher.Adapter<BluetoothDevice> {
                override fun submit(key: BluetoothDevice, payload: ByteArray): Boolean {
                    val server = callbackServer.get() ?: return false
                    val char = callbackCharacteristic.get() ?: return false
                    char.value = payload.copyOf()
                    return server.notifyCharacteristicChanged(key, char, false)
                }
            }
        )
        val callbackGeneration = dispatcher.currentServerGeneration()

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                dispatcher.onNotificationSent(
                    device,
                    status,
                    BluetoothGatt.GATT_SUCCESS,
                    callbackGeneration
                )
            }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    dispatcher.onPeerDisconnected(device, callbackGeneration)
                }
                // Reject callbacks belonging to a stopped or replaced server instance.
                if (!isCurrentServerGeneration(setupGeneration)) {
                    Log.d(TAG, "Server: Ignoring stale connection state change")
                    return
                }
                
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Server: Device connected ${device.address}")
                        
                        // Get best available RSSI (scan RSSI for server connections)
                        val rssi = connectionTracker.getBestRSSI(device.address) ?: Int.MIN_VALUE
                        
                        val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                            device = device,
                            rssi = rssi,
                            isClient = false
                        )
                        connectionTracker.addDeviceConnection(device.address, deviceConn)

                        connectionScope.launch {
                            delay(1000)
                            if (isActive) { // Check if still active
                                delegate?.onDeviceConnected(device)
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Server: Device disconnected ${device.address}")
                        connectionTracker.cleanupDeviceConnection(device.address)
                        // Notify delegate about device disconnection so higher layers can update direct flags
                        delegate?.onDeviceDisconnected(device)
                    }
                }
            }
            
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (!isCurrentServerGeneration(setupGeneration)) {
                    Log.d(TAG, "Server: Ignoring stale service added callback")
                    return
                }
                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Server: Service added successfully: ${service.uuid}")
                } else {
                    Log.e(TAG, "Server: Failed to add service: ${service.uuid}, status: $status")
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (!isCurrentServerGeneration(setupGeneration)) {
                    Log.d(TAG, "Server: Ignoring stale characteristic write")
                    return
                }
                
                if (characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                    Log.i(TAG, "Server: Received packet from ${device.address}, size: ${value.size} bytes")
                    val packet = ZemzemePacket.fromBinaryData(value)
                    if (packet != null) {
                        val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Server: Parsed packet type ${packet.type} from $peerID")
                        delegate?.onPacketReceived(packet, peerID, device)
                    } else {
                        Log.w(TAG, "Server: Failed to parse packet from ${device.address}, size: ${value.size} bytes")
                        Log.w(TAG, "Server: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (!isCurrentServerGeneration(setupGeneration)) {
                    Log.d(TAG, "Server: Ignoring stale descriptor write")
                    return
                }
                
                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    connectionTracker.addSubscribedDevice(device)

                    Log.d(TAG, "Server: Connection setup complete for ${device.address}")
                    connectionScope.launch {
                        delay(100)
                        if (isActive) { // Check if still active
                            delegate?.onDeviceConnected(device)
                        }
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        // Retire any previous instance under the same lock used by stop()
        // and server publication.
        synchronized(serverLifecycleLock) {
            if (!isCurrentServerGeneration(setupGeneration)) {
                dispatcher.shutdown()
                return
            }
            notificationDispatcher?.shutdown()
            notificationDispatcher = null
            gattServer?.let { server ->
                Log.d(TAG, "Cleaning up existing GATT server")
                try {
                    server.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing existing GATT server: ${e.message}")
                }
            }
            gattServer = null
            characteristic = null
        }
        
        // Small delay to ensure cleanup is complete
        Thread.sleep(100)
        
        if (!isCurrentServerGeneration(setupGeneration)) {
            dispatcher.shutdown()
            Log.d(TAG, "Server generation changed, skipping GATT server creation")
            return
        }
        
        // Create a fresh server/dispatcher pair. Callbacks from a closed
        // server retain their old dispatcher and can never complete new work.
        val openedServer = bluetoothManager.openGattServer(context, serverCallback)
        if (openedServer == null) {
            dispatcher.shutdown()
            Log.e(TAG, "Failed to open GATT server")
            return
        }
        if (!isCurrentServerGeneration(setupGeneration)) {
            openedServer.close()
            dispatcher.shutdown()
            Log.d(TAG, "Server stopped while opening GATT server; discarded stale instance")
            return
        }
        // Create characteristic before publishing the server so stop() cannot
        // race with partially initialized adapter state.
        val createdCharacteristic = BluetoothGattCharacteristic(
            AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val descriptor = BluetoothGattDescriptor(
            AppConstants.Mesh.Gatt.DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        createdCharacteristic.addDescriptor(descriptor)
        callbackCharacteristic.set(createdCharacteristic)

        val service = BluetoothGattService(
            AppConstants.Mesh.Gatt.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(createdCharacteristic)

        val published = synchronized(serverLifecycleLock) {
            if (!isCurrentServerGeneration(setupGeneration)) {
                false
            } else {
                callbackServer.set(openedServer)
                gattServer = openedServer
                characteristic = createdCharacteristic
                notificationDispatcher = dispatcher
                openedServer.addService(service)
                true
            }
        }
        if (!published) {
            openedServer.close()
            dispatcher.shutdown()
            Log.d(TAG, "Server stopped during publication; discarded stale instance")
            return
        }
        
        Log.i(TAG, "GATT server setup complete")
    }
    
    /**
     * Start advertising
     */
    @Suppress("DEPRECATION")
    private fun startAdvertising() {
        // Respect debug setting
        val enabled = try { com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true }

        // Guard conditions – never throw here to avoid crashing the app from a background coroutine
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.w(TAG, "Not starting advertising: missing Bluetooth permissions")
            return
        }
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Not starting advertising: bluetoothAdapter is null")
            return
        }
        if (!isActive) {
            Log.d(TAG, "Not starting advertising: manager not active")
            return
        }
        if (!enabled) {
            Log.i(TAG, "Not starting advertising: GATT Server disabled via debug settings")
            return
        }
        if (bleAdvertiser == null) {
            Log.w(TAG, "Not starting advertising: BLE advertiser not available on this device")
            return
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "Not starting advertising: multiple advertisement not supported on this device")
            return
        }

        val settings = powerManager.getAdvertiseSettings()
        
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
            
        // Add stable identity (first 8 bytes of peerID) to Scan Response
        // This allows scanners to deduplicate devices even if MAC address rotates
        val peerIDBytes = try {
            myPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray().take(8).toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
        
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID), peerIDBytes)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                val mode = try {
                    powerManager.getPowerInfo().split("Current Mode: ")[1].split("\n")[0]
                } catch (_: Exception) { "unknown" }
                Log.i(TAG, "Advertising started (power mode: $mode) with stable ID: ${peerIDBytes.joinToString("") { "%02x".format(it) }}")
            }
            
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed: $errorCode")
            }
        }
        
        try {
            bleAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException starting advertising (missing permission?): ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
        }
    }
    
    /**
     * Stop advertising
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        if (!permissionManager.hasBluetoothPermissions() || bleAdvertiser == null) return
        try {
            advertiseCallback?.let { cb -> bleAdvertiser.stopAdvertising(cb) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
    }
    
    /**
     * Restart advertising (for power mode changes)
     */
    fun restartAdvertising() {
        // Respect debug setting
        val enabled = try { com.roman.zemzeme.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true }
        if (!isActive || !enabled) {
            stopAdvertising()
            return
        }

        connectionScope.launch {
            stopAdvertising()
            delay(100)
            startAdvertising()
        }
    }
}
