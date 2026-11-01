package com.roman.zemzeme.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattCallbackLaneStateTest {

    @Test
    fun permitsOnlyOneOutstandingNotificationAcrossPeers() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a") as GattCallbackLaneState.Event.Started

        assertEquals(GattCallbackLaneState.Event.Busy, lane.begin("peer-b"))
        assertEquals(
            GattCallbackLaneState.Event.Completed(started.operationId, 0),
            lane.onCallback("peer-a", callbackStatus = 0, successStatus = 0)
        )
        assertEquals(GattCallbackLaneState.LaneStatus.READY, lane.status())
        assertTrue(lane.begin("peer-b") is GattCallbackLaneState.Event.Started)
    }

    @Test
    fun ignoresCallbackFromDifferentPeer() {
        val lane = GattCallbackLaneState<String>()
        lane.begin("peer-a")

        assertEquals(GattCallbackLaneState.Event.Ignored, lane.onCallback("peer-b", 0, 0))
        assertEquals(GattCallbackLaneState.LaneStatus.IN_FLIGHT, lane.status())
    }

    @Test
    fun callbackFailureReleasesLaneWithoutClaimingSuccess() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a") as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                started.operationId,
                GattCallbackLaneState.FailureReason.CALLBACK_FAILURE,
                133
            ),
            lane.onCallback("peer-a", callbackStatus = 133, successStatus = 0)
        )
        assertEquals(GattCallbackLaneState.LaneStatus.READY, lane.status())
    }

    @Test
    fun timeoutClosesLaneUntilNewServerIsInstalled() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a") as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                started.operationId,
                GattCallbackLaneState.FailureReason.TIMEOUT
            ),
            lane.onTimeout(started.operationId)
        )
        assertEquals(GattCallbackLaneState.Event.Closed, lane.begin("peer-b"))
        assertEquals(GattCallbackLaneState.Event.Ignored, lane.onCallback("peer-a", 0, 0))

        lane.resetForNewServer()
        assertTrue(lane.begin("peer-b") is GattCallbackLaneState.Event.Started)
    }

    @Test
    fun disconnectOfActivePeerFailsClosed() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a") as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                started.operationId,
                GattCallbackLaneState.FailureReason.DISCONNECTED
            ),
            lane.onDisconnected("peer-a")
        )
        assertEquals(GattCallbackLaneState.LaneStatus.CLOSED, lane.status())
    }

    @Test
    fun shutdownResolvesActiveOperationExactlyOnce() {
        val lane = GattCallbackLaneState<String>()
        val started = lane.begin("peer-a") as GattCallbackLaneState.Event.Started

        assertEquals(
            GattCallbackLaneState.Event.Failed(
                started.operationId,
                GattCallbackLaneState.FailureReason.SHUTDOWN
            ),
            lane.close()
        )
        assertEquals(GattCallbackLaneState.Event.Closed, lane.close())
        assertEquals(GattCallbackLaneState.Event.Ignored, lane.onCallback("peer-a", 0, 0))
    }
}
