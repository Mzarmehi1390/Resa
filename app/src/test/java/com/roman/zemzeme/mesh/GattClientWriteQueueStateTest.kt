package com.roman.zemzeme.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattClientWriteQueueStateTest {

    @Test
    fun admitsInFifoOrderAndAllowsOnlyOneActiveWrite() {
        val queue = GattClientWriteQueueState<String>(7L, maxOutstandingWrites = 3)
        assertEquals(GattClientWriteQueueState.Admission.Accepted(1L), queue.enqueue("gatt-a", byteArrayOf(1), 7L))
        assertEquals(GattClientWriteQueueState.Admission.Accepted(2L), queue.enqueue("gatt-a", byteArrayOf(2), 7L))

        val first = queue.takeNext(7L)!!
        assertEquals(1L, first.id)
        assertNull(queue.takeNext(7L))
        assertEquals(
            GattClientWriteQueueState.Completion.Succeeded(first),
            queue.onWriteCallback("gatt-a", 0, 0, 7L)
        )

        val second = queue.takeNext(7L)!!
        assertEquals(2L, second.id)
    }

    @Test
    fun rejectsAdmissionAtCapacityWithoutDroppingExistingRequests() {
        val queue = GattClientWriteQueueState<String>(2L, maxOutstandingWrites = 2)
        queue.enqueue("gatt-a", byteArrayOf(1), 2L)
        queue.enqueue("gatt-a", byteArrayOf(2), 2L)

        assertEquals(GattClientWriteQueueState.Admission.Full, queue.enqueue("gatt-a", byteArrayOf(3), 2L))
        assertEquals(2, queue.outstandingCount())
        assertArrayEquals(byteArrayOf(1), queue.takeNext(2L)!!.payload)
    }

    @Test
    fun copiesPayloadOnAdmission() {
        val queue = GattClientWriteQueueState<String>(3L, maxOutstandingWrites = 2)
        val source = byteArrayOf(4, 5)
        queue.enqueue("gatt-a", source, 3L)
        source[0] = 99

        assertArrayEquals(byteArrayOf(4, 5), queue.takeNext(3L)!!.payload)
    }

    @Test
    fun staleGenerationCannotAdmitTakeOrCompleteWrites() {
        val queue = GattClientWriteQueueState<String>(4L, maxOutstandingWrites = 2)
        assertEquals(GattClientWriteQueueState.Admission.Ignored, queue.enqueue("gatt-a", byteArrayOf(1), 3L))
        queue.enqueue("gatt-a", byteArrayOf(2), 4L)

        assertNull(queue.takeNext(3L))
        val active = queue.takeNext(4L)!!
        assertEquals(GattClientWriteQueueState.Completion.Ignored, queue.onWriteCallback("gatt-a", 0, 0, 3L))
        assertEquals(1L, active.id)
        assertEquals(1, queue.outstandingCount())
    }

    @Test
    fun wrongConnectionCallbackDoesNotReleaseActiveWrite() {
        val queue = GattClientWriteQueueState<String>(5L, maxOutstandingWrites = 2)
        queue.enqueue("gatt-a", byteArrayOf(1), 5L)
        queue.takeNext(5L)

        assertEquals(GattClientWriteQueueState.Completion.Ignored, queue.onWriteCallback("gatt-b", 0, 0, 5L))
        assertEquals(1, queue.outstandingCount())
    }

    @Test
    fun callbackFailureReleasesOnlyActiveRequestAndPreservesQueueOrder() {
        val queue = GattClientWriteQueueState<String>(6L, maxOutstandingWrites = 3)
        queue.enqueue("gatt-a", byteArrayOf(1), 6L)
        queue.enqueue("gatt-a", byteArrayOf(2), 6L)
        val first = queue.takeNext(6L)!!

        assertEquals(
            GattClientWriteQueueState.Completion.Failed(
                first,
                GattClientWriteQueueState.FailureReason.CALLBACK_FAILURE,
                133
            ),
            queue.onWriteCallback("gatt-a", 133, 0, 6L)
        )
        assertArrayEquals(byteArrayOf(2), queue.takeNext(6L)!!.payload)
    }

    @Test
    fun synchronousSubmissionRejectionReleasesActiveRequest() {
        val queue = GattClientWriteQueueState<String>(8L, maxOutstandingWrites = 2)
        queue.enqueue("gatt-a", byteArrayOf(1), 8L)
        val active = queue.takeNext(8L)!!

        assertEquals(
            GattClientWriteQueueState.Completion.Failed(
                active,
                GattClientWriteQueueState.FailureReason.SUBMISSION_REJECTED
            ),
            queue.onSubmissionRejected("gatt-a", 8L)
        )
        assertEquals(0, queue.outstandingCount())
    }

    @Test
    fun disconnectFailsActiveAndQueuedRequestsAndClosesQueue() {
        val queue = GattClientWriteQueueState<String>(9L, maxOutstandingWrites = 3)
        queue.enqueue("gatt-a", byteArrayOf(1), 9L)
        queue.enqueue("gatt-a", byteArrayOf(2), 9L)
        queue.takeNext(9L)

        val failed = queue.onDisconnected(9L)
        assertEquals(2, failed.size)
        assertTrue(failed.all {
            it is GattClientWriteQueueState.Completion.Failed &&
                it.reason == GattClientWriteQueueState.FailureReason.DISCONNECTED
        })
        assertTrue(queue.isClosed())
        assertEquals(GattClientWriteQueueState.Admission.Closed, queue.enqueue("gatt-a", byteArrayOf(3), 9L))
    }

    @Test
    fun staleDisconnectDoesNotCloseQueue() {
        val queue = GattClientWriteQueueState<String>(10L, maxOutstandingWrites = 2)
        assertTrue(queue.onDisconnected(9L).isEmpty())
        assertFalse(queue.isClosed())
    }

    @Test
    fun shutdownFailsOutstandingRequestsExactlyOnce() {
        val queue = GattClientWriteQueueState<String>(11L, maxOutstandingWrites = 2)
        queue.enqueue("gatt-a", byteArrayOf(1), 11L)
        queue.enqueue("gatt-a", byteArrayOf(2), 11L)
        val first = queue.takeNext(11L)!!

        val failed = queue.close()
        assertEquals(2, failed.size)
        assertTrue(failed.all {
            it is GattClientWriteQueueState.Completion.Failed &&
                it.reason == GattClientWriteQueueState.FailureReason.SHUTDOWN
        })
        assertTrue(queue.close().isEmpty())
        assertEquals(GattClientWriteQueueState.Completion.Ignored, queue.onWriteCallback("gatt-a", 0, 0, 11L))
        assertEquals(1L, first.id)
    }
}
