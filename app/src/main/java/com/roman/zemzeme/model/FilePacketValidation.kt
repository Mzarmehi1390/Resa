package com.roman.zemzeme.model

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Bounded, platform-independent validation for the current BLE file packet format. */
internal object FilePacketValidation {
    const val MAX_CONTENT_BYTES = 480 * 1024
    const val MAX_FILE_NAME_BYTES = 255
    const val MAX_MIME_TYPE_BYTES = 127

    fun isValid(nameBytes: ByteArray, fileSize: Long, mimeBytes: ByteArray, contentSize: Int): Boolean {
        if (nameBytes.isEmpty() || nameBytes.size > MAX_FILE_NAME_BYTES) return false
        if (mimeBytes.isEmpty() || mimeBytes.size > MAX_MIME_TYPE_BYTES) return false
        if (fileSize < 0 || fileSize > MAX_CONTENT_BYTES || fileSize != contentSize.toLong()) return false
        if (contentSize < 0 || contentSize > MAX_CONTENT_BYTES) return false
        return decodeUtf8(nameBytes)?.isNotBlank() == true &&
            decodeUtf8(mimeBytes)?.isNotBlank() == true
    }

    fun sanitizeIncomingFileName(value: String, fallback: String, extension: String): String {
        val safeBase = value
            .replace('\\\\', '_')
            .replace('/', '_')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_', ' ')
            .take(120)
            .ifBlank { fallback }
        return if (safeBase.contains('.')) safeBase else safeBase + extension
    }

    fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }
}
