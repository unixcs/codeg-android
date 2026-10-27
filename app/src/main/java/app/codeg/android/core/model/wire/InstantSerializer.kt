package app.codeg.android.core.model.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Tolerant RFC3339 / ISO-8601 parsing, ported from the iOS `ISO8601` helper.
 * The server (chrono on the Rust side) may emit fractional seconds with
 * arbitrary precision (or none), with `Z` or a numeric offset. We accept all
 * shapes and, as a last resort, drop sub-second precision (immaterial for
 * display/sort). `java.time` is fully available at our minSdk 31.
 */
object Rfc3339 {
    fun parse(raw: String): Instant? {
        // Offset/Z form, optionally with fractional seconds (covers +08:00 too).
        runCatching { return OffsetDateTime.parse(raw).toInstant() }
        runCatching { return Instant.parse(raw) }
        // No offset at all → assume UTC.
        runCatching { return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }
        // Strip a fractional-seconds component (6/9-digit precision some parsers
        // reject) and retry.
        val stripped = raw.replace(Regex("\\.\\d+"), "")
        runCatching { return OffsetDateTime.parse(stripped).toInstant() }
        runCatching { return LocalDateTime.parse(stripped).toInstant(ZoneOffset.UTC) }
        return null
    }
}

/** Serializes [Instant] as an RFC3339 string; decodes leniently via [Rfc3339]. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor =
        PrimitiveSerialDescriptor("com.codeg.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        // Degrade instead of throw: a required Instant sits in ConversationSummary
        // (createdAt/updatedAt/pinnedAt) and MessageTurn, so one malformed value
        // would otherwise fail the whole list/detail decode and blank the screen.
        return Rfc3339.parse(raw) ?: run {
            android.util.Log.w("InstantSerializer", "Unparseable timestamp, degrading to EPOCH: $raw")
            Instant.EPOCH
        }
    }
}
