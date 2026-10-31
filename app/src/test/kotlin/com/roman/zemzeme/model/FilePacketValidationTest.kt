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
}
