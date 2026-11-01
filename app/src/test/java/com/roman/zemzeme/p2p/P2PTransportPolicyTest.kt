package com.roman.zemzeme.p2p

import com.roman.zemzeme.net.TorMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2PTransportPolicyTest {

    @Test
    fun p2pRunsOnlyWhenEnabledAutoStartAndTorOff() {
        assertTrue(P2PTransportPolicy.shouldRun(true, true, TorMode.OFF))
        assertFalse(P2PTransportPolicy.shouldRun(false, true, TorMode.OFF))
        assertFalse(P2PTransportPolicy.shouldRun(true, false, TorMode.OFF))
        assertFalse(P2PTransportPolicy.shouldRun(true, true, TorMode.ON))
        assertFalse(P2PTransportPolicy.shouldRun(false, false, TorMode.ON))
    }
}
