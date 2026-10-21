package app.codeg.android.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The coding agents codeg can drive. Wire value is snake_case (Rust serde
 * `rename_all = "snake_case"`).
 *
 * Kept free of any Compose/UI types so the model layer stays pure Kotlin — the
 * per-agent accent colours and monogram rendering live in the design system
 * (`AgentVisuals`), keyed off this enum.
 *
 * Unknown future agent types decode to [UNKNOWN] so one new server-side agent
 * can't break a whole list decode, and can't be mistaken for a real agent.
 */
@Serializable(with = AgentType.AgentTypeSerializer::class)
enum class AgentType(val wire: String) {
    CLAUDE_CODE("claude_code"),
    CODEX("codex"),
    OPEN_CODE("open_code"),
    GEMINI("gemini"),
    OPEN_CLAW("open_claw"),
    CLINE("cline"),
    HERMES("hermes"),
    CODE_BUDDY("code_buddy"),
    KIMI_CODE("kimi_code"),
    PI("pi"),
    GROK("grok"),
    CURSOR("cursor"),
    DEEPSEEK("deepseek"),
    QODER("qoder"),
    ANTIGRAVITY("antigravity"),
    KIRO("kiro"),
    ZCODE("zcode"),
    TRAE("trae"),
    COMMAND_CODE("command_code"),

    /**
     * A server-side agent this build has no entry for. Decoding to this instead
     * of [CLAUDE_CODE] keeps an unknown agent from masquerading as a real one;
     * it is display-only and must never be sent back to the server.
     */
    UNKNOWN("unknown");

    val displayName: String
        get() = when (this) {
            CLAUDE_CODE -> "Claude Code"
            CODEX -> "Codex CLI"
            OPEN_CODE -> "OpenCode"
            GEMINI -> "Gemini CLI"
            OPEN_CLAW -> "OpenClaw"
            CLINE -> "Cline"
            HERMES -> "Hermes"
            CODE_BUDDY -> "CodeBuddy"
            KIMI_CODE -> "Kimi Code"
            PI -> "Pi"
            GROK -> "Grok"
            CURSOR -> "Cursor"
            DEEPSEEK -> "DeepSeek Harness"
            QODER -> "Qoder"
            ANTIGRAVITY -> "Google Antigravity"
            KIRO -> "Kiro CLI"
            ZCODE -> "ZCode"
            TRAE -> "TRAE CLI"
            COMMAND_CODE -> "Command Code"
            UNKNOWN -> "Unknown Agent"
        }

    /** Short label for dense badges. */
    val shortName: String
        get() = when (this) {
            CLAUDE_CODE -> "Claude"
            CODEX -> "Codex"
            OPEN_CODE -> "OpenCode"
            GEMINI -> "Gemini"
            OPEN_CLAW -> "OpenClaw"
            CLINE -> "Cline"
            HERMES -> "Hermes"
            CODE_BUDDY -> "CodeBuddy"
            KIMI_CODE -> "Kimi"
            PI -> "Pi"
            GROK -> "Grok"
            CURSOR -> "Cursor"
            DEEPSEEK -> "DeepSeek"
            QODER -> "Qoder"
            ANTIGRAVITY -> "Antigravity"
            KIRO -> "Kiro"
            ZCODE -> "ZCode"
            TRAE -> "TRAE"
            COMMAND_CODE -> "Command"
            UNKNOWN -> "Unknown"
        }

    companion object {
        /**
         * The agent for [raw], falling back to [UNKNOWN] when unrecognised so a
         * single agent-typed field always decodes without inventing an identity.
         * List decoders that must not collapse several unknown agents onto one
         * identity use [knownFromWire] and drop the nulls instead — see
         * `CodegClient.decodeAgentList`.
         */
        fun fromWire(raw: String): AgentType = knownFromWire(raw) ?: UNKNOWN

        /** The agent for [raw], or null when this build doesn't recognise the wire value. */
        fun knownFromWire(raw: String): AgentType? = entries.firstOrNull { it.wire == raw }
    }

    object AgentTypeSerializer : KSerializer<AgentType> {
        override val descriptor =
            PrimitiveSerialDescriptor("com.codeg.AgentType", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: AgentType) =
            encoder.encodeString(value.wire)

        override fun deserialize(decoder: Decoder): AgentType =
            fromWire(decoder.decodeString())
    }
}
