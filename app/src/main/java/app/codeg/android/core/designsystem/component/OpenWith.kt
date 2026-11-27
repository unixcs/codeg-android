package app.codeg.android.core.designsystem.component

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Open a fetched payload with a system-selected app. The bytes are cached
 * before the system chooser runs because Android cannot read Codeg's in-memory
 * server-backed files directly. `resolveOpenWithMime` keeps media types out of
 * the wildcard chooser so the right player/editor appears first.
 */
suspend fun openWithBytes(context: Context, fileName: String, bytes: ByteArray) {
    val uri = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "open_with").apply { mkdirs() }
        val file = java.io.File(dir, fileName.substringAfterLast('/').ifEmpty { "file" })
        file.writeBytes(bytes)
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, resolveOpenWithMime(fileName))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (first: Exception) {
        val fallback = Intent(Intent.ACTION_VIEW)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(fallback, null))
        } catch (second: Exception) {
            second.addSuppressed(first)
            throw second
        }
    }
}

fun resolveOpenWithMime(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "md", "markdown", "txt", "log", "json", "xml", "yml", "yaml", "toml", "csv",
        "ini", "conf", "properties", "js", "ts", "kt", "kts", "java", "py", "rb", "go",
        "rs", "c", "h", "cpp", "sh", "bat", "sql", "gradle", "proto",
        -> "text/plain"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "mp4", "m4v", "mov", "avi", "mkv", "webm", "3gp" -> "video/$extension"
        "mp3", "m4a", "aac", "wav", "flac" -> "audio/$extension"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wmv" -> "video/x-ms-wmv"
        "wma" -> "audio/x-ms-wma"
        "amr" -> "audio/amr"
        "mid", "midi" -> "audio/midi"
        else -> "*/*"
    }
}

fun isOpenWithMediaType(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in setOf("mp4", "m4v", "mov", "avi", "mkv", "webm", "3gp", "wmv") ||
        extension in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "wma", "amr", "mid", "midi")
}
