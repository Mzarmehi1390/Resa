package com.roman.zemzeme.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattCallbackLaneStateTest {

    @Test
    fun permitsOnlyOneOutstandingNotificationAcrossPeers() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a", lane.currentServerGeneration()) as GattCallbackLaneState.Event.Started

        assertEquals(GattCallbackLaneState.Event.Busy, lane.begin("peer-b", lane.currentServerGeneration()))
        assertEquals(
            GattCallbackLaneState.Event.Completed(started.operationId, 0),
            lane.onCallback("peer-a", callbackStatus = 0, successStatus = 0, callbackGeneration = lane.currentServerGeneration())
        )
        assertEquals(GattCallbackLaneState.LaneStatus.READY, lane.status())
        assertTrue(lane.begin("peer-b", lane.currentServerGeneration()) is GattCallbackLaneState.Event.Started)
    }

    @Test
    fun ignoresCallbackFromDifferentPeer() {
        val lane = GattCallbackLaneState<String>()
        lane.begin("peer-a", lane.currentServerGeneration())

        assertEquals(GattCallbackLaneState.Event.Ignored, lane.onCallback("peer-b", 0, 0, lane.currentServerGeneration()))
        assertEquals(GattCallbackLaneState.LaneStatus.IN_FLIGHT, lane.status())
    }

    @Test
    fun callbackFailureReleasesLaneWithoutClaimingSuccess() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a", lane.currentServerGeneration()) as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                started.operationId,
                GattCallbackLaneState.FailureReason.CALLBACK_FAILURE,
                133
            ),
            lane.onCallback("peer-a", callbackStatus = 133, successStatus = 0, callbackGeneration = lane.currentServerGeneration())
        )
        assertEquals(GattCallbackLaneState.LaneStatus.READY, lane.status())
    }

    @Test
    fun synchronousSubmissionRejectionReleasesLaneWithoutWaitingForCallback() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a", lane.currentServerGeneration()) as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                started.operationId,
                GattCallbackLaneState.FailureReason.SUBMISSION_REJECTED
            ),
            lane.onSubmissionRejected(started.operationId)
        )
        assertEquals(GattCallbackLaneState.LaneStatus.READY, lane.status())
        assertEquals(GattCallbackLaneState.Event.Ignored, lane.onCallback("peer-a", 0, 0, lane.currentServerGeneration()))
    }

    @Test
    fun timeoutClosesLaneUntilNewServerIsInstalled() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a", lane.currentServerGeneration()) as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                started.operationId,
                GattCallbackLaneState.FailureReason.TIMEOUT
            ),
            lane.onTimeout(started.operationId)
        )
        assertEquals(GattCallbackLaneState.Event.Closed, lane.begin("peer-b", lane.currentServerGeneration()))
        assertEquals(GattCallbackLaneState.Event.Ignored, lane.onCallback("peer-a", 0, 0, lane.currentServerGeneration()))

        lane.resetForNewServer()
        assertTrue(lane.begin("peer-b", lane.currentServerGeneration()) is GattCallbackLaneState.Event.Started)
    }

    @Test
    fun disconnectOfActivePeerFailsClosed() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a", lane.currentServerGeneration()) as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                started.operationId,
                GattCallbackLaneState.FailureReason.DISCONNECTED
            ),
            lane.onDisconnected("peer-a", lane.currentServerGeneration())
        )
        assertEquals(GattCallbackLaneState.LaneStatus.CLOSED, lane.status())
    }

    @Test
    fun shutdownResolvesActiveOperationExactlyOnce() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a", lane.currentServerGeneration()) as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                started.operationId,
                GattCallbackLaneState.FailureReason.SHUTDOWN
            ),
            lane.close()
        )
        assertEquals(GattCallbackLaneState.Event.Closed, lane.close())
        assertEquals(GattCallbackLaneState.Event.Ignored, lane.onCallback("peer-a", 0, 0, lane.currentServerGeneration()))
    }

    @Test
    fun lateCallbackFromPreviousServerCannotCompleteSamePeerOnReplacementServer() {
        val lane = GattCallbackLaneState<String>()
        val oldGeneration = lane.currentServerGeneration()
        val oldOperation = lane.begin("peer-a", oldGeneration) as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                oldOperation.operationId,
                GattCallbackLaneState.FailureReason.SHUTDOWN
            ),
            lane.close()
        )
        val newGeneration = lane.resetForNewServer()
        assertTrue(newGeneration > oldGeneration)
        val newOperation = lane.begin("peer-a", newGeneration) as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Ignored,
            lane.onCallback("peer-a", callbackStatus = 0, successStatus = 0, callbackGeneration = oldGeneration)
        )
        assertEquals(GattCallbackLaneState.LaneStatus.IN_FLIGHT, lane.status())
        assertEquals(
            GattCallbackLaneState.Event.Completed(newOperation.operationId, 0),
            lane.onCallback("peer-a", callbackStatus = 0, successStatus = 0, callbackGeneration = newGeneration)
        )
        assertTrue(oldOperation.operationId != newOperation.operationId)
    }

}
