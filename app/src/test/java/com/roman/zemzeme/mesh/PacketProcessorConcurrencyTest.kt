package com.roman.zemzeme.mesh

import android.os.Build
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.roman.zemzeme.model.RoutedPacket
import com.roman.zemzeme.protocol.MessageType
import com.roman.zemzeme.protocol.ZemzemePacket
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class PacketProcessorConcurrencyTest {
    @Test
    fun concurrentCallbacksReuseOneActorForTheSamePeer() {
        val processor = PacketProcessor("1111222233334444")
        val peerID = "aaaabbbbccccdddd"
        val workerCount = 48
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(12)
        try {
            repeat(workerCount) { index ->
                workers.execute {
                    try {
                        start.await()
                        processor.processPacket(packet(peerID, index))
                    } finally {
                        ready.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue("All receive callbacks should finish", ready.await(5, TimeUnit.SECONDS))
            assertTrue(
                "Concurrent callbacks for one peer must share one actor",
                processor.getDebugInfo().contains("Active Peer Actors: 1")
            )
        } finally {
            workers.shutdownNow()
            processor.shutdown()
        }
    }

    @Test
    fun actorContinuesAfterOnePacketHandlerThrows() {
        val processor = PacketProcessor("1111222233334444")
        val peerID = "aaaabbbbccccdddd"
        val calls = AtomicInteger()
        val reachedValidation = CountDownLatch(2)
        processor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: ZemzemePacket, peerID: String): Boolean {
                reachedValidation.countDown()
                if (calls.getAndIncrement() == 0) throw IllegalStateException("intentional test exception")
                return false
            }
            override fun updatePeerLastSeen(peerID: String) = Unit
            override fun getPeerNickname(peerID: String): String? = null
            override fun getNetworkSize(): Int = 1
            override fun getBroadcastRecipient(): ByteArray = ByteArray(0)
            override fun handleNoiseHandshake(routed: RoutedPacket): Boolean = false
            override fun handleNoiseEncrypted(routed: RoutedPacket) = Unit
            override fun handleAnnounce(routed: RoutedPacket) = Unit
            override fun handleMessage(routed: RoutedPacket) = Unit
            override fun handleLeave(routed: RoutedPacket) = Unit
            override fun handleFragment(packet: ZemzemePacket): ZemzemePacket? = null
            override fun handleRequestSync(routed: RoutedPacket) = Unit
            override fun sendAnnouncementToPeer(peerID: String) = Unit
            override fun sendCachedMessages(peerID: String) = Unit
            override fun relayPacket(routed: RoutedPacket) = Unit
            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean = false
        }
        try {
            processor.processPacket(packet(peerID, 1))
            processor.processPacket(packet(peerID, 2))
            assertTrue("Actor must survive a handler exception", reachedValidation.await(5, TimeUnit.SECONDS))
        } finally {
            processor.shutdown()
        }
    }

    private fun packet(peerID: String, sequence: Int): RoutedPacket {
        val packet = ZemzemePacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            timestamp = (1_800_000_000_000L + sequence).toULong(),
            payload = byteArrayOf(sequence.toByte())
        )
        return RoutedPacket(packet, peerID, null)
    }
}
