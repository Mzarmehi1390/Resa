package com.roman.zemzeme.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class GattClientWriteLaneStateTest {

    @Test
    fun permitsOnlyOneWriteUntilMatchingCallbackArrives() {
        val lane = GattClientWriteLaneState<String>(connectionGeneration = 4L)

        assertEquals(GattClientWriteLaneState.Event.Started, lane.begin("device-a", 4L))
        assertEquals(GattClientWriteLaneState.Event.Busy, lane.begin("device-a", 4L))
        assertEquals(
            GattClientWriteLaneState.Event.Completed,
            lane.onWriteCallback("device-a", callbackStatus = 0, successStatus = 0, callbackGeneration = 4L)
        )
        assertEquals(GattClientWriteLaneState.Status.READY, lane.status())
        assertEquals(GattClientWriteLaneState.Event.Started, lane.begin("device-a", 4L))
    }

    @Test
    fun ignoresCallbacksFromSupersededConnectionGeneration() {
        val lane = GattClientWriteLaneState<String>(connectionGeneration = 8L)
        lane.begin("device-a", 8L)

        assertEquals(
            GattClientWriteLaneState.Event.Ignored,
            lane.onWriteCallback("device-a", 0, 0, callbackGeneration = 7L)
        )
        assertEquals(GattClientWriteLaneState.Status.IN_FLIGHT, lane.status())
    }

    @Test
    fun ignoresCallbackForDifferentConnectionKey() {
        val lane = GattClientWriteLaneState<String>(connectionGeneration = 2L)
        lane.begin("device-a", 2L)

        assertEquals(
            GattClientWriteLaneState.Event.Ignored,
            lane.onWriteCallback("device-b", 0, 0, callbackGeneration = 2L)
        )
        assertEquals(GattClientWriteLaneState.Status.IN_FLIGHT, lane.status())
    }

    @Test
    fun callbackFailureReleasesTheSingleFlightGate() {
        val lane = GattClientWriteLaneState<String>(connectionGeneration = 3L)
        lane.begin("device-a", 3L)

        assertEquals(
            GattClientWriteLaneState.Event.Failed(
                GattClientWriteLaneState.FailureReason.CALLBACK_FAILURE,
                status = 133
            ),
            lane.onWriteCallback("device-a", 133, 0, callbackGeneration = 3L)
        )
        assertEquals(GattClientWriteLaneState.Status.READY, lane.status())
    }

    @Test
    fun synchronousSubmissionRejectionDoesNotWaitForCallback() {
        val lane = GattClientWriteLaneState<String>(connectionGeneration = 5L)
        lane.begin("device-a", 5L)

        assertEquals(
            GattClientWriteLaneState.Event.Failed(
                GattClientWriteLaneState.FailureReason.SUBMISSION_REJECTED
            ),
            lane.onSubmissionRejected("device-a", 5L)
        )
        assertEquals(GattClientWriteLaneState.Event.Ignored, lane.onWriteCallback("device-a", 0, 0, 5L))
        assertEquals(GattClientWriteLaneState.Status.READY, lane.status())
    }

    @Test
    fun disconnectFailsActiveWriteAndClosesLane() {
        val lane = GattClientWriteLaneState<String>(connectionGeneration = 6L)
        lane.begin("device-a", 6L)

        assertEquals(
            GattClientWriteLaneState.Event.Failed(GattClientWriteLaneState.FailureReason.DISCONNECTED),
            lane.onDisconnected(6L)
        )
        assertEquals(GattClientWriteLaneState.Status.CLOSED, lane.status())
        assertEquals(GattClientWriteLaneState.Event.Closed, lane.begin("device-a", 6L))
    }

    @Test
    fun staleDisconnectCannotCloseNewerGenerationLane() {
        val lane = GattClientWriteLaneState<String>(connectionGeneration = 9L)

        assertEquals(GattClientWriteLaneState.Event.Ignored, lane.onDisconnected(8L))
        assertEquals(GattClientWriteLaneState.Status.READY, lane.status())
    }

    @Test
    fun shutdownResolvesActiveWriteExactlyOnce() {
        val lane = GattClientWriteLaneState<String>(connectionGeneration = 10L)
        lane.begin("device-a", 10L)

        assertEquals(
            GattClientWriteLaneState.Event.Failed(GattClientWriteLaneState.FailureReason.SHUTDOWN),
            lane.close()
        )
        assertEquals(GattClientWriteLaneState.Event.Closed, lane.close())
        assertEquals(GattClientWriteLaneState.Status.CLOSED, lane.status())
    }
}
