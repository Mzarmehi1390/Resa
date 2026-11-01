package com.roman.zemzeme.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runtime coordinator for one Android BluetoothGatt client connection.
 *
 * The adapter performs the actual write submission. A request is considered
 * complete only after the matching onCharacteristicWrite callback (or a
 * terminal failure). A timeout closes this connection's lane because Android
 * callbacks do not carry an application request id.
 */
internal class GattClientWriteDispatcher<K : Any>(
    scope: CoroutineScope,
    connectionGeneration: Long,
    private val adapter: Adapter<K>,
    maxOutstandingWrites: Int = DEFAULT_MAX_OUTSTANDING_WRITES,
    private val callbackTimeoutMillis: Long = DEFAULT_CALLBACK_TIMEOUT_MILLIS,
    private val onLaneInvalidated: () -> Unit = {}
) {
    companion object {
        const val DEFAULT_MAX_OUTSTANDING_WRITES = 64
        const val DEFAULT_CALLBACK_TIMEOUT_MILLIS = 10_000L
    }

    internal interface Adapter<K : Any> {
        /** Returns false only when Android synchronously rejects the submission. */
        fun submit(key: K, payload: ByteArray): Boolean
    }

    internal sealed interface Outcome {
        data object Sent : Outcome
        data class Failed(
            val reason: GattClientWriteQueueState.FailureReason,
            val status: Int? = null
        ) : Outcome
    }

    private data class Entry(
        val result: CompletableDeferred<Outcome>,
        val onComplete: ((Outcome) -> Unit)?
    )

    private val lock = Any()
    private val generation = connectionGeneration
    private val state = GattClientWriteQueueState<K>(
        connectionGeneration = generation,
        maxOutstandingWrites = maxOutstandingWrites
    )
    private val entries = HashMap<Long, Entry>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    init {
        require(callbackTimeoutMillis > 0) { "callbackTimeoutMillis must be positive" }
    }

    private val worker = scope.launch {
        for (ignored in wakeups) {
            while (true) {
                val request = synchronized(lock) {
                    if (closed) null else state.takeNext(connectionGeneration)
                } ?: break

                val entry = synchronized(lock) { entries[request.id] } ?: continue
                val accepted = try {
                    adapter.submit(request.key, request.payload)
                } catch (_: Exception) {
                    false
                }

                if (!accepted) {
                    val completion = synchronized(lock) {
                        state.onSubmissionRejected(request.key, connectionGeneration)
                    }
                    completeFromState(completion)
                    continue
                }

                val outcome = withTimeoutOrNull(callbackTimeoutMillis) {
                    entry.result.await()
                } ?: timeoutActiveWrite(entry)

                if (outcome.reasonIsTimeout()) {
                    break
                }
            }
        }
    }

    /**
     * Non-blocking bounded admission. Accepted means queued locally, not
     * delivered to or processed by the remote application.
     */
    fun enqueue(
        key: K,
        payload: ByteArray,
        onComplete: ((Outcome) -> Unit)? = null
    ): GattClientWriteQueueState.Admission {
        val admission = synchronized(lock) {
            if (closed) return GattClientWriteQueueState.Admission.Closed
            val result = state.enqueue(key, payload, stateGeneration())
            if (result is GattClientWriteQueueState.Admission.Accepted) {
                entries[result.requestId] = Entry(CompletableDeferred(), onComplete)
            }
            result
        }
        if (admission is GattClientWriteQueueState.Admission.Accepted) {
            wakeups.trySend(Unit)
        }
        return admission
    }

    /** Forward BluetoothGattCallback.onCharacteristicWrite for this GATT session. */
    fun onWriteCallback(key: K, status: Int, successStatus: Int, callbackGeneration: Long) {
        val completion = synchronized(lock) {
            state.onWriteCallback(key, status, successStatus, callbackGeneration)
        }
        completeFromState(completion)
    }

    /** Fail active and queued writes when this exact GATT connection disconnects. */
    fun onDisconnected(callbackGeneration: Long) {
        val failures = synchronized(lock) {
            if (closed) return
            closed = true
            state.onDisconnected(callbackGeneration)
        }
        completeAll(failures)
        wakeups.close()
    }

    /** Close the dispatcher during manager shutdown. */
    fun shutdown() {
        val failures = synchronized(lock) {
            if (closed) return
            closed = true
            state.close()
        }
        completeAll(failures)
        wakeups.close()
    }

    fun outstandingCount(): Int = synchronized(lock) { state.outstandingCount() }

    private fun timeoutActiveWrite(activeEntry: Entry): Outcome {
        val failures = synchronized(lock) {
            if (activeEntry.result.isCompleted) return activeEntry.result.getCompleted()
            closed = true
            state.onTimeout(stateGeneration())
        }
        completeAll(failures)
        wakeups.close()
        runCatching(onLaneInvalidated)
        return activeEntry.result.getCompleted()
    }

    private fun completeFromState(completion: GattClientWriteQueueState.Completion<K>) {
        when (completion) {
            is GattClientWriteQueueState.Completion.Succeeded ->
                completeEntry(completion.request.id, Outcome.Sent)
            is GattClientWriteQueueState.Completion.Failed ->
                completeEntry(
                    completion.request.id,
                    Outcome.Failed(completion.reason, completion.status)
                )
            GattClientWriteQueueState.Completion.Ignored -> Unit
        }
    }

    private fun completeAll(completions: List<GattClientWriteQueueState.Completion<K>>) {
        completions.forEach(::completeFromState)
    }

    private fun completeEntry(requestId: Long, outcome: Outcome) {
        val entry = synchronized(lock) { entries.remove(requestId) } ?: return
        if (entry.result.complete(outcome)) {
            runCatching { entry.onComplete?.invoke(outcome) }
        }
    }

    private fun Outcome?.reasonIsTimeout(): Boolean =
        this is Outcome.Failed && reason == GattClientWriteQueueState.FailureReason.TIMEOUT

    // The dispatcher owns one immutable generation; this accessor keeps every
    // state transition explicitly tied to that generation.
    private fun stateGeneration(): Long = generation
}
