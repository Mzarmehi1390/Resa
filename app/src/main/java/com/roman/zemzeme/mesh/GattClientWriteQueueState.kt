package com.roman.zemzeme.mesh

import java.util.ArrayDeque

/**
 * Bounded, platform-independent FIFO state for one GATT client connection.
 *
 * This class only models admission and single-flight callback correlation. It
 * does not submit Android writes or provide remote-delivery acknowledgement.
 */
internal class GattClientWriteQueueState<K : Any>(
    private val connectionGeneration: Long,
    private val maxOutstandingWrites: Int
) {
    init {
        require(maxOutstandingWrites > 0) { "maxOutstandingWrites must be positive" }
    }

    data class Request<K : Any>(
        val id: Long,
        val key: K,
        val payload: ByteArray
    )

    sealed interface Admission {
        data class Accepted(val requestId: Long) : Admission
        data object Full : Admission
        data object Closed : Admission
        data object Ignored : Admission
    }

    enum class FailureReason {
        CALLBACK_FAILURE,
        SUBMISSION_REJECTED,
        DISCONNECTED,
        TIMEOUT,
        SHUTDOWN
    }

    sealed interface Completion<out K : Any> {
        data class Succeeded<K : Any>(val request: Request<K>) : Completion<K>
        data class Failed<K : Any>(
            val request: Request<K>,
            val reason: FailureReason,
            val status: Int? = null
        ) : Completion<K>
        data object Ignored : Completion<Nothing>
    }

    private val pending = ArrayDeque<Request<K>>()
    private var nextRequestId = 1L
    private var active: Request<K>? = null
    private var closed = false

    @Synchronized
    fun outstandingCount(): Int = pending.size + if (active == null) 0 else 1

    @Synchronized
    fun isClosed(): Boolean = closed

    /** Capacity includes both the active write and queued writes. */
    @Synchronized
    fun enqueue(key: K, payload: ByteArray, callbackGeneration: Long): Admission {
        if (callbackGeneration != connectionGeneration) return Admission.Ignored
        if (closed) return Admission.Closed
        if (outstandingCount() >= maxOutstandingWrites) return Admission.Full

        val id = nextRequestId++
        pending.addLast(Request(id, key, payload.copyOf()))
        return Admission.Accepted(id)
    }

    /** Returns the next FIFO request only when no write is currently in flight. */
    @Synchronized
    fun takeNext(callbackGeneration: Long): Request<K>? {
        if (callbackGeneration != connectionGeneration || closed || active != null) return null
        if (pending.isEmpty()) return null
        return pending.removeFirst().also { active = it }
    }

    @Synchronized
    fun onWriteCallback(
        key: K,
        callbackStatus: Int,
        successStatus: Int,
        callbackGeneration: Long
    ): Completion<K> {
        if (callbackGeneration != connectionGeneration) return Completion.Ignored
        val request = active ?: return Completion.Ignored
        if (request.key != key) return Completion.Ignored

        active = null
        return if (callbackStatus == successStatus) {
            Completion.Succeeded(request)
        } else {
            Completion.Failed(request, FailureReason.CALLBACK_FAILURE, callbackStatus)
        }
    }

    @Synchronized
    fun onSubmissionRejected(key: K, callbackGeneration: Long): Completion<K> {
        if (callbackGeneration != connectionGeneration) return Completion.Ignored
        val request = active ?: return Completion.Ignored
        if (request.key != key) return Completion.Ignored
        active = null
        return Completion.Failed(request, FailureReason.SUBMISSION_REJECTED)
    }

    /** A callback timeout is ambiguous: close the lane so a late callback cannot
     * be mistaken for completion of a later request on this GATT connection. */
    @Synchronized
    fun onTimeout(callbackGeneration: Long): List<Completion<K>> {
        if (callbackGeneration != connectionGeneration || closed || active == null) return emptyList()
        return closeAndFail(FailureReason.TIMEOUT)
    }

    /** Fail the active request and every queued request; stale disconnects do nothing. */
    @Synchronized
    fun onDisconnected(callbackGeneration: Long): List<Completion<K>> {
        if (callbackGeneration != connectionGeneration || closed) return emptyList()
        return closeAndFail(FailureReason.DISCONNECTED)
    }

    @Synchronized
    fun close(): List<Completion<K>> {
        if (closed) return emptyList()
        return closeAndFail(FailureReason.SHUTDOWN)
    }

    private fun closeAndFail(reason: FailureReason): List<Completion<K>> {
        closed = true
        val failures = ArrayList<Completion<K>>(outstandingCount())
        active?.let { failures += Completion.Failed(it, reason) }
        active = null
        while (pending.isNotEmpty()) {
            failures += Completion.Failed(pending.removeFirst(), reason)
        }
        return failures
    }
}
