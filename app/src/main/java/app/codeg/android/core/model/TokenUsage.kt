package app.codeg.android.core.model

import kotlinx.serialization.Serializable

/**
 * Token 用量（舰队 M3，Q6：以 codeg 服务端会话真实用量为真源；网关侧成本
 * 口径另见 zcode-hub，两套互补不对账）。镜像 web 端 `src/lib/types.ts` 的
 * TokenUsage* 契约：
 * - [TokenUsageFilter] 是请求体：Rust 端 `rename_all = "camelCase"`，由
 *   CodegJson.request（camelCase）自然编码——字段名就是 wire 名。
 * - 其余是响应体：Rust 默认 snake_case，由 CodegJson.response 的 SnakeCase
 *   策略映射到 camelCase 属性。批v169 起 series/heatmap/topConversations/
 *   streak 已接住（仪表盘用），服务端将来新增字段仍靠 ignoreUnknownKeys 兼容。
 * 时间语义：bucket 全部是「查看者本地时区」的桶——服务端靠
 * [TokenUsageFilter.tzOffsetMinutes] 分桶，客户端只上报偏移，不自行换算
 * 任何时间戳（与 PRD「时区绝不客户端换算」一致：换算是服务端的事）。
 */
@Serializable
data class TokenUsageFilter(
    /** Inclusive lower bound, ISO-8601. null = since the first recorded turn. */
    val start: String? = null,
    /** Exclusive upper bound, ISO-8601. null = up to now. */
    val end: String? = null,
    val folderIds: List<Int>? = null,
    val agentTypes: List<String>? = null,
    val models: List<String>? = null,
    /** day | week | month（轻量版只用 day）。 */
    val bucket: String = "day",
    /** `-new Date().getTimezoneOffset()` 的等价物：查看者相对 UTC 的东向偏移分钟（东八区=+480）。 */
    val tzOffsetMinutes: Int = 0,
    val comparePrevious: Boolean = false,
)

@Serializable
data class TokenUsageTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalTokens: Long = 0,
    val turnCount: Int = 0,
    val conversationCount: Int = 0,
    /** Counted turns 的生成时长之和，不是在 App 里花的时间。 */
    val durationMs: Long = 0,
    val activeDays: Int = 0,
)

@Serializable
data class TokenUsageBreakdownItem(
    /** 项目 folder id / agent wire 名 / 模型名。 */
    val key: String = "",
    val label: String = "",
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalTokens: Long = 0,
    val turnCount: Int = 0,
    val conversationCount: Int = 0,
)

@Serializable
data class TokenUsageReport(
    val rangeStart: String? = null,
    val rangeEnd: String? = null,
    val bucket: String = "day",
    val totals: TokenUsageTotals = TokenUsageTotals(),
    val previousTotals: TokenUsageTotals? = null,
    val byFolder: List<TokenUsageBreakdownItem> = emptyList(),
    val byAgent: List<TokenUsageBreakdownItem> = emptyList(),
    val byModel: List<TokenUsageBreakdownItem> = emptyList(),
    val firstActivityAt: String? = null,
    val lastActivityAt: String? = null,
    /** 扫描撞到行数上限——数字只覆盖最近的一段。 */
    val truncated: Boolean = false,
    // 批v169：轻量版原本「落地即弃」的四块 web 契约，接住以渲染仪表盘
    // （web 端 src/lib/types.ts:2763-2837 为镜像正本）。全部带默认值——
    // 老服务端不带这些字段时反序列化照常成功，UI 走各自空态。
    /** 按 [TokenUsageFilter.bucket] 分桶的时间序列（查看者本地时区）。 */
    val series: List<TokenUsagePoint> = emptyList(),
    /** weekday 0=周一…6=周日 × hour 0–23，查看者本地时区的用量分布。 */
    val heatmap: List<TokenUsageHeatCell> = emptyList(),
    /** 窗口内消耗最高的会话（服务端已排好序）。 */
    val topConversations: List<TokenUsageConversationItem> = emptyList(),
    /** 连续活跃天数。 */
    val streak: TokenUsageStreak = TokenUsageStreak(),
)

/** 一个时间桶的用量（day/week 桶 key 是 `YYYY-MM-DD`，month 是 `YYYY-MM`）。 */
@Serializable
data class TokenUsagePoint(
    val bucketKey: String = "",
    val start: String? = null,
    val end: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalTokens: Long = 0,
    val turnCount: Int = 0,
    val conversationCount: Int = 0,
)

@Serializable
data class TokenUsageHeatCell(
    /** 0 = 周一 … 6 = 周日（查看者本地时区）。 */
    val weekday: Int = 0,
    /** 0–23（查看者本地时区）。 */
    val hour: Int = 0,
    val totalTokens: Long = 0,
    val turnCount: Int = 0,
)

@Serializable
data class TokenUsageConversationItem(
    val conversationId: Int = 0,
    val title: String? = null,
    val agentType: String = "",
    val folderLabel: String? = null,
    val totalTokens: Long = 0,
    val turnCount: Int = 0,
    val lastActivityAt: String? = null,
)

@Serializable
data class TokenUsageStreak(
    val longestDays: Int = 0,
    val currentDays: Int = 0,
    val currentEndsOn: String? = null,
)

/** 项目 facet：alias 已设置时 label 用 alias；筛选列表用 `alias [ name ]`。 */
@Serializable
data class TokenUsageFolderFacet(
    val folderId: Int = 0,
    val label: String = "",
    val name: String = "",
    val alias: String? = null,
    val path: String = "",
    val parentId: Int? = null,
)

@Serializable
data class TokenUsageFacets(
    val folders: List<TokenUsageFolderFacet> = emptyList(),
    val agents: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val dataStart: String? = null,
    val dataEnd: String? = null,
)

@Serializable
data class TokenUsageSyncStatus(
    val totalConversations: Int = 0,
    val syncedConversations: Int = 0,
    val staleConversations: Int = 0,
    val factRows: Long = 0,
    val lastSyncedAt: String? = null,
    val running: Boolean = false,
)

@Serializable
data class TokenUsageSyncResult(
    val scanned: Int = 0,
    val synced: Int = 0,
    val skipped: Int = 0,
    /** 真故障——下一轮会重试；唯一值得提示用户的计数。 */
    val failed: Int = 0,
    /** 彻底丢失的 transcript：facts 保留、不再重试，刻意静默。 */
    val lost: Int = 0,
    val turnsWritten: Int = 0,
    val tokensWritten: Long = 0,
    val prunedConversations: Int = 0,
)

/** Request envelopes — Rust params structs are `rename_all = "camelCase"`. */
@Serializable
data class TokenUsageReportBody(val filter: TokenUsageFilter)

@Serializable
data class TokenUsageSyncBody(val mode: String = "incremental")
