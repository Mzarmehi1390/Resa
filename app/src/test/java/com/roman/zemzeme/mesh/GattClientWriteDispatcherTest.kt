package com.roman.zemzeme.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GattClientWriteDispatcherTest {

    @Test
    fun serializesWritesUntilMatchingCallbackCompletes() = runBlocking {
        val submitted = AtomicInteger()
        val firstSubmitted = CompletableDeferred<ByteArray>()
        val secondSubmitted = CompletableDeferred<ByteArray>()
        val firstOutcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()
        val secondOutcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()

        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 21L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    when (submitted.incrementAndGet()) {
                        1 -> firstSubmitted.complete(payload)
                        2 -> secondSubmitted.complete(payload)
                    }
                    return true
                }
            },
            callbackTimeoutMillis = 1_000L
        )

        assertTrue(dispatcher.enqueue("characteristic", byteArrayOf(1)) {
            firstOutcome.complete(it)
        } is GattClientWriteQueueState.Admission.Accepted)
        assertTrue(dispatcher.enqueue("characteristic", byteArrayOf(2)) {
            secondOutcome.complete(it)
        } is GattClientWriteQueueState.Admission.Accepted)

        assertTrue(firstSubmitted.await().contentEquals(byteArrayOf(1)))
        assertFalse(secondSubmitted.isCompleted)

        dispatcher.onWriteCallback("characteristic", 0, 0, 21L)
        assertTrue(secondSubmitted.await().contentEquals(byteArrayOf(2)))
        dispatcher.onWriteCallback("characteristic", 0, 0, 21L)

        assertEquals(GattClientWriteDispatcher.Outcome.Sent, firstOutcome.await())
        assertEquals(GattClientWriteDispatcher.Outcome.Sent, secondOutcome.await())
        dispatcher.shutdown()
    }

    @Test
    fun rejectsAdmissionWhenBoundedQueueIsFull() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 22L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    submitted.complete(Unit)
                    return true
                }
            },
            maxOutstandingWrites = 1,
            callbackTimeoutMillis = 1_000L
        )

        assertTrue(dispatcher.enqueue("characteristic", byteArrayOf(1)) {}
            is GattClientWriteQueueState.Admission.Accepted)
        submitted.await()
        assertEquals(
            GattClientWriteQueueState.Admission.Full,
            dispatcher.enqueue("characteristic", byteArrayOf(2)) {}
        )
        dispatcher.onDisconnected(22L)
    }

    @Test
    fun timeoutFailsLaneAndRequestsConnectionInvalidation() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        val invalidated = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()
        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 23L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    submitted.complete(Unit)
                    return true
                }
            },
            callbackTimeoutMillis = 30L,
            onLaneInvalidated = { invalidated.complete(Unit) }
        )

        dispatcher.enqueue("characteristic", byteArrayOf(1)) { outcome.complete(it) }
        submitted.await()
        assertEquals(
            GattClientWriteDispatcher.Outcome.Failed(GattClientWriteQueueState.FailureReason.TIMEOUT),
            withTimeout(1_000L) { outcome.await() }
        )
        withTimeout(1_000L) { invalidated.await() }
        assertEquals(0, dispatcher.outstandingCount())
        dispatcher.shutdown()
    }

    @Test
    fun disconnectFailsActiveAndQueuedRequests() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        val firstOutcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()
        val secondOutcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()
        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 24L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    submitted.complete(Unit)
                    return true
                }
            },
            callbackTimeoutMillis = 1_000L
        )

        dispatcher.enqueue("characteristic", byteArrayOf(1)) { firstOutcome.complete(it) }
        dispatcher.enqueue("characteristic", byteArrayOf(2)) { secondOutcome.complete(it) }
        submitted.await()
        dispatcher.onDisconnected(24L)

        val expected = GattClientWriteDispatcher.Outcome.Failed(
            GattClientWriteQueueState.FailureReason.DISCONNECTED
        )
        assertEquals(expected, firstOutcome.await())
        assertEquals(expected, secondOutcome.await())
    }

    @Test
    fun staleGenerationCallbackCannotCompleteActiveRequest() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()
        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 25L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    submitted.complete(Unit)
                    return true
                }
            },
            callbackTimeoutMillis = 1_000L
        )

        dispatcher.enqueue("characteristic", byteArrayOf(1)) { outcome.complete(it) }
        submitted.await()
        dispatcher.onWriteCallback("characteristic", 0, 0, 24L)
        assertFalse(outcome.isCompleted)
        dispatcher.onDisconnected(25L)
        assertEquals(
            GattClientWriteDispatcher.Outcome.Failed(GattClientWriteQueueState.FailureReason.DISCONNECTED),
            outcome.await()
        )
    }

    @Test
    fun cancellingParentScopeFailsActiveAndQueuedRequests() = runBlocking {
        val parentJob = Job()
        val dispatcherScope = CoroutineScope(coroutineContext + parentJob)
        val submitted = CompletableDeferred<Unit>()
        val firstOutcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()
        val secondOutcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()
        val dispatcher = GattClientWriteDispatcher(
            scope = dispatcherScope,
            connectionGeneration = 26L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    submitted.complete(Unit)
                    return true
                }
            },
            callbackTimeoutMillis = 5_000L
        )

        dispatcher.enqueue("characteristic", byteArrayOf(1)) { firstOutcome.complete(it) }
        dispatcher.enqueue("characteristic", byteArrayOf(2)) { secondOutcome.complete(it) }
        submitted.await()
        parentJob.cancel()

        val expected = GattClientWriteDispatcher.Outcome.Failed(
            GattClientWriteQueueState.FailureReason.SHUTDOWN
        )
        assertEquals(expected, withTimeout(1_000L) { firstOutcome.await() })
        assertEquals(expected, withTimeout(1_000L) { secondOutcome.await() })
        assertEquals(0, dispatcher.outstandingCount())
    }


    @Test
    fun adapterCancellationClosesDispatcherAndFailsRequest() = runBlocking {
        val outcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()
        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 27L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    throw CancellationException("test cancellation")
                }
            },
            callbackTimeoutMillis = 5_000L
        )

        dispatcher.enqueue("characteristic", byteArrayOf(1)) { outcome.complete(it) }
        assertEquals(
            GattClientWriteDispatcher.Outcome.Failed(
                GattClientWriteQueueState.FailureReason.SHUTDOWN
            ),
            withTimeout(1_000L) { outcome.await() }
        )
        assertEquals(0, dispatcher.outstandingCount())
    }

    @Test
    fun enqueueAndAwaitReturnsOnlyAfterMatchingCallback() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 28L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    submitted.complete(Unit)
                    return true
                }
            },
            callbackTimeoutMillis = 1_000L
        )

        val result = async { dispatcher.enqueueAndAwait("characteristic", byteArrayOf(7)) }
        submitted.await()
        assertFalse(result.isCompleted)
        dispatcher.onWriteCallback("characteristic", 0, 0, 28L)
        assertEquals(GattClientWriteDispatcher.Outcome.Sent, result.await())
        dispatcher.shutdown()
    }

    @Test
    fun enqueueAndAwaitReturnsNullWhenQueueAdmissionIsRejected() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 29L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    submitted.complete(Unit)
                    return true
                }
            },
            maxOutstandingWrites = 1,
            callbackTimeoutMillis = 1_000L
        )

        val active = async { dispatcher.enqueueAndAwait("characteristic", byteArrayOf(1)) }
        submitted.await()
        assertEquals(null, dispatcher.enqueueAndAwait("characteristic", byteArrayOf(2)))
        dispatcher.onDisconnected(29L)
        assertEquals(
            GattClientWriteDispatcher.Outcome.Failed(GattClientWriteQueueState.FailureReason.DISCONNECTED),
            active.await()
        )
    }


    @Test
    fun enqueueAndAwaitReturnsFailureWhenAndroidCallbackReportsError() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 30L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    submitted.complete(Unit)
                    return true
                }
            },
            callbackTimeoutMillis = 1_000L
        )

        val result = async {
            dispatcher.enqueueAndAwait("characteristic", byteArrayOf(9))
        }
        submitted.await()
        dispatcher.onWriteCallback("characteristic", 133, 0, 30L)

        assertEquals(
            GattClientWriteDispatcher.Outcome.Failed(
                GattClientWriteQueueState.FailureReason.CALLBACK_FAILURE,
                status = 133
            ),
            result.await()
        )
        dispatcher.shutdown()
    }

    @Test
    fun enqueueCompletionCallbackReceivesFailureWhenAndroidCallbackReportsError() = runBlocking {
        val submitted = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<GattClientWriteDispatcher.Outcome>()
        val dispatcher = GattClientWriteDispatcher(
            scope = this,
            connectionGeneration = 31L,
            adapter = object : GattClientWriteDispatcher.Adapter<String> {
                override fun submit(key: String, payload: ByteArray): Boolean {
                    submitted.complete(Unit)
                    return true
                }
            },
            callbackTimeoutMillis = 1_000L
        )

        dispatcher.enqueue("characteristic", byteArrayOf(10)) { outcome.complete(it) }
        submitted.await()
        dispatcher.onWriteCallback("characteristic", 8, 0, 31L)

        assertEquals(
            GattClientWriteDispatcher.Outcome.Failed(
                GattClientWriteQueueState.FailureReason.CALLBACK_FAILURE,
                status = 8
            ),
            outcome.await()
        )
        dispatcher.shutdown()
    }

}
