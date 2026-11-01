package com.roman.zemzeme.mesh

/**
 * Platform-independent single-flight state for one GATT client connection attempt.
 *
 * Android write callbacks do not carry an application operation id. The runtime
 * owner must therefore serialize writes for this GATT instance and pass the
 * captured connection generation to every callback.
 */
internal class GattClientWriteLaneState<K : Any>(
    private val connectionGeneration: Long
) {
    enum class Status { READY, IN_FLIGHT, CLOSED }

    sealed interface Event {
        data object Started : Event
        data object Busy : Event
        data object Closed : Event
        data object Ignored : Event
        data object Completed : Event
        data class Failed(val reason: FailureReason, val status: Int? = null) : Event
    }

    enum class FailureReason {
        CALLBACK_FAILURE,
        SUBMISSION_REJECTED,
        DISCONNECTED,
        SHUTDOWN
    }

    private var status = Status.READY
    private var activeKey: K? = null

    @Synchronized
    fun status(): Status = status

    @Synchronized
    fun begin(key: K, callbackGeneration: Long): Event {
        if (callbackGeneration != connectionGeneration) return Event.Ignored
        if (status == Status.CLOSED) return Event.Closed
        if (status == Status.IN_FLIGHT) return Event.Busy
        activeKey = key
        status = Status.IN_FLIGHT
        return Event.Started
    }

    @Synchronized
    fun onWriteCallback(
        key: K,
        callbackStatus: Int,
        successStatus: Int,
        callbackGeneration: Long
    ): Event {
        if (callbackGeneration != connectionGeneration) return Event.Ignored
        if (status != Status.IN_FLIGHT || activeKey != key) return Event.Ignored
        activeKey = null
        status = Status.READY
        return if (callbackStatus == successStatus) {
            Event.Completed
        } else {
            Event.Failed(FailureReason.CALLBACK_FAILURE, callbackStatus)
        }
    }

    @Synchronized
    fun onSubmissionRejected(key: K, callbackGeneration: Long): Event {
        if (callbackGeneration != connectionGeneration) return Event.Ignored
        if (status != Status.IN_FLIGHT || activeKey != key) return Event.Ignored
        activeKey = null
        status = Status.READY
        return Event.Failed(FailureReason.SUBMISSION_REJECTED)
    }

    @Synchronized
    fun onDisconnected(callbackGeneration: Long): Event {
        if (callbackGeneration != connectionGeneration) return Event.Ignored
        if (status == Status.CLOSED) return Event.Closed
        activeKey = null
        status = Status.CLOSED
        return Event.Failed(FailureReason.DISCONNECTED)
    }

    @Synchronized
    fun close(): Event {
        if (status == Status.CLOSED) return Event.Closed
        val hadActiveWrite = status == Status.IN_FLIGHT
        activeKey = null
        status = Status.CLOSED
        return if (hadActiveWrite) Event.Failed(FailureReason.SHUTDOWN) else Event.Closed
    }
}
