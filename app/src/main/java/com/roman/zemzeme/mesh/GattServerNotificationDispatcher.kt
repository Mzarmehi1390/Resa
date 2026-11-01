package com.roman.zemzeme.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes server-side GATT notifications that share one mutable characteristic.
 *
 * The adapter must perform both characteristic mutation and the Android
 * notifyCharacteristicChanged submission in [Adapter.submit]. Completion is
 * reported only after the matching Android callback, not after submission.
 */
internal class GattServerNotificationDispatcher<K : Any>(
    scope: CoroutineScope,
    private val adapter: Adapter<K>,
    private val callbackTimeoutMillis: Long = 10_000L,
    private val lane: GattCallbackLaneState<K> = GattCallbackLaneState()
) {
    init {
        require(callbackTimeoutMillis > 0) { "callbackTimeoutMillis must be positive" }
    }

    internal interface Adapter<K : Any> {
        /** Returns false only when the platform synchronously rejects submission. */
        fun submit(key: K, payload: ByteArray): Boolean
    }

    internal sealed interface Outcome {
        data object Sent : Outcome
        data class Failed(
            val reason: GattCallbackLaneState.FailureReason,
            val status: Int? = null
        ) : Outcome
    }

    private data class Request<K : Any>(
        val key: K,
        val payload: ByteArray,
        val result: CompletableDeferred<Outcome>
    )

    private data class Active(
        val operationId: Long,
        val callbackResult: CompletableDeferred<Outcome>
    )

    private val lock = Any()
    private val requests = Channel<Request<K>>(Channel.UNLIMITED)
    private var active: Active? = null
    private var closed = false

    private val worker = scope.launch {
        try {
            for (request in requests) {
                process(request)
            }
        } finally {
            closeFromWorker()
            while (true) {
                val pending = requests.tryReceive().getOrNull() ?: break
                pending.result.complete(
                    Outcome.Failed(GattCallbackLaneState.FailureReason.SHUTDOWN)
                )
            }
        }
    }

    /**
     * Non-suspending admission for legacy synchronous routing APIs.
     * True means queued locally, not delivered to the remote peer.
     */
    fun enqueue(key: K, payload: ByteArray): Boolean {
        synchronized(lock) {
            if (closed) return false
            val result = CompletableDeferred<Outcome>()
            return requests.trySend(Request(key, payload.copyOf(), result)).isSuccess
        }
    }

    /** Caller cancellation does not cancel an already admitted platform operation. */
    suspend fun send(key: K, payload: ByteArray): Outcome {
        val result = CompletableDeferred<Outcome>()
        synchronized(lock) {
            if (closed) {
                return Outcome.Failed(GattCallbackLaneState.FailureReason.SHUTDOWN)
            }
        }
        return try {
            requests.send(Request(key, payload.copyOf(), result))
            result.await()
        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
            Outcome.Failed(GattCallbackLaneState.FailureReason.SHUTDOWN)
        }
    }

    /** Forward BluetoothGattServerCallback.onNotificationSent with its server generation. */
    fun onNotificationSent(key: K, status: Int, successStatus: Int, generation: Long) {
        synchronized(lock) {
            val event = lane.onCallback(key, status, successStatus, generation)
            when (event) {
                is GattCallbackLaneState.Event.Completed -> {
                    active?.takeIf { it.operationId == event.operationId }?.let {
                        it.callbackResult.complete(Outcome.Sent)
                        active = null
                    }
                }
                is GattCallbackLaneState.Event.Failed -> {
                    active?.takeIf { it.operationId == event.operationId }?.let {
                        it.callbackResult.complete(Outcome.Failed(event.reason, event.status))
                        active = null
                    }
                }
                else -> Unit
            }
        }
    }

    /** A disconnect of the peer with the outstanding notification fails the lane closed. */
    fun onPeerDisconnected(key: K, generation: Long) {
        synchronized(lock) {
            val event = lane.onDisconnected(key, generation)
            if (event is GattCallbackLaneState.Event.Failed) {
                active?.takeIf { it.operationId == event.operationId }?.let {
                    it.callbackResult.complete(Outcome.Failed(event.reason, event.status))
                    active = null
                }
            }
        }
    }

    /**
     * Call only after the old Android server is closed and a replacement callback
     * instance is installed. Late callbacks must keep carrying their old generation.
     */
    fun onServerReplaced(): Long = synchronized(lock) {
        check(!closed) { "The notification dispatcher has been shut down" }
        check(active == null) { "Cannot replace a GATT server with an active notification" }
        lane.resetForNewServer()
    }

    fun currentServerGeneration(): Long = lane.currentServerGeneration()

    fun shutdown() {
        synchronized(lock) {
            if (closed) return
            closed = true
            lane.close()
            active?.callbackResult?.complete(
                Outcome.Failed(GattCallbackLaneState.FailureReason.SHUTDOWN)
            )
            active = null
        }
        requests.close()
    }

    private suspend fun process(request: Request<K>) {
        val callbackResult = CompletableDeferred<Outcome>()
        val started = synchronized(lock) {
            if (closed) return request.result.completeShutdown()
            when (val event = lane.begin(request.key, lane.currentServerGeneration())) {
                is GattCallbackLaneState.Event.Started -> {
                    active = Active(event.operationId, callbackResult)
                    event
                }
                GattCallbackLaneState.Event.Closed ->
                    return request.result.completeFailure(GattCallbackLaneState.FailureReason.SHUTDOWN)
                else ->
                    return request.result.completeFailure(GattCallbackLaneState.FailureReason.SUBMISSION_REJECTED)
            }
        } as GattCallbackLaneState.Event.Started

        val submitted = try {
            adapter.submit(request.key, request.payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

        if (!submitted) {
            synchronized(lock) {
                lane.onSubmissionRejected(started.operationId)
                active?.takeIf { it.operationId == started.operationId }?.let {
                    active = null
                }
            }
            request.result.complete(
                Outcome.Failed(GattCallbackLaneState.FailureReason.SUBMISSION_REJECTED)
            )
            return
        }

        val callbackOutcome = withTimeoutOrNull(callbackTimeoutMillis) {
            callbackResult.await()
        } ?: synchronized(lock) {
            val event = lane.onTimeout(started.operationId)
            active?.takeIf { it.operationId == started.operationId }?.let { active = null }
            if (event is GattCallbackLaneState.Event.Failed) {
                Outcome.Failed(event.reason, event.status)
            } else {
                Outcome.Failed(GattCallbackLaneState.FailureReason.TIMEOUT)
            }
        }
        request.result.complete(callbackOutcome)
    }

    private fun closeFromWorker() {
        synchronized(lock) {
            if (closed) return
            closed = true
            lane.close()
            active?.callbackResult?.complete(
                Outcome.Failed(GattCallbackLaneState.FailureReason.SHUTDOWN)
            )
            active = null
        }
        requests.close()
    }

    private fun CompletableDeferred<Outcome>.completeShutdown() {
        complete(Outcome.Failed(GattCallbackLaneState.FailureReason.SHUTDOWN))
    }

    private fun CompletableDeferred<Outcome>.completeFailure(
        reason: GattCallbackLaneState.FailureReason
    ) {
        complete(Outcome.Failed(reason))
    }
}
