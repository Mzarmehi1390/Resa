package com.roman.zemzeme.mesh

/**
 * Correlates the single outstanding server notification with Android's
 * onNotificationSent callback.
 *
 * Android does not provide an operation id in that callback. Consequently
 * this gate deliberately allows only one in-flight notification and becomes
 * unavailable after an ambiguous timeout/disconnect until the owning GATT
 * server is recreated and [resetForNewServer] is called.
 *
 * This class is platform-independent so its state transitions can be tested
 * without Android Bluetooth objects.
 */
internal class GattCallbackLaneState<K : Any> {

    enum class LaneStatus {
        READY,
        IN_FLIGHT,
        CLOSED
    }

    sealed interface Event {
        data class Started(val operationId: Long) : Event
        data class Completed(val operationId: Long, val status: Int) : Event
        data class Failed(val operationId: Long, val reason: FailureReason, val status: Int? = null) : Event
        data object Busy : Event
        data object Closed : Event
        data object Ignored : Event
    }

    enum class FailureReason {
        CALLBACK_FAILURE,
        TIMEOUT,
        DISCONNECTED,
        SHUTDOWN
    }

    private data class Active<K>(
        val operationId: Long,
        val key: K
    )

    private var status: LaneStatus = LaneStatus.READY
    private var nextOperationId = 1L
    private var active: Active<K>? = null

    @Synchronized
    fun status(): LaneStatus = status

    /**
     * Starts tracking one platform operation. The caller must invoke the
     * Android API only after receiving Started.
     */
    @Synchronized
    fun begin(key: K): Event {
        if (status == LaneStatus.CLOSED) return Event.Closed
        if (active != null) return Event.Busy

        val operationId = nextOperationId++
        active = Active(operationId, key)
        status = LaneStatus.IN_FLIGHT
        return Event.Started(operationId)
    }

    /**
     * Completes the current operation only when the callback's peer key
     * matches. Android's callback has no operation id, so FIFO single-flight
     * is a required invariant of the owner.
     */
    @Synchronized
    fun onCallback(key: K, callbackStatus: Int, successStatus: Int): Event {
        val current = active ?: return Event.Ignored
        if (current.key != key) return Event.Ignored

        active = null
        status = LaneStatus.READY
        return if (callbackStatus == successStatus) {
            Event.Completed(current.operationId, callbackStatus)
        } else {
            Event.Failed(
                current.operationId,
                FailureReason.CALLBACK_FAILURE,
                callbackStatus
            )
        }
    }

    /**
     * A timeout is ambiguous: a late callback has no operation id and could
     * otherwise be mistaken for the next request. Fail closed until the
     * Android GATT server instance is replaced.
     */
    @Synchronized
    fun onTimeout(operationId: Long): Event {
        val current = active ?: return Event.Ignored
        if (current.operationId != operationId) return Event.Ignored

        active = null
        status = LaneStatus.CLOSED
        return Event.Failed(operationId, FailureReason.TIMEOUT)
    }

    /**
     * Disconnecting the peer with the outstanding notification is also
     * terminal for this server instance; the callback cannot carry a
     * connection-generation token to disambiguate a rapid reconnect.
     */
    @Synchronized
    fun onDisconnected(key: K): Event {
        val current = active ?: return Event.Ignored
        if (current.key != key) return Event.Ignored

        active = null
        status = LaneStatus.CLOSED
        return Event.Failed(current.operationId, FailureReason.DISCONNECTED)
    }

    @Synchronized
    fun close(): Event {
        val current = active
        active = null
        status = LaneStatus.CLOSED
        return current?.let {
            Event.Failed(it.operationId, FailureReason.SHUTDOWN)
        } ?: Event.Closed
    }

    /**
     * Must only be called after the previous BluetoothGattServer has been
     * closed and a new server callback instance is installed.
     */
    @Synchronized
    fun resetForNewServer() {
        active = null
        status = LaneStatus.READY
    }
}
