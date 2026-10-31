package com.roman.zemzeme.ui

import com.roman.zemzeme.model.ZemzemeMessage
import com.roman.zemzeme.model.ZemzemeMessageType

/**
 * Utilities for building human-friendly notification text/previews.
 */
object NotificationTextUtils {
    /**
     * Build a user-friendly notification preview for private messages, especially attachments.
     * Examples:
     * - Image: "📷 sent an image"
     * - Audio: "🎤 sent a voice message"
     * - File (pdf): "📄 file.pdf"
     * - Text: original message content
     */
    fun buildPrivateMessagePreview(message: ZemzemeMessage): String {
        return try {
            when (message.type) {
                ZemzemeMessageType.Image -> "📷 sent an image"
                ZemzemeMessageType.Audio -> "🎤 sent a voice message"
                ZemzemeMessageType.File -> {
                    // Show just the filename (not the full path)
                    val name = try { java.io.File(message.content).name } catch (_: Exception) { null }
                    if (!name.isNullOrBlank()) {
                        val lower = name.lowercase()
                        val icon = when {
                            lower.endsWith(".pdf") -> "📄"
                            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> "🗜️"
                            lower.endsWith(".doc") || lower.endsWith(".docx") -> "📄"
                            lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "📊"
                            lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "📈"
                            else -> "📎"
                        }
                        "$icon $name"
                    } else {
                        "📎 sent a file"
                    }
                }
                else -> message.content
            }
        } catch (_: Exception) {
            // Fallback to original content on any error
            message.content
        }
    }
}
