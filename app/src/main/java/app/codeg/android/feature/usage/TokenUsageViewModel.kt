package app.codeg.android.feature.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.TokenUsageFilter
import app.codeg.android.core.model.TokenUsageHeatCell
import app.codeg.android.core.model.TokenUsageReport
import app.codeg.android.core.model.TokenUsageTotals
import app.codeg.android.core.model.TokenUsageSyncStatus
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.network.isUnsupportedMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 批v169 对齐 web：今日/近 7 天/近 30 天/近 90 天四档。 */
enum class UsagePeriod(val backDays: Long) { TODAY(0), WEEK(6), MONTH(29), QUARTER(89) }

/** 用量分布子视角（RankedBarList 的三个数据面）。 */
enum class UsageBreakdownMode { FOLDER, AGENT, MODEL }

/**
 * Build the report filter for a period. Bucket granularity is always `day`
 * (趋势柱状图按天); the range is [local midnight … now) in the device viewer's
 * timezone — the server buckets by the reported offset, the client never
 * converts timestamps itself. 批v169 起 comparePrevious=true：服务端回填
 * 上一周期 totals，Hero 卡渲染涨跌徽标（previous 全零时 UI 自行隐藏）。
 */
fun usageFilter(period: UsagePeriod, now: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): TokenUsageFilter {
    val startDay = if (period == UsagePeriod.TODAY) now else now.minusDays(period.backDays)
    val start = startDay.atStartOfDay(zone).toInstant().toString()
    val offsetMinutes = zone.rules.getOffset(Instant.now()).totalSeconds / 60
    return TokenUsageFilter(
        start = start,
        end = null,
        bucket = "day",
        tzOffsetMinutes = offsetMinutes,
        comparePrevious = true,
    )
}

/** K/M compact token counts — Locale.ROOT so digits stay ASCII everywhere. */
fun formatTokenCount(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.2fB", n / 1_000_000_000.0)
    n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

/** 缓存命中率 = cache read / total（0 除保护：无用量时 0）。
 *  口径与 web「缓存读取 1380.3M 95.1%」一致——分母是全部四类 token 之和。 */
fun cacheHitRate(t: TokenUsageTotals): Float {
    val total = t.totalTokens
    if (total <= 0) return 0f
    return (t.cacheReadTokens.toFloat() / total).coerceIn(0f, 1f)
}

/** 夜猫子占比：作息热力图里 22:00–05:59 的 token 占比（0–1；无数据 0）。
 *  语义对齐 web 深夜码农徽标「45% 的 Token 烧在天黑之后」。 */
fun nightOwlFraction(cells: List<TokenUsageHeatCell>): Float {
    val total = cells.sumOf { it.totalTokens }
    if (total <= 0) return 0f
    val night = cells.filter { it.hour >= 22 || it.hour < 6 }.sumOf { it.totalTokens }
    return (night.toFloat() / total).coerceIn(0f, 1f)
}

/** 周期环比：本周期相对上一周期的涨跌比例（1.0 = +100%）。
 *  previous 缺失或全零返回 null——UI 隐藏徽标而不是渲染 +∞。 */
fun deltaPercent(current: Long, previous: Long): Float? {
    if (previous <= 0) return null
    return (current - previous).toFloat() / previous
}

/** 「生成时长 100h 24m」紧凑口径。 */
fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** 作息峰值时段（token 最高的 hour，24 小时制整数；空数据 null）。 */
fun peakHour(cells: List<TokenUsageHeatCell>): Int? {
    val best = cells.maxByOrNull { it.totalTokens } ?: return null
    return if (best.totalTokens <= 0) null else best.hour
}

/**
 * Per-device fan-out, same discipline as [app.codeg.android.feature.automation.AutomationViewModel]:
 * runCatching + 10s budget per device, failure keeps the stale slice, a
 * generation counter arbitrates races. No polling — usage data only refreshes
 * on entry, on period switch, on pull and after a manual sync.
 */
@HiltViewModel
class TokenUsageViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    data class DeviceUsage(
        val serverId: String,
        val deviceName: String,
        val status: DeviceStatus = DeviceStatus.LOADING,
        val error: String? = null,
        val report: TokenUsageReport? = null,
        val syncStatus: TokenUsageSyncStatus? = null,
        /** Old server without the token_usage surface. */
        val supported: Boolean = true,
    ) {
        val isFailed: Boolean get() = status == DeviceStatus.FAILED
    }

    enum class DeviceStatus { LOADING, OK, FAILED }

    data class UiState(
        val devices: List<DeviceUsage> = emptyList(),
        val period: UsagePeriod = UsagePeriod.TODAY,
        // 批v169: 用量分布的子视角，全局一份（所有设备卡同一视角，避免
        // 每卡各记一个的错位感）。
        val breakdownMode: UsageBreakdownMode = UsageBreakdownMode.FOLDER,
        val lastRefreshed: Instant? = null,
        val hasLoaded: Boolean = false,
        val syncing: Boolean = false,
        val actionNote: String? = null,
        val actionError: String? = null,
    ) {
        val anySupported: Boolean get() = devices.any { it.supported && !it.isFailed }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _refreshTick = MutableStateFlow(0)
    private var generation = 0
    private var syncSeq = 0

    init {
        viewModelScope.launch { loadLoop() }
    }

    fun refresh() { _refreshTick.value += 1 }

    fun setPeriod(period: UsagePeriod) {
        if (_ui.value.period == period) return
        _ui.update { it.copy(period = period, lastRefreshed = null) }
        refresh()
    }

    /** 纯本地视角切换——数据本来就三面全回，不触发网络刷新。 */
    fun setBreakdownMode(mode: UsageBreakdownMode) {
        _ui.update { it.copy(breakdownMode = mode) }
    }

    fun clearActionNote() { _ui.update { it.copy(actionNote = null, actionError = null) } }

    /** Incremental sync on every in-scope supported device, then a data
     *  refresh. Fire-and-forget with a seq guard; the note surfaces the
     *  only counter the web UI also toasts about: failures. Every exit path
     *  (normal / error / cancel) clears `syncing` — a DataStore hiccup inside
     *  [inScopeProfiles] must not leave the sync button spinning forever
     *  (fleet M3 审查 H2). */
    fun syncAll() {
        if (_ui.value.syncing) return
        _ui.update { it.copy(syncing = true, actionNote = null, actionError = null) }
        val seq = ++syncSeq
        viewModelScope.launch {
            try {
                syncAllInner(seq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(syncing = false, actionError = e.message ?: "同步失败") }
            }
        }
    }

    private suspend fun syncAllInner(seq: Int) {
        val profiles = inScopeProfiles()
        var synced = 0
        var failed = 0
        var unsupported = 0
        for (profile in profiles) {
            if (seq != syncSeq) return
            val result = runCatching {
                val client = repository.client(profile)
                    ?: throw IllegalStateException("Token 缺失，无法连接该设备")
                withTimeoutOrNull(SYNC_TIMEOUT_MS) { client.tokenUsageSync("incremental") }
            }
            if (seq != syncSeq) return
            result.fold(
                onSuccess = { r ->
                    if (r == null) failed += 1 else {
                        synced += r.synced
                        failed += r.failed
                        refreshDeviceSlice(profile.id)
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    if (isUnsupportedMethod(e)) unsupported += 1 else failed += 1
                },
            )
        }
        if (seq != syncSeq) return
        _ui.update {
            it.copy(
                syncing = false,
                actionNote = "同步完成：新增 $synced 条会话记录" +
                    (if (failed > 0) "，$failed 条失败" else "") +
                    (if (unsupported > 0) "，$unsupported 台设备不支持" else ""),
            )
        }
        refresh()
    }

    // No periodic poll: usage data is not live. Fetch on entry, on manual
    // refresh/sync, and whenever the device scope (selected/all) changes.
    private fun loadLoop() {
        viewModelScope.launch {
            combine(
                _refreshTick,
                repository.selectedId,
                repository.tasksAll,
            ) { tick, selectedId, allMode -> Triple(tick, selectedId, allMode) }
                .distinctUntilChanged()
                .retryWhen { _, attempt ->
                        // 批v165 R1 (P0): a DataStore read failure upstream must resubscribe — a
                        // bare collector dying here is the v130 crash class (process kill from a
                        // screen entry) AND "deaf for life" if it never re-arms.
                        kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                        true
                }
                .collectLatest {
                    // Any distinct emission (entry / refresh / sync / scope
                    // switch) is a full re-slice; collectLatest cancels the
                    // in-flight round on switch, the generation guard covers
                    // the race.
                    fetchAll()
                }
        }
    }

    private suspend fun fetchAll() {
        try {
            fetchAllInner()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            android.util.Log.w("TokenUsageViewModel", "fetch round failed")
        }
    }

    private suspend fun fetchAllInner() {
        val profiles = inScopeProfiles()
        if (profiles.isEmpty()) {
            _ui.update { it.copy(devices = emptyList(), lastRefreshed = Instant.now(), hasLoaded = true) }
            return
        }
        generation += 1
        val gen = generation
        _ui.update { state ->
            state.copy(
                devices = state.devices.filter { d -> profiles.any { it.id == d.serverId } }.let { kept ->
                    profiles.map { p ->
                        kept.firstOrNull { it.serverId == p.id }
                            ?.copy(status = DeviceStatus.LOADING, error = null)
                            ?: DeviceUsage(p.id, p.name)
                    }
                },
            )
        }
        coroutineScope {
            profiles.forEach { profile ->
                launch { fetchDevice(profile, gen) }
            }
        }
        if (gen == generation) _ui.update { it.copy(lastRefreshed = Instant.now(), hasLoaded = true) }
    }

    private suspend fun fetchDevice(profile: ServerProfile, gen: Int) {
        val result = runCatching {
            val client = repository.client(profile)
                ?: throw IllegalStateException("Token 缺失，无法加载该设备")
            withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                val filter = usageFilter(_ui.value.period)
                client.tokenUsageReport(filter) to client.tokenUsageStatus()
            } ?: throw java.util.concurrent.TimeoutException("device timeout")
        }
        if (gen != generation) return
        result.fold(
            onSuccess = { pair ->
                _ui.update { state ->
                    state.copy(
                        devices = state.devices.map {
                            if (it.serverId == profile.id) {
                                it.copy(
                                    status = DeviceStatus.OK, error = null,
                                    report = pair.first, syncStatus = pair.second, supported = true,
                                )
                            } else it
                        },
                    )
                }
            },
            onFailure = { e ->
                if (e is CancellationException) throw e
                val unsupported = isUnsupportedMethod(e)
                _ui.update { state ->
                    state.copy(
                        devices = state.devices.map {
                            if (it.serverId == profile.id) {
                                it.copy(
                                    status = if (unsupported) DeviceStatus.OK else DeviceStatus.FAILED,
                                    error = (e as? ApiError)?.displayMessage() ?: e.message ?: "加载失败",
                                    supported = !unsupported,
                                )
                            } else it
                        },
                    )
                }
            },
        )
    }

    /** Single-device data re-pull after its sync finished (no generation bump —
     *  the slice write is keyed by serverId and sync is user-paced). A failure
     *  here is harmless: syncAll's trailing refresh() re-slices from the flows
     *  anyway; only cancellation propagates (fleet M3 审查 L1). */
    private suspend fun refreshDeviceSlice(serverId: String) {
        val profile = try {
            repository.profiles.first().firstOrNull { it.id == serverId } ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val result = runCatching {
            val client = repository.client(profile) ?: return
            val filter = usageFilter(_ui.value.period)
            withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                client.tokenUsageReport(filter) to client.tokenUsageStatus()
            }
        }
        result.getOrNull()?.let { pair ->
            _ui.update { state ->
                state.copy(
                    devices = state.devices.map {
                        if (it.serverId == serverId) {
                            it.copy(status = DeviceStatus.OK, error = null, report = pair.first, syncStatus = pair.second)
                        } else it
                    },
                )
            }
        }
    }

    /** Retry one failed device — not a global refresh (fleet M3 审查 M1:
     *  sibling cards must not flash LOADING / re-fan-out). Uses the current
     *  generation so a loadLoop round starting meanwhile discards this write. */
    fun retry(serverId: String) {
        viewModelScope.launch {
            _ui.update { state ->
                state.copy(
                    devices = state.devices.map {
                        if (it.serverId == serverId) it.copy(status = DeviceStatus.LOADING, error = null) else it
                    },
                )
            }
            val profile = try {
                repository.profiles.first().firstOrNull { it.id == serverId } ?: return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            fetchDevice(profile, generation)
        }
    }

    private suspend fun inScopeProfiles(): List<ServerProfile> {
        val profiles = repository.profiles.first()
        val allMode = repository.tasksAll.value
        val selectedId = repository.selectedId.first()
        return if (allMode) {
            profiles.filter { it.visible && it.includeInAll }
        } else {
            profiles.filter { it.id == selectedId }
        }
    }

    companion object {
        // 批v165 R1 (P2): 130s — a hair ABOVE the server's 120s sync budget so the
// client never times out first and turns a slow-but-successful sync into a
// phantom failure.
private const val SYNC_TIMEOUT_MS = 130_000L
    }
}
