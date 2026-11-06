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
import app.codeg.android.core.data.InteractiveRequestWatcher
import app.codeg.android.core.data.TaskTransition
import app.codeg.android.core.data.TurnCompleteEvent
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System notifications for "work finished somewhere" (系统级通知体系) — the
 * second tier of the notification stack [InteractiveNotifier] opened: task-board
 * outcomes and turn completions on the IMPORTANCE_DEFAULT `task_results`
 * channel (shade + sound, never heads-up), device-outage alerts on the silent
 * LOW `device_events` channel.
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
 * Notification ids must be STABLE ACROSS PROCESSES (same contract as
 * [InteractiveNotifier.notificationId]): pure hash formulas, never a counter.
 * They are distinct from the interactive (31·) and keep-alive (42) ids by their
 * multipliers (37 / 41 / 43); exact cross-family collisions are theoretically
 * possible under Int wraparound but require a crafted 4-tuple over uniform
 * hashes — the same accepted risk the 31· formula shipped with in 批v160 W8.
 */
@Singleton
class TaskResultNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watcher: TaskResultWatcher,
    private val interactionWatcher: InteractiveRequestWatcher,
    private val appForeground: AppForeground,
    private val appSettingsStore: AppSettingsStore,
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
    }

    private fun onTaskTransition(transition: TaskTransition) = synchronized(lock) {
        if (!settings.notifyTaskResultsEnabled) return
        // Looking at the board right now → the alert would be noise. One-shot:
        // leaving the board later does not resurrect it. The app must also be
        // foreground (批v161.3 R1): the board mirror is stale the moment the
        // user leaves the app, and background alerts are the point.
        if (appForeground.fg.value && ForegroundBoard.onBoard.value) return
        val ctx = LocaleManager.wrap(context)
        val id = taskNotificationId(transition.serverId, transition.taskId)
        val text = ctx.getString(
            if (transition.to == "done") R.string.notify_task_done else R.string.notify_task_failed,
        )
        val notification = NotificationCompat.Builder(ctx, InteractiveNotifier.CHANNEL_TASK_RESULTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ellipsis(transition.title))
            .setContentText("$text · ${transition.serverName}")
            .setContentIntent(boardContentIntent(ctx))
            .setAutoCancel(true)
            // Re-posts for the same task (a re-run landing in the same id) must
            // stay silent after the first alert, like the interactive builder.
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        // POST_NOTIFICATIONS revoked → SecurityException lands here, logged, not fatal.
        runCatching { notificationManager().notify(id, notification) }
            .onFailure { Log.w(TAG, "notify task result failed: ${it.message}") }
        putCapped(postedResults, "${transition.serverId}|${transition.taskId}", id)
    }

    private fun onTurnComplete(event: TurnCompleteEvent) = synchronized(lock) {
        if (!settings.notifyTaskResultsEnabled) return
        // Viewing the conversation right now → the alert would be noise. Same
        // foreground gate as the board check above (批v161.3 R1).
        if (appForeground.fg.value &&
            ForegroundConversation.ref.value == SessionRef(event.serverId, event.conversationId)
        ) return
        val ctx = LocaleManager.wrap(context)
        val id = turnNotificationId(event.serverId, event.conversationId)
        val title = event.conversationTitle?.takeIf { it.isNotBlank() }
            ?: ctx.getString(R.string.notify_turn_title)
        val notification = NotificationCompat.Builder(ctx, InteractiveNotifier.CHANNEL_TASK_RESULTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ellipsis(title))
            .setContentText(ctx.getString(R.string.notify_turn_done))
            .setContentIntent(
                conversationContentIntent(ctx, event.serverId, event.conversationId),
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { notificationManager().notify(id, notification) }
            .onFailure { Log.w(TAG, "notify turn complete failed: ${it.message}") }
        putCapped(postedResults, "turn|${event.serverId}|${event.conversationId}", id)
    }

    private fun onDeviceDown(event: DeviceDownEvent) = synchronized(lock) {
        if (!settings.notifyDeviceEventsEnabled) return
        val ctx = LocaleManager.wrap(context)
        val id = deviceNotificationId(event.serverId)
        val notification = NotificationCompat.Builder(ctx, InteractiveNotifier.CHANNEL_DEVICE_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.serverName)
            .setContentText(ctx.getString(R.string.notify_device_offline))
            .setContentIntent(boardContentIntent(ctx))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .build()
        runCatching { notificationManager().notify(id, notification) }
            .onFailure { Log.w(TAG, "notify device down failed: ${it.message}") }
        putCapped(postedDevice, event.serverId, id)
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
     *  conversation ids are server-local); request codes stay disjoint so the
     *  two notifiers never mutate each other's PendingIntents. */
    private fun conversationContentIntent(ctx: Context, serverId: String, conversationId: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            REQUEST_CODE_TURN,
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

        // Large fixed offsets: the interactive notifier uses raw conversationId
        // as its request code, and small ids (101/102 are real early-session
        // ids) would let these two PendingIntents overwrite each other under
        // FLAG_UPDATE_CURRENT — tapping one alert could deep-link elsewhere.
        private const val REQUEST_CODE_BOARD = 1_000_101
        private const val REQUEST_CODE_TURN = 1_000_102

        /** Outstanding-post ledger cap (批v161.3 R2). */
        private const val POSTED_CAP = 128

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
