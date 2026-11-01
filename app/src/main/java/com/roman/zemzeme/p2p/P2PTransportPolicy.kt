package com.roman.zemzeme.p2p

import com.roman.zemzeme.net.ArtiTorManager
import com.roman.zemzeme.net.TorMode

/**
 * Runtime policy for the transports that cannot currently share a Tor route.
 *
 * User preferences are intentionally not changed when Tor is enabled: P2P is
 * paused at runtime and may resume after Tor has fully stopped.
 */
internal object P2PTransportPolicy {
    fun shouldRun(
        p2pEnabled: Boolean,
        autoStart: Boolean,
        torMode: TorMode
    ): Boolean = p2pEnabled && autoStart && torMode == TorMode.OFF

    /**
     * A node may start only after both the saved preference and Arti's
     * observed runtime state confirm that Tor is OFF.
     */
    fun canStartNode(
        torPreference: TorMode,
        torRuntimeState: ArtiTorManager.TorState
    ): Boolean = torPreference == TorMode.OFF && torRuntimeState == ArtiTorManager.TorState.OFF
}
