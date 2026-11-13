package app.codeg.android.core.designsystem.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 批v178: the system camera wired to the existing attachment pipelines.
 *
 * [ActivityResultContracts.TakePicture] needs no CAMERA permission and hands the
 * full-resolution JPEG we pre-create under `cacheDir/camera/` back through
 * FileProvider; callers feed the returned Uri into whatever the screen already
 * does with picked images (the composer's base64 chain, AttachmentStager's
 * private copy). The pending path is rememberSaveable so a process death in the
 * composer still consumes its pending result (dialog/sheet hosts lose the shot
 * instead — the stale file is swept later); a cancelled capture deletes the
 * orphan. Returns a zero-arg launch callback.
 */
@Composable
fun rememberCameraCapture(onCaptured: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pendingFile by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingFile?.let(::File)
        pendingFile = null
        when {
            file == null -> Unit
            ok -> FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                .let(onCaptured)
            else -> file.delete()
        }
    }
    return {
        runCatching {
            // 审查批v178 P2-1: one capture in flight — a second tap while the
            // camera is up would re-point the single pending slot and orphan
            // the first shot (or stage a half-written file).
            if (pendingFile != null) return@runCatching
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            // Sweep only stale orphans: a file the stager is still copying
            // (created seconds ago) must survive this launch.
            val staleBefore = System.currentTimeMillis() - 60_000
            dir.listFiles()?.forEach { if (it.lastModified() < staleBefore) it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "IMG_$stamp.jpg").apply { createNewFile() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingFile = file.absolutePath
            launcher.launch(uri)
        }
    }
}
