package com.roman.zemzeme.services

import com.roman.zemzeme.model.ZemzemeMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.Date

class AppStateStoreTest {

    @Before
    fun setUp() {
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
    }

    @Test
    fun addChannelMessagePublishesMessageAndDeduplicatesById() {
        val message = ZemzemeMessage(
            id = "channel-message-test",
            sender = "peer-a",
            content = "hello",
            timestamp = Date(System.currentTimeMillis() + 1_000L),
            senderPeerID = "peer-a",
            channel = "#general"
        )

        AppStateStore.addChannelMessage("#general", message)
        AppStateStore.addChannelMessage("#general", message)

        val stored = AppStateStore.channelMessages.value["#general"]
        assertNotNull(stored)
        assertEquals(listOf(message), stored)
    }
}
