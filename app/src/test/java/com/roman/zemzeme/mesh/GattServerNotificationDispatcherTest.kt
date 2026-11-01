package com.roman.zemzeme.mesh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class GattServerNotificationDispatcherTest {

    private class FakeAdapter : GattServerNotificationDispatcher.Adapter<String> {
        data class Submission(val key: String, val payload: ByteArray)
        val submissions = Collections.synchronizedList(mutableListOf<Submission>())
        var accept: Boolean = true

        override fun submit(key: String, payload: ByteArray): Boolean {
            submissions += Submission(key, payload.copyOf())
            return accept
        }
    }

    private suspend fun awaitSubmissions(adapter: FakeAdapter, count: Int) {
        withTimeout(2_000) {
            while (adapter.submissions.size < count) delay(1)
        }
    }

    @Test
    fun serializesDifferentPeersUntilMatchingCallbackCompletes() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)
        val first = async { dispatcher.send("peer-a", byteArrayOf(1)) }
        awaitSubmissions(adapter, 1)
        val second = async { dispatcher.send("peer-b", byteArrayOf(2)) }
        delay(20)
        assertEquals(1, adapter.submissions.size)
        assertFalse(first.isCompleted)

        val generation = dispatcher.currentServerGeneration()
        dispatcher.onNotificationSent("peer-a", 0, 0, generation)
        assertEquals(GattServerNotificationDispatcher.Outcome.Sent, first.await())
        awaitSubmissions(adapter, 2)
        dispatcher.onNotificationSent("peer-b", 0, 0, generation)
        assertEquals(GattServerNotificationDispatcher.Outcome.Sent, second.await())
        dispatcher.shutdown()
        scope.cancel()
    }

    @Test
    fun callbackFailureIsReturnedToTheRequest() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)
        val request = async { dispatcher.send("peer-a", byteArrayOf(1)) }
        awaitSubmissions(adapter, 1)

        dispatcher.onNotificationSent("peer-a", 133, 0, dispatcher.currentServerGeneration())
        assertEquals(
            GattServerNotificationDispatcher.Outcome.Failed(
                GattCallbackLaneState.FailureReason.CALLBACK_FAILURE,
                133
            ),
            request.await()
        )
        dispatcher.shutdown()
        scope.cancel()
    }

    @Test
    fun synchronousSubmissionRejectionDoesNotWaitForCallback() = runBlocking {
        val adapter = FakeAdapter().apply { accept = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)

        assertEquals(
            GattServerNotificationDispatcher.Outcome.Failed(
                GattCallbackLaneState.FailureReason.SUBMISSION_REJECTED
            ),
            withTimeout(2_000) { dispatcher.send("peer-a", byteArrayOf(1)) }
        )
        dispatcher.shutdown()
        scope.cancel()
    }

    @Test
    fun timeoutClosesLaneUntilReplacementServerIsInstalled() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter, callbackTimeoutMillis = 30)

        assertEquals(
            GattServerNotificationDispatcher.Outcome.Failed(
                GattCallbackLaneState.FailureReason.TIMEOUT
            ),
            dispatcher.send("peer-a", byteArrayOf(1))
        )
        val oldGeneration = dispatcher.currentServerGeneration()
        val newGeneration = dispatcher.onServerReplaced()
        assertTrue(newGeneration > oldGeneration)

        val next = async { dispatcher.send("peer-a", byteArrayOf(2)) }
        awaitSubmissions(adapter, 2)
        dispatcher.onNotificationSent("peer-a", 0, 0, oldGeneration)
        delay(20)
        assertFalse(next.isCompleted)
        dispatcher.onNotificationSent("peer-a", 0, 0, newGeneration)
        assertEquals(GattServerNotificationDispatcher.Outcome.Sent, next.await())
        dispatcher.shutdown()
        scope.cancel()
    }

    @Test
    fun disconnectFailsActiveRequestAndClosesLane() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)
        val request = async { dispatcher.send("peer-a", byteArrayOf(1)) }
        awaitSubmissions(adapter, 1)

        dispatcher.onPeerDisconnected("peer-a", dispatcher.currentServerGeneration())
        assertEquals(
            GattServerNotificationDispatcher.Outcome.Failed(
                GattCallbackLaneState.FailureReason.DISCONNECTED
            ),
            request.await()
        )
        dispatcher.shutdown()
        scope.cancel()
    }

    @Test
    fun shutdownResolvesActiveAndQueuedRequests() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)
        val first = async { dispatcher.send("peer-a", byteArrayOf(1)) }
        awaitSubmissions(adapter, 1)
        val second = async { dispatcher.send("peer-b", byteArrayOf(2)) }
        delay(20)

        dispatcher.shutdown()
        val expected = GattServerNotificationDispatcher.Outcome.Failed(
            GattCallbackLaneState.FailureReason.SHUTDOWN
        )
        assertEquals(expected, first.await())
        assertEquals(expected, second.await())
        scope.cancel()
    }

    @Test
    fun cancellingCallerDoesNotCancelAdmittedNotification() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)
        val caller = async { dispatcher.send("peer-a", byteArrayOf(3)) }
        awaitSubmissions(adapter, 1)
        caller.cancel()
        delay(20)

        dispatcher.onNotificationSent("peer-a", 0, 0, dispatcher.currentServerGeneration())
        val next = async { dispatcher.send("peer-b", byteArrayOf(4)) }
        awaitSubmissions(adapter, 2)
        dispatcher.onNotificationSent("peer-b", 0, 0, dispatcher.currentServerGeneration())
        assertEquals(GattServerNotificationDispatcher.Outcome.Sent, next.await())
        dispatcher.shutdown()
        scope.cancel()
    }

    @Test
    fun enqueueAdmitsWithoutClaimingRemoteDelivery() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)

        assertTrue(dispatcher.enqueue("peer-a", byteArrayOf(5)))
        awaitSubmissions(adapter, 1)
        dispatcher.shutdown()
        scope.cancel()
    }

    @Test
    fun enqueueCompletionReportsOnlyAfterPlatformCallback() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)
        val completion = CompletableDeferred<GattServerNotificationDispatcher.Outcome>()

        assertTrue(dispatcher.enqueue("peer-a", byteArrayOf(6)) { completion.complete(it) })
        awaitSubmissions(adapter, 1)
        assertFalse(completion.isCompleted)

        dispatcher.onNotificationSent("peer-a", 0, 0, dispatcher.currentServerGeneration())
        assertEquals(GattServerNotificationDispatcher.Outcome.Sent, completion.await())
        dispatcher.shutdown()
        scope.cancel()
    }

    @Test
    fun shutdownCompletesQueuedCallbackExactlyOnce() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)
        val completion = CompletableDeferred<GattServerNotificationDispatcher.Outcome>()
        var callbackCount = 0

        assertTrue(dispatcher.enqueue("peer-a", byteArrayOf(8)) {
            callbackCount += 1
            completion.complete(it)
        })
        awaitSubmissions(adapter, 1)
        dispatcher.shutdown()

        assertEquals(
            GattServerNotificationDispatcher.Outcome.Failed(
                GattCallbackLaneState.FailureReason.SHUTDOWN
            ),
            completion.await()
        )
        delay(20)
        assertEquals(1, callbackCount)
        scope.cancel()
    }

    @Test
    fun enqueueRejectsAfterShutdown() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)
        dispatcher.shutdown()

        assertFalse(dispatcher.enqueue("peer-a", byteArrayOf(5)))
        scope.cancel()
    }

    @Test
    fun payloadIsCopiedBeforeItIsQueued() = runBlocking {
        val adapter = FakeAdapter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = GattServerNotificationDispatcher(scope, adapter)
        val payload = byteArrayOf(7)
        val request = async { dispatcher.send("peer-a", payload) }
        awaitSubmissions(adapter, 1)
        payload[0] = 9

        assertEquals(7.toByte(), adapter.submissions.single().payload.single())
        dispatcher.onNotificationSent("peer-a", 0, 0, dispatcher.currentServerGeneration())
        assertEquals(GattServerNotificationDispatcher.Outcome.Sent, request.await())
        dispatcher.shutdown()
        scope.cancel()
    }
}
