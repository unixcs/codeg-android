package app.codeg.android.core.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import app.codeg.android.MainActivity
import app.codeg.android.R
import app.codeg.android.core.common.LocaleManager
import app.codeg.android.core.data.GlobalEventWatcher
import app.codeg.android.core.data.InteractiveRequestWatcher
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.TaskTransition
import app.codeg.android.core.data.TurnCompleteEvent
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System notifications for "work finished somewhere" (系统级通知体系) — the
 * second tier of the notification stack [InteractiveNotifier] opened: task-board
 * outcomes and turn completions ride `task_results` (IMPORTANCE_DEFAULT, shade
 * only) or — 批v176, when 任务完成·悬浮提醒 is on — the HIGH
 * `task_results_priority` twin (banner/heads-up + lock-screen). All three
 * result chains post with [NotificationCompat.VISIBILITY_PUBLIC] (readable on
 * the lock screen) and the [GROUP_TASK_RESULTS] group with a live summary, so
 * a batch of finishes collapses to one expandable row. Device-outage alerts
 * stay on the silent LOW `device_events` channel, ungrouped.
 *
 * Sources are three hot event streams ([TaskResultWatcher.taskTransitions],
 * [InteractiveRequestWatcher.turnComplete], [TaskResultWatcher.deviceDown]) —
 * replay-free SharedFlows, so unlike the interactive notifier's `combine` over
 * StateFlows there is nothing to "combine" here (a combine over replay-0
 * streams would idle forever waiting for every stream's first emission); each
 * stream gets its own collector that reads the CURRENT settings / foreground
 * mirrors at event time. Settings ride a hot local copy updated by its own
 * collector — flipping a toggle off also cancels that class's outstanding
 * notifications (same cleanup rule as the interactive toggle).
 *
 * Suppression: a turn notification is dropped while that conversation is the
 * foreground detail ([ForegroundConversation]); a task notification is dropped
 * while the task board itself is on screen ([ForegroundBoard]) — the user is
 * already looking at the outcome. Both screen checks are gated on the app
 * being foreground ([AppForeground], 批v161.3 R1): behind the shade or another
 * app the screen mirrors say nothing about what the user sees, and suppressing
 * there would eat exactly the alerts the feature exists for. One-shot events
 * are NOT re-posted after the suppression window passes.
 *
 * Concurrency (批v161.3 R2): the settings collector's toggle-off cleanup and
 * the event handlers both run on the app scope — without a shared lock an
 * event could land its notification AFTER the cleanup cancelled everything,
 * leaving a shade row for a disabled class. The whole 判定+notify+record of
 * each handler is therefore one [lock] critical section (cleanup takes the
 * same lock), closing the window.
 *
 * 批v176: the firehose's `task://changed` frames reach the automation collector
 * below and POKE [TaskResultWatcher] into an immediate poll — a finished task
 * announces in seconds instead of at the next 30s tick. The poll diff itself
 * stays the event source (baseline dedupe); the poke only shortens the wait.
 *
 * Notification ids must be STABLE ACROSS PROCESSES (same contract as
 * [InteractiveNotifier.notificationId]): pure hash formulas, never a counter.
 * They are distinct from the interactive (31·) and keep-alive (42) ids by their
 * multipliers (37 / 41 / 43); exact cross-family collisions are theoretically
 * possible under Int wraparound but require a crafted 4-tuple over uniform
 * hashes — the same accepted risk the 31· formula shipped with in 批v160 W8.
 */
/** One settled automation run (批v173 C7) — decoded from the firehose payload
 *  by [TaskResultNotifier.parseRunSettled]. */
internal data class RunSettled(
    val automationId: Int,
    val runId: Int,
    val status: String,
)

@Singleton
class TaskResultNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watcher: TaskResultWatcher,
    private val interactionWatcher: InteractiveRequestWatcher,
    private val appForeground: AppForeground,
    private val appSettingsStore: AppSettingsStore,
    /** 批v173 C7: run-settled pushes ride the process-wide firehose listener. */
    private val globalEvents: GlobalEventWatcher,
    /** 批v173 C7: the payload carries only ids — the automation's display name
     *  is refetched through the device client at event time. */
    private val repository: ServerRepository,
    @ApplicationScope appScope: CoroutineScope,
) {
    /** key → notification id for outstanding task_results posts. LinkedHashMap
     *  (insertion-ordered) so the [POSTED_CAP] eviction can drop the oldest —
     *  all access happens under [lock]. */
    private val postedResults = LinkedHashMap<String, Int>()

    /** key → notification id for outstanding device_events posts (same cap). */
    private val postedDevice = LinkedHashMap<String, Int>()

    private val lock = Any()

    /** Hot settings copy for event-time reads (defaults until DataStore lands). */
    @Volatile
    private var settings: AppSettings = AppSettings()

    init {
        appScope.launch {
            appSettingsStore.settings.collect { current ->
                val previous = settings
                settings = current
                // Toggle-off cleanup (mirrors InteractiveNotifier.render): each
                // switch cancels its own class; flipping back on just resumes.
                // Same lock as the handlers — a handler either finishes its
                // post+record BEFORE this cleanup (the row is cancelled below)
                // or sees the fresh setting and never posts.
                if (!current.notifyTaskResultsEnabled && previous.notifyTaskResultsEnabled) {
                    synchronized(lock) {
                        val nm = notificationManager()
                        postedResults.values.forEach(nm::cancel)
                        postedResults.clear()
                        // 批v176: the group summary is the last row left standing
                        // once every child is cancelled — take it down too.
                        nm.cancel(GROUP_SUMMARY_ID)
                    }
                }
                if (!current.notifyDeviceEventsEnabled && previous.notifyDeviceEventsEnabled) {
                    synchronized(lock) {
                        val nm = notificationManager()
                        postedDevice.values.forEach(nm::cancel)
                        postedDevice.clear()
                    }
                }
            }
        }
        appScope.launch {
            watcher.taskTransitions.collect { transition ->
                runCatching { onTaskTransition(transition) }
                    .onFailure { Log.w(TAG, "task transition post failed") }
            }
        }
        appScope.launch {
            interactionWatcher.turnComplete.collect { event ->
                runCatching { onTurnComplete(event) }
                    .onFailure { Log.w(TAG, "turn-complete post failed") }
            }
        }
        appScope.launch {
            watcher.deviceDown.collect { event ->
                runCatching { onDeviceDown(event) }
                    .onFailure { Log.w(TAG, "device-down post failed") }
            }
        }
        // 批v173 C7: automation://changed → run_settled alerts. Only the two
        // outcomes a user waits on (succeeded/failed) notify — cancelled/skipped
        // are scheduler noise. The name lookup runs BEFORE the lock: the lock
        // guards post+record, never a network call.
        appScope.launch {
            globalEvents.events.collect { event ->
                // 批v176: task://changed on the same firehose — poke the poller
                // so a finished task announces in seconds (debounced inside the
                // watcher); the poll diff stays the authoritative event source.
                if (event.channel == GlobalEventWatcher.CHANNEL_TASK) watcher.poke()
                if (event.channel != GlobalEventWatcher.CHANNEL_AUTOMATION) return@collect
                val settled = parseRunSettled(event.payload) ?: return@collect
                if (settled.status !in NOTIFYABLE_RUN_STATUSES) return@collect
                runCatching { onAutomationSettled(event.serverId, settled) }
                    .onFailure { Log.w(TAG, "automation settled post failed") }
            }
        }
    }

    /** 批v176: which channel result notifications post on — the HIGH twin when
     *  任务完成·悬浮提醒 is on (banner + lock-screen), the original DEFAULT one
     *  as the shade-only fallback. Reads the hot settings copy; callers run
     *  under [lock]. */
    private fun resultChannel(): String =
        if (settings.notifyTaskHeadsUp) InteractiveNotifier.CHANNEL_TASK_RESULTS_PRIORITY
        else InteractiveNotifier.CHANNEL_TASK_RESULTS

    private fun onTaskTransition(transition: TaskTransition) = synchronized(lock) {
        if (!settings.notifyTaskResultsEnabled) return
        // Looking at the board right now → the alert would be noise. One-shot:
        // leaving the board later does not resurrect it. The app must also be
        // foreground (批v161.3 R1): the board mirror is stale the moment the
        // user leaves the app, and background alerts are the point.
        if (appForeground.fg.value && ForegroundBoard.onBoard.value) return
        val ctx = LocaleManager.wrapReadOnly(context)
        val id = taskNotificationId(transition.serverId, transition.taskId)
        val text = ctx.getString(
            if (transition.to == "done") R.string.notify_task_done else R.string.notify_task_failed,
        )
        val notification = NotificationCompat.Builder(ctx, resultChannel())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ellipsis(transition.title))
            .setContentText("$text · ${transition.serverName}")
            .setContentIntent(boardContentIntent(ctx))
            // 批v176: readable on the lock screen (names the task, no content)
            // + group so a batch of finishes collapses to one expandable row.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(GROUP_TASK_RESULTS)
            .setAutoCancel(true)
            // Re-posts for the same task (a re-run landing in the same id) must
            // stay silent after the first alert, like the interactive builder.
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        // POST_NOTIFICATIONS revoked → SecurityException lands here, logged, not fatal.
        runCatching { notificationManager().notify(id, notification) }
            .onFailure { Log.w(TAG, "notify task result failed: ${it.message}") }
            .onSuccess { putCapped(postedResults, "${transition.serverId}|${transition.taskId}", id) }
        postGroupSummary(ctx)
    }

    private fun onTurnComplete(event: TurnCompleteEvent) = synchronized(lock) {
        // 批v179: its own gate (was lumped in with task results). 回合完成 is the
        // noisiest alert class — the 三档 split puts it OFF by default, on its
        // own switch, instead of piggybacking 任务完成.
        if (!settings.notifyTurnEnabled) return
        // Viewing the conversation right now → the alert would be noise. Same
        // foreground gate as the board check above (批v161.3 R1).
        if (appForeground.fg.value &&
            ForegroundConversation.ref.value == SessionRef(event.serverId, event.conversationId)
        ) return
        val ctx = LocaleManager.wrapReadOnly(context)
        val id = turnNotificationId(event.serverId, event.conversationId)
        val title = event.conversationTitle?.takeIf { it.isNotBlank() }
            ?: ctx.getString(R.string.notify_turn_title)
        val notification = NotificationCompat.Builder(ctx, resultChannel())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ellipsis(title))
            .setContentText(ctx.getString(R.string.notify_turn_done))
            .setContentIntent(
                conversationContentIntent(ctx, event.serverId, event.conversationId),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(GROUP_TASK_RESULTS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { notificationManager().notify(id, notification) }
            .onFailure { Log.w(TAG, "notify turn complete failed: ${it.message}") }
            .onSuccess { putCapped(postedResults, "turn|${event.serverId}|${event.conversationId}", id) }
        postGroupSummary(ctx)
    }

    /** 批v173 C7: resolve the automation's display name (and the device's) at
     *  event time. Never throws: a gone device / dead client / failed lookup
     *  all degrade to nulls → the fallback title below. */
    private suspend fun resolveAutomationContext(
        serverId: String,
        automationId: Int,
    ): Pair<String?, String?> {
        val profile = try {
            repository.profiles.first().find { it.id == serverId }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null to null
        val name = try {
            repository.client(profile)?.automationGet(automationId)?.name
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return name to profile.name
    }

    private suspend fun onAutomationSettled(serverId: String, settled: RunSettled) {
        // Quick gate on the cached snapshot BEFORE the network lookup: with the
        // toggle off, every run_settled must not spend an automationGet. The
        // authoritative re-check inside the lock stays.
        if (!settings.notifyTaskResultsEnabled) return
        val (automationName, serverName) = resolveAutomationContext(serverId, settled.automationId)
        synchronized(lock) {
            if (!settings.notifyTaskResultsEnabled) return
            // Automations surface on the board (the 琥珀区 row) — looking at the
            // board right now means the alert would be noise. Same foreground
            // gate as the task/turn checks (批v161.3 R1).
            if (appForeground.fg.value && ForegroundBoard.onBoard.value) return
            val ctx = LocaleManager.wrapReadOnly(context)
            val id = automationNotificationId(serverId, settled.runId)
            val title = automationName?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.notify_automation_fallback, settled.automationId)
            val outcome = ctx.getString(
                if (settled.status == "succeeded") R.string.notify_automation_succeeded
                else R.string.notify_automation_failed,
            )
            val text = serverName?.takeIf { it.isNotBlank() }?.let { "$outcome · $it" } ?: outcome
            val notification = NotificationCompat.Builder(ctx, resultChannel())
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(ellipsis(title))
                .setContentText(text)
                .setContentIntent(boardContentIntent(ctx))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setGroup(GROUP_TASK_RESULTS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            runCatching { notificationManager().notify(id, notification) }
                .onFailure { Log.w(TAG, "notify automation settled failed: ${it.message}") }
                .onSuccess { putCapped(postedResults, "automation|$serverId|${settled.runId}", id) }
            postGroupSummary(ctx)
        }
    }

    // Declared Unit: the onSuccess chain's Result would otherwise become the
    // expression body's inferred type and clash with the bare `return` above.
    private fun onDeviceDown(event: DeviceDownEvent): Unit = synchronized(lock) {
        if (!settings.notifyDeviceEventsEnabled) return
        val ctx = LocaleManager.wrapReadOnly(context)
        val id = deviceNotificationId(event.serverId)
        val notification = NotificationCompat.Builder(ctx, InteractiveNotifier.CHANNEL_DEVICE_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.serverName)
            .setContentText(ctx.getString(R.string.notify_device_offline))
            .setContentIntent(boardContentIntent(ctx))
            // 批v176: an outage alert you can't read on the lock screen loses
            // most of its point — show it (it names the device, no content).
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .build()
        runCatching { notificationManager().notify(id, notification) }
            .onFailure { Log.w(TAG, "notify device down failed: ${it.message}") }
            .onSuccess { putCapped(postedDevice, event.serverId, id) }
    }

    /** Cap on the outstanding-post ledgers: the key spaces are unbounded in
     *  principle (one per task/turn ever seen), and the only thing these maps
     *  buy is the toggle-off cleanup. Evicting the oldest entry past the cap
     *  loses the cleanup for that one id — the shade enforces its own far
     *  smaller limits long before 128 rows. 批v161.3 R2; caller holds [lock]. */
    private fun putCapped(map: LinkedHashMap<String, Int>, key: String, id: Int) {
        while (map.size >= POSTED_CAP) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
        map[key] = id
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 批v176: refresh the result group's summary (caller holds [lock] and just
     *  posted a grouped child; [ctx] is the localized wrapper). One summary per
     *  group: collapsed batches show it as the single row, expanded shows the
     *  children. Silent — the children carry the alert; without this the first
     *  summary post would double-alert alongside the child. The system
     *  auto-dismisses the summary when its last child is dismissed. */
    private fun postGroupSummary(ctx: Context) {
        val count = postedResults.size
        if (count <= 0) return
        val summary = NotificationCompat.Builder(ctx, resultChannel())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.notify_group_summary_title))
            .setContentText(ctx.getString(R.string.notify_group_summary_text, count))
            .setContentIntent(boardContentIntent(ctx))
            .setGroup(GROUP_TASK_RESULTS)
            .setGroupSummary(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { notificationManager().notify(GROUP_SUMMARY_ID, summary) }
            .onFailure { Log.w(TAG, "notify group summary failed: ${it.message}") }
    }

    /** 批v176: the settings page's 「发测试通知」 — one real notification through
     *  the same channel-selection path the live chains use, so heads-up /
     *  lock-screen behaviour can be verified on the spot. Deliberately NOT
     *  grouped: a diagnostic must not pollute the result group's ledger.
     *  批v179: the id increments per tap — a fixed id made every re-tap an
     *  UPDATE of the old notification, and MIUI only banners NEW ids (the
     *  "测试通知只出现在任务栏不弹横幅" second root cause). */
    fun postTestNotification() {
        val ctx = LocaleManager.wrapReadOnly(context)
        val notification = NotificationCompat.Builder(ctx, resultChannel())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.notify_test_title))
            .setContentText(ctx.getString(R.string.notify_test_text))
            .setContentIntent(boardContentIntent(ctx))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { notificationManager().notify(testNotificationId.incrementAndGet(), notification) }
            .onFailure { Log.w(TAG, "notify test failed: ${it.message}") }
    }

    /** Task board deep link — `codeg://tab/tasks` (MainShell routes it to
     *  HomeTab.TASKS; request code distinct from the other notifiers'). */
    private fun boardContentIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            REQUEST_CODE_BOARD,
            Intent(Intent.ACTION_VIEW, Uri.parse("codeg://tab/tasks"), ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Same deep-link shape the interactive alert uses (device id rides along —
     *  conversation ids are server-local). 批v161.3 R3: the request code is now
     *  derived PER (serverId, conversationId) — the shared constant +
     *  FLAG_UPDATE_CURRENT let one conversation's PendingIntent overwrite
     *  another's, so tapping conversation A's alert could deep-link into B. */
    private fun conversationContentIntent(ctx: Context, serverId: String, conversationId: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            turnRequestCode(serverId, conversationId),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("codeg://conversation/$conversationId?serverId=${Uri.encode(serverId)}"),
                ctx,
                MainActivity::class.java,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val TAG = "TaskResultNotifier"

        // Large fixed offset: the interactive notifier uses raw conversationId
        // as its request code, and small ids (101/102 are real early-session
        // ids) would let these two PendingIntents overwrite each other under
        // FLAG_UPDATE_CURRENT — tapping one alert could deep-link elsewhere.
        private const val REQUEST_CODE_BOARD = 1_000_101

        /** 批v161.3 R3: base band for the per-conversation deep-link codes —
         *  [turnRequestCode] parks every code between 1e9 and 1e9 + 2^30, well
         *  clear of the interactive notifier's raw-conversationId codes and the
         *  board's fixed REQUEST_CODE_BOARD above. */
        private const val REQUEST_CODE_TURN_BASE = 1_000_000_000

        /** 批v161.3 R3: one code per (serverId, conversationId) — conversation
         *  ids are server-local, so the device id must join the hash. The
         *  30-bit mask keeps the sum inside the positive base band (a raw Int
         *  hash could go negative); residual in-band collisions are the same
         *  accepted hash risk as the notification-id formulas. */
        fun turnRequestCode(serverId: String, conversationId: Int): Int =
            REQUEST_CODE_TURN_BASE + ((31 * serverId.hashCode() + conversationId) and 0x3FFFFFFF)

        /** Outstanding-post ledger cap (批v161.3 R2). */
        private const val POSTED_CAP = 128

        /** 批v176: Android notification group key for every result chain — a
         *  batch of finishes collapses under one expandable row. */
        private const val GROUP_TASK_RESULTS = "task_results"

        /** Stable group-summary notification id (53 ≠ 37/41/43/47 multipliers
         *  and ≠ any 31· hash family member by a practical margin). */
        private val GROUP_SUMMARY_ID = 53 * GROUP_TASK_RESULTS.hashCode()

        /** 批v176: fixed id for the diagnostic test notification (a re-post
         *  replaces the previous test instead of stacking). 批v179: the seed for
         *  the INCREMENTING test id — a fixed id turned re-taps into updates
         *  and MIUI stopped banners for them; the counter hands every tap a
         *  fresh id in a dedicated band. Process death resets the counter to the
         *  seed, so the first test after a restart may update one stale id once
         *  before climbing again — acceptable for a diagnostic, not persisted. */
        private val TEST_NOTIFICATION_ID = 59 * "test_notification".hashCode()
        private val testNotificationId = java.util.concurrent.atomic.AtomicInteger(TEST_NOTIFICATION_ID)

        /** Notification content titles render on one line — keep the truncation
         *  tight so the shade never has to ellipsize a runaway task title. */
        private const val TITLE_MAX = 64

        /** Stable per-task notification id (device id joins the hash — task ids
         *  are server-local). 37 ≠ 31 (interactive) / 41 (turn) / 43 (device). */
        fun taskNotificationId(serverId: String, taskId: Int): Int =
            37 * serverId.hashCode() + taskId.hashCode()

        /** Stable per-conversation turn id. */
        fun turnNotificationId(serverId: String, conversationId: Int): Int =
            41 * serverId.hashCode() + conversationId

        /** One outstanding outage alert per device. */
        fun deviceNotificationId(serverId: String): Int =
            43 * serverId.hashCode()

        /** 批v173 C7: the run outcomes worth a shade row (cancelled/skipped are
         *  scheduler noise the user never waits on). */
        internal val NOTIFYABLE_RUN_STATUSES = setOf("succeeded", "failed")

        /** Stable per-run automation notification id. 47 ≠ 37 (task) / 41
         *  (turn) / 43 (device) — a run id may equal a task id on the wire. */
        fun automationNotificationId(serverId: String, runId: Int): Int =
            47 * serverId.hashCode() + runId.hashCode()

        /** 批v173 C7: pure decoder for the firehose's run_settled payload —
         *  `{"kind":"run_settled","automation_id":N,"run_id":N,"status":"..."}`.
         *  null = anything malformed or a different kind on the same channel
         *  (the channel carries other automation frames too). Exposed internal
         *  and pure so the contract is unit-testable without a socket. */
        internal fun parseRunSettled(payload: JsonObject): RunSettled? {
            val kind = (payload["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (kind != "run_settled") return null
            val automationId = (payload["automation_id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: return null
            val runId = (payload["run_id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: return null
            val status = (payload["status"] as? JsonPrimitive)?.contentOrNull ?: return null
            return RunSettled(automationId, runId, status)
        }

        /** Collapse line breaks first (a multi-line title would break the one-
         *  line shade row), then count CHARACTERS as code points (批v161.3 R2):
         *  String.length counts UTF-16 units, so a CJK-heavy title was cut at
         *  half its visible length and a surrogate pair split across the cut
         *  turned into a stray replacement char. */
        fun ellipsis(title: String): String {
            val flat = title.replace("\n", " ")
            if (flat.codePointCount(0, flat.length) <= TITLE_MAX) return flat
            return flat.substring(0, flat.offsetByCodePoints(0, TITLE_MAX)) + "…"
        }
    }
}
