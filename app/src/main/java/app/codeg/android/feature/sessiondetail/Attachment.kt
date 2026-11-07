package app.codeg.android.feature.sessiondetail

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import app.codeg.android.core.model.ImageData
import app.codeg.android.core.model.PromptInputBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * A prepared image attachment ready to send: base64 payload + mime type. Mirrors
 * iOS `Attachment` — the bytes are already downscaled/encoded by [AttachmentPrep].
 */
data class Attachment(
    val id: String,
    val base64: String,
    val mimeType: String,
) {
    val promptBlock: PromptInputBlock get() = PromptInputBlock.Image(data = base64, mimeType = mimeType)
    val imageData: ImageData get() = ImageData(data = base64, mimeType = mimeType)
}

/**
 * Downscales + encodes a picked image to keep prompts under Axum's JSON body
 * limit. Port of iOS `AttachmentPrep`: longest side ≤ 1568 px, JPEG quality 0.75,
 * ≤ 4 MB per image hard ceiling. Originals already within limits keep their
 * bytes + sniffed mime.
 *
 * v149.2 adaptive re-encode: the composer's aggregate cap is
 * [AttachmentPrep.MAX_TOTAL_BYTES] × 4/3 base64 chars (the 2 MiB axum prompt
 * ceiling), so an original that skipped re-encode (small dimensions, up to
 * 4 MB) was attached and then REJECTED there — a ~1.5 MB screenshot filled the
 * whole budget and every later image (even a few KB) was skipped as "too
 * large". Prep now walks [LADDER] step-down (dimension × quality) until the
 * JPEG fits [TARGET_BYTES], so any attachable image lands small enough for
 * 2–3 of them to coexist; only an image failing every step returns null.
 */
object AttachmentPrep {
    const val MAX_DIMENSION = 1568
    private const val JPEG_QUALITY = 75
    const val MAX_BYTES = 4_000_000
    const val MAX_TOTAL_BYTES = 1_300_000

    /** 批v155: count cap unified across every surface (was a local 10). */
    val MAX_COUNT = app.codeg.android.core.data.AttachmentLimits.MAX_IMAGES

    /** Per-image byte budget after prep — see the object KDoc. */
    const val TARGET_BYTES = 550_000

    /**
     * Step-down re-encode ladder: (longest side px, JPEG quality). Monotonic;
     * the last step yields ≲100 KB even for noise-heavy photos. Pure data so
     * the schedule is unit-testable without Android graphics.
     * 批v155: the 512/35 rung serves the shrunken per-image targets a 20-image
     * batch needs to fit the same aggregate ceiling (see [fromUri]).
     */
    internal val LADDER = listOf(
        PrepStep(MAX_DIMENSION, JPEG_QUALITY),
        PrepStep(1280, 60),
        PrepStep(1024, 50),
        PrepStep(768, 40),
        PrepStep(512, 35),
    )

    /**
     * Pure prep routing: -1 keeps the original (already within the byte budget
     * and the dimension cap — no quality loss), otherwise the [LADDER] index to
     * start from. Returns -2 for a non-image (no bytes). Unit-tested.
     */
    internal fun prepRoute(originalBytes: Int, longestSide: Int, targetBytes: Int = TARGET_BYTES): Int =
        when {
            originalBytes <= 0 || longestSide <= 0 -> -2
            originalBytes <= targetBytes && longestSide <= MAX_DIMENSION -> -1
            else -> 0
        }

    /**
     * [targetBytes] overrides the per-image budget: the composer passes a
     * count-aware value so a large batch fits the aggregate ceiling (批v155).
     */
    suspend fun fromUri(
        uri: Uri,
        resolver: ContentResolver,
        targetBytes: Int = TARGET_BYTES,
    ): Attachment? = withContext(Dispatchers.Default) {
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        decodeAndEncode(bytes, targetBytes)
    }

    /**
     * 批v162（想法⇄会话快链）：同一条降采样/编码管线，源换成已落在应用私有
     * 目录的本地文件（想法附件）——不走 ContentResolver，不造 file:// 假 Uri，
     * 纯文件流读字节。null = 文件读不出或怎么压都进不了预算。
     */
    suspend fun fromLocalFile(
        path: String,
        targetBytes: Int = TARGET_BYTES,
    ): Attachment? = withContext(Dispatchers.Default) {
        val bytes = runCatching { java.io.File(path).inputStream().use { it.readBytes() } }.getOrNull()
            ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        decodeAndEncode(bytes, targetBytes)
    }

    /** [fromUri]/[fromLocalFile] 共用的解码+降采样+编码核心（CPU 密集，调用方已在 Default 上）。 */
    private fun decodeAndEncode(bytes: ByteArray, targetBytes: Int): Attachment? {
        // 批v165 R4 (P0): a bare decodeByteArray memory-maps the FULL image — a
        // 48 MP camera photo is ~200 MB of ARGB_8888, and that OOM is an Error
        // that escapes every caller's runCatching (process kill from picking
        // one big photo). Two-pass sampling first, same shape as
        // ImageViewerDialog.decodeForViewer: the ladder tops out at
        // MAX_DIMENSION, so anything wider decodes at sample size with
        // identical ladder output.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample >= MAX_DIMENSION * 2 || bounds.outHeight / sample >= MAX_DIMENSION * 2) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val longest = max(bitmap.width, bitmap.height)
        // 批v165 R5: route on the ORIGINAL dimensions — integer sampling can
        // floor a 3136-px original to exactly 1568, which read as "within the
        // cap" and shipped the raw bytes (route -1) at double the limit. The
        // ladder below still scales from the sampled bitmap's own longest.
        val routedLongest = max(bounds.outWidth, bounds.outHeight)
        return when (val start = prepRoute(bytes.size, routedLongest, targetBytes)) {
            -1 -> Attachment(
                id = UUID.randomUUID().toString(),
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                mimeType = sniffMime(bytes),
            )
            -2 -> null
            else -> {
                var out: ByteArray? = null
                for (step in LADDER.drop(start)) {
                    val scale = if (longest > step.longestSide) step.longestSide.toFloat() / longest else 1f
                    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, step.jpegQuality, baos)
                    val encoded = baos.toByteArray()
                    if (encoded.isEmpty()) continue
                    if (encoded.size <= targetBytes) { out = encoded; break }
                    if (out == null || encoded.size < out.size) out = encoded
                }
                val best = out
                if (best == null || best.isEmpty() || best.size > MAX_BYTES) {
                    null
                } else {
                    Attachment(
                        id = UUID.randomUUID().toString(),
                        base64 = Base64.encodeToString(best, Base64.NO_WRAP),
                        mimeType = "image/jpeg",
                    )
                }
            }
        }
    }

    /** Sniff a mime type from magic bytes; default to PNG. */
    private fun sniffMime(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> "image/png"
        b.size >= 4 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() -> "image/gif"
        b.size >= 12 && b[0] == 0x52.toByte() && b[8] == 0x57.toByte() && b[9] == 0x45.toByte() -> "image/webp"
        else -> "image/png"
    }
}

/** One rung of the [AttachmentPrep.LADDER]: re-encode to this longest side at this JPEG quality. */
internal data class PrepStep(val longestSide: Int, val jpegQuality: Int)

/**
 * Auto-rename for a workspace upload that hit "already exists" (409): the
 * v149.2 fix for "retrying the same file keeps failing" — a previous attempt
 * may have landed the bytes even though the client saw an error, and the
 * server refuses to overwrite. "a.png" → "a (1).png"; extensionless names get
 * the suffix too; a leading-dot name keeps its dot. Pure — unit-tested.
 */
internal fun autoRenamedFileName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} (1)${name.substring(dot)}" else "$name (1)"
}
