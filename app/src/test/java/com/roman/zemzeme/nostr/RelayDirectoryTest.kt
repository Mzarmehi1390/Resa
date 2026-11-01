package com.roman.zemzeme.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayDirectoryTest {

    @Test
    fun acceptsSecureWebSocketAndBareRelayHosts() {
        assertEquals(
            "wss://relay.example",
            RelayDirectory.normalizeRelayUrl("wss://relay.example")
        )
        assertEquals(
            "wss://relay.example:443/path",
            RelayDirectory.normalizeRelayUrl("relay.example:443/path")
        )
    }

    @Test
    fun rejectsCleartextAndNonWebSocketSchemes() {
        assertNull(RelayDirectory.normalizeRelayUrl("ws://relay.example"))
        assertNull(RelayDirectory.normalizeRelayUrl("http://relay.example"))
        assertNull(RelayDirectory.normalizeRelayUrl("https://relay.example"))
        assertNull(RelayDirectory.normalizeRelayUrl("ftp://relay.example"))
    }

    @Test
    fun rejectsRelayUrlsWithCredentialsFragmentsOrWhitespace() {
        assertNull(RelayDirectory.normalizeRelayUrl("wss://user:pass@relay.example"))
        assertNull(RelayDirectory.normalizeRelayUrl("wss://relay.example/#fragment"))
        assertNull(RelayDirectory.normalizeRelayUrl("wss://relay .example"))
        assertNull(RelayDirectory.normalizeRelayUrl(""))
    }
}
