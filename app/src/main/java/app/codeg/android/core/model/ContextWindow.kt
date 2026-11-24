package app.codeg.android.core.model

/**
 * The three session-context figures every surface must agree on. `maxTokens`
 * is only meaningful for the percentage when [usedTokens] is also present.
 */
data class ContextWindowUsage(
    val usedTokens: Int? = null,
    val maxTokens: Int? = null,
    val percent: Double? = null,
)

/**
 * Resolve server-reported context occupancy with the client-side model-family
 * fallback. The usage sheet already used this fallback, but the composer chip
 * and reply footers previously read only the raw server fields, so the same
 * CodeBuddy session showed 29.2% in one place and "—" in another.
 */
fun resolveContextWindowUsage(stats: SessionStats?, model: String?): ContextWindowUsage {
    val used = stats?.contextWindowUsedTokens?.takeIf { it >= 0 }
    val max = (stats?.contextWindowMaxTokens ?: inferContextWindowMaxTokens(model))
        ?.takeIf { it > 0 }
    val percent = stats?.contextWindowUsagePercent
        ?: used?.takeIf { max != null && max > 0 }?.toDouble()?.div(max)?.times(100.0)?.coerceIn(0.0, 100.0)
    return ContextWindowUsage(usedTokens = used, maxTokens = max, percent = percent)
}

/**
 * 批v161.1 R1: client-side context-window inference. The server's history
 * parser only fills `context_window_max_tokens` for model families it knows
 * (`parsers/mod.rs infer_context_window_max_tokens`); every CodeBuddy model
 * this deployment actually runs (DeepSeek V4.x, Hy3) falls through to null,
 * and the usage sheet then hid the used count along with the missing window —
 * the reported "CodeBuddy 会话上下文占用空白". The used tokens and the model
 * name both reach the client, so infer the window here as a supplement and
 * degrade honestly ("窗口未知") for models this table doesn't cover.
 *
 * Family values mirror the server's own table where they overlap (claude /
 * gemini / kimi / grok / gpt-o families) so the two gauges never disagree;
 * deepseek / glm / hunyuan come from each vendor's published API limits.
 */
fun inferContextWindowMaxTokens(model: String?): Int? {
    val raw = model?.trim().takeUnless { it.isNullOrEmpty() } ?: return null

    // Explicit capacity suffix wins, same as the server ("…-128k", "…1m").
    capacitySuffixRegex.find(raw)?.let { match ->
        val value = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: return@let
        val multiplier = when (match.groupValues[2].lowercase()) {
            "m" -> 1_000_000.0
            "k" -> 1_000.0
            else -> return@let
        }
        return (value * multiplier).toInt().coerceAtLeast(0)
    }

    // Strip provider prefixes ("magpie/group/x") and variant suffixes ("x:high").
    val normalized = raw.substringAfterLast('/').substringBefore(':').trim().lowercase()
    if (normalized.isEmpty()) return null

    return when {
        normalized.startsWith("claude") ->
            if (normalized.contains("1m")) 1_000_000 else 200_000
        normalized.startsWith("gemini") -> 1_048_576
        normalized.startsWith("gemma") -> 256_000
        // DeepSeek's public API caps context at 128K across V3/V3.x/V4 chat
        // and reasoner lanes (api-docs.deepseek.com, 2026-11).
        normalized.startsWith("deepseek") -> 128_000
        normalized.startsWith("kimi") ->
            if (normalized.startsWith("kimi-k3")) 1_048_576 else 262_144
        normalized.startsWith("grok") -> when {
            normalized.contains("4.3") || normalized.contains("4.20") -> 1_000_000
            normalized.contains("4.5") -> 500_000
            normalized.contains("code") || normalized.contains("build") -> 256_000
            normalized.contains("fast") && !normalized.contains("composer") -> 2_000_000
            else -> 256_000
        }
        // Zhipu: GLM-4.5 lane 128K, GLM-4.6 and GLM-5 lane 200K.
        normalized.startsWith("glm") ->
            if (normalized.startsWith("glm-5") || normalized.contains("4.6")) 200_000 else 128_000
        // Tencent Hunyuan (CodeBuddy's "Hy3"): the family's API window is 256K.
        normalized.startsWith("hy") || normalized.startsWith("hunyuan") -> 256_000
        normalized.startsWith("gpt-6.1-terra") -> 475_000
        normalized.startsWith("gpt-5") || normalized.startsWith("gpt-6") -> 258_000
        normalized.startsWith("gpt-4o") || normalized.startsWith("gpt-4.1") ||
            normalized.startsWith("gpt-4-turbo") -> 128_000
        normalized == "gpt-4" -> 8_192
        normalized == "o3" || normalized == "o1" -> 200_000
        normalized.startsWith("o3") -> 200_000
        normalized.startsWith("o1-mini") || normalized.startsWith("o1-preview") -> 128_000
        else -> null
    }
}

private val capacitySuffixRegex = Regex("(\\d+(?:\\.\\d+)?)([mk])\\b", RegexOption.IGNORE_CASE)
