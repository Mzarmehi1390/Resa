package com.roman.zemzeme.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePacketValidationTest {
    private val name = "report.pdf".toByteArray()
    private val mime = "application/pdf".toByteArray()

    @Test fun acceptsConsistentBoundedMetadata() {
        assertTrue(FilePacketValidation.isValid(name, 128, mime, 128))
    }

    @Test fun allowsAnEmptyFile() {
        assertTrue(FilePacketValidation.isValid(name, 0, mime, 0))
    }

    @Test fun rejectsNegativeOversizedAndMismatchedSizes() {
        assertFalse(FilePacketValidation.isValid(name, -1, mime, 1))
        assertFalse(FilePacketValidation.isValid(name, FilePacketValidation.MAX_CONTENT_BYTES + 1L, mime, 1))
        assertFalse(FilePacketValidation.isValid(name, 128, mime, 127))
    }

    @Test fun rejectsEmptyOrOversizedMetadata() {
        assertFalse(FilePacketValidation.isValid(byteArrayOf(), 1, mime, 1))
        assertFalse(FilePacketValidation.isValid(ByteArray(256) { 65 }, 1, mime, 1))
        assertFalse(FilePacketValidation.isValid(name, 1, ByteArray(128) { 65 }, 1))
    }

    @Test fun rejectsMalformedUtf8() {
        assertFalse(FilePacketValidation.isValid(byteArrayOf(0xC3.toByte(), 0x28), 1, mime, 1))
        assertFalse(FilePacketValidation.isValid(name, 1, byteArrayOf(0xC3.toByte(), 0x28), 1))
    }
    @Test fun filePacketRoundTripsAndPreservesContent() {
        val content = ByteArray(4096) { (it % 251).toByte() }
        val packet = ZemzemeFilePacket("report.pdf", content.size.toLong(), "application/pdf", content)
        val encoded = packet.encode()
        assertTrue(encoded != null)
        val decoded = ZemzemeFilePacket.decode(encoded!!)
        assertTrue(decoded != null)
        assertTrue(content.contentEquals(decoded!!.content))
        assertTrue(decoded.fileName == packet.fileName)
        assertTrue(decoded.fileSize == packet.fileSize)
        assertTrue(decoded.mimeType == packet.mimeType)
    }

    @Test fun emptyFileRoundTrips() {
        val packet = ZemzemeFilePacket("empty.txt", 0, "text/plain", byteArrayOf())
        val encoded = packet.encode()
        assertTrue(encoded != null)
        val decoded = ZemzemeFilePacket.decode(encoded!!)
        assertTrue(decoded != null)
        assertTrue(decoded!!.content.isEmpty())
    }

    @Test fun rejectsTrailingBytesAndDuplicateMetadata() {
        val packet = ZemzemeFilePacket("a.txt", 1, "text/plain", byteArrayOf(7))
        val encoded = packet.encode()!!
        assertFalse(ZemzemeFilePacket.decode(encoded + byteArrayOf(1)) != null)

        val nameTlvLength = 3 + "a.txt".toByteArray().size
        val duplicateNameTlv = encoded.copyOfRange(0, nameTlvLength)
        assertFalse(ZemzemeFilePacket.decode(duplicateNameTlv + encoded) != null)
    }

    @Test fun refusesFilesAboveTransportBound() {
        val size = FilePacketValidation.MAX_CONTENT_BYTES + 1
        val packet = ZemzemeFilePacket("large.bin", size.toLong(), "application/octet-stream", ByteArray(size))
        assertTrue(packet.encode() == null)
    }

    @Test fun sanitizesTraversalAndSeparatorsInIncomingNames() {
        val traversal = FilePacketValidation.sanitizeIncomingFileName("../../secret.txt", "file", ".bin")
        assertFalse(traversal.contains('/'))
        assertFalse(traversal.contains('\\\\'))
        assertFalse(traversal == ".." || traversal.startsWith(".."))
        assertTrue(traversal.endsWith(".txt"))

        val blank = FilePacketValidation.sanitizeIncomingFileName("...///", "file", ".bin")
        assertTrue(blank == "file.bin")
    }

}
