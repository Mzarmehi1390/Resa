package com.roman.zemzeme.features.file

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * Save a file from URI to app's file directory with unique filename
     */
    fun saveFileFromUri(
        context: Context,
        uri: Uri,
        originalName: String? = null
    ): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "❌ Failed to open input stream for URI: $uri")
                return null
            }
            Log.d(TAG, "📂 Opened input stream successfully")

            // Determine file extension
            val extension = originalName?.substringAfterLast(".") ?: "bin"
            val fileName = "file_${System.currentTimeMillis()}.$extension"

            // Create incoming dir if needed
            val incomingDir = File(context.filesDir, "files/incoming").apply {
                if (!exists()) mkdirs()
            }

            val file = File(incomingDir, fileName)

            inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Saved file to: ${file.absolutePath}")
            file.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file from URI", e)
            null
        }
    }

    /**
     * Copy file to app's outgoing directory for sending
     */
    fun copyFileForSending(context: Context, uri: Uri, originalName: String? = null): String? {
        Log.d(TAG, "🔄 Starting file copy from URI: $uri")
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "❌ Failed to open input stream for URI: $uri")
                return null
            }
            Log.d(TAG, "📂 Opened input stream successfully")

            // Determine original filename and extension if available
            val displayName = originalName ?: run {
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                    }
                } catch (_: Exception) { null }
            }
            val extension = displayName?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
                ?: run {
                    // Try mime type to extension
                    val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
                    android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                }
            // Preserve original filename (without artificial prefixes), ensure uniqueness
            val baseName = displayName?.substringBeforeLast('.')?.take(64)?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?: "file"
            var fileName = if (extension.isNotBlank()) "$baseName.$extension" else baseName

            // Create outgoing dir if needed
            val outgoingDir = File(context.filesDir, "files/outgoing").apply {
                if (!exists()) mkdirs()
            }

            var target = File(outgoingDir, fileName)
            if (target.exists()) {
                var idx = 1
                val pureBase = baseName
                val dotExt = if (extension.isNotBlank()) ".${extension}" else ""
                while (target.exists() && idx < 1000) {
                    fileName = "$pureBase ($idx)$dotExt"
                    target = File(outgoingDir, fileName)
                    idx++
                }
            }

            inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "✅ Successfully copied file for sending: ${target.absolutePath}")
            Log.d(TAG, "📊 Final file size: ${target.length()} bytes")
            target.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Failed to copy file for sending", e)
            Log.e(TAG, "❌ Source URI: $uri")
            Log.e(TAG, "❌ Original name: $originalName")
            Log.e(TAG, "❌ Error type: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Get MIME type for a file based on extension
     */
    fun getMimeTypeFromExtension(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            else -> "application/octet-stream"
        }
    }

    /**
     * Format file size for display
     */
    fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024.0
            unitIndex++
        }
        return "%.1f %s".format(size, units[unitIndex])
    }

    /**
     * Check if file is viewable in system viewer
     */
    fun isFileViewable(fileName: String): Boolean {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return extension in listOf(
            "pdf", "txt", "json", "xml", "html", "htm", "csv",
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg"
        )
    }

    /**
     * Save an incoming file packet to app storage and return absolute path.
     * Mirrors existing behavior used in MessageHandler (preserves names and folders).
     */
    fun saveIncomingFile(
        context: Context,
        file: com.roman.zemzeme.model.ZemzemeFilePacket
    ): String {
        val valid = com.roman.zemzeme.model.FilePacketValidation.isValid(
            nameBytes = file.fileName.toByteArray(Charsets.UTF_8),
            fileSize = file.fileSize,
            mimeBytes = file.mimeType.toByteArray(Charsets.UTF_8),
            contentSize = file.content.size
        )
        require(valid) { "Invalid or oversized incoming file packet" }

        val lowerMime = file.mimeType.lowercase()
        val isImage = lowerMime.startsWith("image/")
        val baseDir = File(context.cacheDir, if (isImage) "images/incoming" else "files/incoming")
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw java.io.IOException("Unable to create incoming file directory")
        }
        require(baseDir.isDirectory) { "Incoming file destination is not a directory" }

        val safeBaseName = file.fileName
            .replace('\\', '_')
            .replace('/', '_')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_', ' ')
            .take(120)
            .ifBlank { if (isImage) "image" else "file" }

        val safeName = if (safeBaseName.contains('.')) safeBaseName else {
            val extension = when (lowerMime) {
                "image/jpeg", "image/jpg" -> ".jpg"
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "application/pdf" -> ".pdf"
                "text/plain" -> ".txt"
                else -> if (isImage) ".img" else ".bin"
            }
            safeBaseName + extension
        }

        val destinationDir = baseDir.canonicalFile
        var destination = File(destinationDir, safeName)
        var index = 1
        while (destination.exists()) {
            val dot = safeName.lastIndexOf('.')
            val base = if (dot > 0) safeName.substring(0, dot) else safeName
            val extension = if (dot > 0) safeName.substring(dot) else ""
            destination = File(destinationDir, "$base ($index)$extension")
            index++
            if (index > 1000) throw java.io.IOException("Unable to allocate unique incoming filename")
        }
        require(destination.canonicalFile.parentFile == destinationDir) {
            "Invalid incoming file destination"
        }

        val temporary = File.createTempFile(".incoming-", ".part", destinationDir)
        try {
            java.io.FileOutputStream(temporary).use { output ->
                output.write(file.content)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                throw java.io.IOException("Unable to finalize incoming file")
            }
            return destination.absolutePath
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    /**
     * Classify ZemzemeMessageType from MIME string used in file messages.
     */
    fun messageTypeForMime(mime: String): com.roman.zemzeme.model.ZemzemeMessageType {
        val lower = mime.lowercase()
        return when {
            lower.startsWith("image/") -> com.roman.zemzeme.model.ZemzemeMessageType.Image
            lower.startsWith("audio/") -> com.roman.zemzeme.model.ZemzemeMessageType.Audio
            else -> com.roman.zemzeme.model.ZemzemeMessageType.File
        }
    }

    /**
     * Recursively delete all media files (incoming and outgoing)
     * Used for Panic Mode cleanup
     */
    fun clearAllMedia(context: Context) {
        try {
            // Clear files dir subdirectories (legacy storage and outgoing)
            val filesDir = context.filesDir
            val dirsToClear = listOf(
                "files/incoming",
                "files/outgoing",
                "images/incoming",
                "images/outgoing",
                "voicenotes"
            )

            dirsToClear.forEach { subDir ->
                val dir = File(filesDir, subDir)
                if (dir.exists()) {
                    dir.deleteRecursively()
                    Log.d(TAG, "Deleted media directory from filesDir: $subDir")
                }
            }
            
            // Clear cache dir subdirectories (new incoming storage)
            // Note: cacheDir.deleteRecursively() below would handle this, but being explicit ensures these
            // specific media folders are targeted even if full cache clear fails or is modified later.
            val cacheDir = context.cacheDir
            val cacheDirsToClear = listOf(
                "files/incoming",
                "images/incoming"
            )
            
            cacheDirsToClear.forEach { subDir ->
                val dir = File(cacheDir, subDir)
                if (dir.exists()) {
                    dir.deleteRecursively()
                    Log.d(TAG, "Deleted media directory from cacheDir: $subDir")
                }
            }
            
            // Also clear entire cache dir as a catch-all
            context.cacheDir.deleteRecursively()
            Log.d(TAG, "Cleared entire cache directory")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear media files", e)
        }
    }
}
