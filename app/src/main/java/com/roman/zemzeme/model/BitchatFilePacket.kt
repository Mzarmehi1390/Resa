package com.roman.zemzeme.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ZemzemeFilePacket: TLV-encoded file transfer payload for BLE mesh.
 * TLVs:
 *  - 0x01: filename (UTF-8)
 *  - 0x02: file size (8 bytes, UInt64)
 *  - 0x03: mime type (UTF-8)
 *  - 0x04: content (bytes) — may appear multiple times for large files
 *
 * Length field for TLV is 2 bytes (UInt16, big-endian) for all TLVs.
 * For large files, CONTENT is chunked into multiple TLVs of up to 65535 bytes each.
 *
 * Note: The outer ZemzemePacket uses version 2 (4-byte payload length), so this
 * TLV payload can exceed 64 KiB even though each TLV value is limited to 65535 bytes.
 * Transport-level fragmentation then splits the final packet for BLE MTU.
 */
data class ZemzemeFilePacket(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val content: ByteArray
) {
    private enum class TLVType(val v: UByte) {
        FILE_NAME(0x01u), FILE_SIZE(0x02u), MIME_TYPE(0x03u), CONTENT(0x04u);
        companion object { fun from(value: UByte) = values().find { it.v == value } }
    }

    fun encode(): ByteArray? {
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        if (!FilePacketValidation.isValid(
                nameBytes = nameBytes,
                fileSize = fileSize,
                mimeBytes = mimeBytes,
                contentSize = content.size
            )
        ) return null

        return try {
            // The current wire format uses 2-byte lengths for metadata and a
            // 4-byte length for CONTENT. Keep that layout for protocol compatibility.
            val capacity = (1 + 2 + nameBytes.size) +
                (1 + 2 + 4) +
                (1 + 2 + mimeBytes.size) +
                (1 + 4 + content.size)
            val buf = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)

            buf.put(TLVType.FILE_NAME.v.toByte())
            buf.putShort(nameBytes.size.toShort())
            buf.put(nameBytes)

            buf.put(TLVType.FILE_SIZE.v.toByte())
            buf.putShort(4.toShort())
            buf.putInt(fileSize.toInt())

            buf.put(TLVType.MIME_TYPE.v.toByte())
            buf.putShort(mimeBytes.size.toShort())
            buf.put(mimeBytes)

            buf.put(TLVType.CONTENT.v.toByte())
            buf.putInt(content.size)
            buf.put(content)
            buf.array()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun decode(data: ByteArray): ZemzemeFilePacket? {
            if (data.isEmpty()) return null
            return try {
                var off = 0
                var nameBytes: ByteArray? = null
                var size: Long? = null
                var mimeBytes: ByteArray? = null
                val contentParts = ArrayList<ByteArray>()
                var contentSize = 0

                while (off < data.size) {
                    // Every TLV needs at least type + 2-byte length.
                    if (data.size - off < 3) return null
                    val type = TLVType.from(data[off].toUByte()) ?: return null
                    off += 1

                    val length: Int
                    if (type == TLVType.CONTENT) {
                        if (data.size - off < 4) return null
                        length = ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).int
                        off += 4
                    } else {
                        length = ((data[off].toInt() and 0xFF) shl 8) or
                            (data[off + 1].toInt() and 0xFF)
                        off += 2
                    }

                    if (length < 0 || length > data.size - off) return null
                    if (type == TLVType.CONTENT) {
                        if (length > FilePacketValidation.MAX_CONTENT_BYTES - contentSize) return null
                    }

                    when (type) {
                        TLVType.FILE_NAME -> {
                            if (nameBytes != null) return null
                            nameBytes = data.copyOfRange(off, off + length)
                        }
                        TLVType.FILE_SIZE -> {
                            if (size != null || length != 4) return null
                            val parsed = ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).int
                            if (parsed < 0) return null
                            size = parsed.toLong()
                        }
                        TLVType.MIME_TYPE -> {
                            if (mimeBytes != null) return null
                            mimeBytes = data.copyOfRange(off, off + length)
                        }
                        TLVType.CONTENT -> {
                            contentParts.add(data.copyOfRange(off, off + length))
                            contentSize += length
                        }
                    }
                    off += length
                }

                val rawName = nameBytes ?: return null
                val rawMime = mimeBytes ?: return null
                if (contentParts.isEmpty()) return null
                val declaredSize = size ?: return null
                if (!FilePacketValidation.isValid(rawName, declaredSize, rawMime, contentSize)) return null

                val name = FilePacketValidation.decodeUtf8(rawName) ?: return null
                val mime = FilePacketValidation.decodeUtf8(rawMime) ?: return null
                val content = ByteArray(contentSize)
                var destinationOffset = 0
                for (part in contentParts) {
                    System.arraycopy(part, 0, content, destinationOffset, part.size)
                    destinationOffset += part.size
                }
                ZemzemeFilePacket(name, declaredSize, mime, content)
            } catch (_: Exception) {
                null
            }
        }
    }
}

