package app.codeg.android.core.designsystem.component

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.codeg.android.core.network.CodegClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Stream a workspace file into the app cache, then hand its URI to the system
 * open-with chooser. Streaming avoids the old 10 MB base64 ceiling that made
 * generated videos (and larger audio files) fail before a player ever opened.
 * `resolveOpenWithMime` narrows the chooser so the right player appears first.
 */
suspend fun openWithWorkspaceFile(
    context: Context,
    client: CodegClient,
    rootPath: String,
    path: String,
    fileName: String = path.substringAfterLast('/'),
) {
    val file = withContext(Dispatchers.IO) {
        val cachedRoot = File(context.cacheDir, "open_with").apply { mkdirs() }
        pruneOldOpenWithDirs(cachedRoot)
        // A unique directory keeps repeated opens from overwriting a file that
        // a player may still be reading after the chooser closes.
        val dir = File(cachedRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(dir, safeShareFileName(fileName).ifEmpty { "file" })
        target.outputStream().buffered().use { output ->
            client.downloadWorkspace(rootPath, path, isDir = false) { chunk ->
                output.write(chunk)
            }
        }
        target
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
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

private fun pruneOldOpenWithDirs(root: File) {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    root.listFiles()
        ?.filter { it.isDirectory && it.lastModified() < cutoff }
        ?.forEach { it.deleteRecursively() }
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
        "mp4" -> "video/mp4"
        "m4v" -> "video/x-m4v"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wmv" -> "video/x-ms-wmv"
        "wma" -> "audio/x-ms-wma"
        "amr" -> "audio/amr"
        "mid", "midi" -> "audio/midi"
        else -> "*/*"
    }
}

/**
 * Resolve the (root, relative path) pair the download RPC expects for a file
 * the agent produced. Artifacts are absolute paths; one nested under the
 * session workspace must keep the WORKSPACE root and the nested relative path
 * (the old code re-rooted at the file's immediate parent but still sent the
 * nested path, producing a 404). Files outside the workspace re-root at their
 * immediate parent and send only the basename. Relative artifact paths resolve
 * against the workspace root; traversal is rejected.
 */
internal data class WorkspaceOpenTarget(
    val rootPath: String,
    val relativePath: String,
)

internal fun resolveWorkspaceOpenTarget(rootPath: String, path: String): WorkspaceOpenTarget? {
    val root = rootPath.trim().let { if (it.length > 1) it.trimEnd('/') else it }
    val candidate = path.trim()
    if (root.isEmpty() || candidate.isEmpty()) return null

    val name = candidate.substringAfterLast('/')
    if (name.isBlank() || name == "." || name == "..") return null

    if (root.length > 1 && candidate.startsWith("$root/")) {
        val relative = candidate.removePrefix("$root/")
        return if (isSafeRelativePath(relative)) WorkspaceOpenTarget(root, relative) else null
    }
    if (candidate.startsWith("/")) {
        val parent = candidate.substringBeforeLast('/').ifEmpty { "/" }
        return WorkspaceOpenTarget(parent, name)
    }

    val relative = candidate.removePrefix("./")
    return if (isSafeRelativePath(relative)) WorkspaceOpenTarget(root, relative) else null
}

private fun isSafeRelativePath(path: String): Boolean =
    path.isNotBlank() && !path.startsWith("/") && path.split('/').none { it == ".." }

fun isOpenWithMediaType(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in setOf("mp4", "m4v", "mov", "avi", "mkv", "webm", "3gp", "wmv") ||
        extension in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "wma", "amr", "mid", "midi")
}
