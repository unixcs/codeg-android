package app.codeg.android.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.codeg.android.MainActivity
import app.codeg.android.R
import app.codeg.android.core.data.InteractionKind
import app.codeg.android.core.data.InteractiveRequestWatcher
import app.codeg.android.core.data.PendingInteraction
import app.codeg.android.core.common.LocaleManager
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conversation the user is LOOKING AT right now (detail on top), mirrored
 * here by the shell so the notifier can suppress the "agent needs you" alert
 * for the session already on screen (批v160 W8: 屏上抑制——UI 置顶无妨，仅通知
 * 抑制用它). null = not on a conversation detail.
 */
object ForegroundConversation {
    private val _ref = MutableStateFlow<SessionRef?>(null)
    val ref: StateFlow<SessionRef?> = _ref.asStateFlow()

    fun set(ref: SessionRef?) {
        _ref.value = ref
    }
}

/**
 * 系统级通知体系: whether the TASK BOARD tab is the top of the nav stack right
 * now, mirrored here by the shell (same LaunchedEffect that feeds
 * [ForegroundConversation]) so the task-result notifier can suppress the
 * "task finished" alert the user is already looking at. true = on the board.
 */
object ForegroundBoard {
    private val _onBoard = MutableStateFlow(false)
    val onBoard: StateFlow<Boolean> = _onBoard.asStateFlow()

    fun set(onBoard: Boolean) {
        _onBoard.value = onBoard
    }
}

/**
 * System notifications for "an agent is blocked waiting for you" (批v160 W8).
 * Pure projection of [InteractiveRequestWatcher.requests]: one notification PER
 * CONVERSATION (stable id → tap-cancel / auto-cancel / re-post all agree), the
 * body lists that conversation's pending requests. Suppressed — cancelled, not
 * just muted — while that conversation is the foreground detail AND the app is
 * foreground ([AppForeground], 批v161.3 R1); re-posted the moment the user
 * navigates away (or leaves the app) while it still waits. Disabled in settings →
 * everything cancelled and the collector idles.
 *
 * Notification ids must be STABLE ACROSS PROCESSES (cancel-after-tap and
 * resolve-after-restart both rely on it), hence the hash of (serverId,
 * conversationId), never a counter.
 */
@Singleton
class InteractiveNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watcher: InteractiveRequestWatcher,
    private val appForeground: AppForeground,
    private val appSettingsStore: AppSettingsStore,
    @ApplicationScope appScope: CoroutineScope,
) {
    /** Conversations that currently have a notification out (group key → id). */
    private val posted = mutableMapOf<String, Int>()

    init {
        appScope.launch {
            // 批v161.3 R1: the app-foreground StateFlow joins the combine as a
            // fourth source — all four are StateFlows (never idle waiting for a
            // first emission), so a foreground/background flip re-renders the
            // whole picture instead of waiting for the next request event.
            combine(
                watcher.requests,
                appSettingsStore.settings,
                ForegroundConversation.ref,
                appForeground.fg,
            ) { requests, settings, fgConv, fgApp ->
                // One bad render (e.g. a SecurityException from a just-revoked
                // permission) must not kill the process-lifetime collector.
                try {
                    render(requests, settings.notifyInteractiveEnabled, fgConv, fgApp)
                } catch (_: Exception) {
                }
            }.collect()
        }
    }

    /** Idempotent; called from [app.codeg.android.CodegApplication.onCreate]. */
    fun ensureChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val interactive = NotificationChannel(
            CHANNEL_INTERACTIVE,
            LocaleManager.wrap(context).getString(R.string.notif_channel_interactive),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_interactive_desc)
            // 系统级通知体系: the P0 alert should break through physically too.
            // (No setSound — null keeps the channel's system default, the style
            // the two original channels shipped with.)
            enableVibration(true)
        }
        val taskResults = NotificationChannel(
            CHANNEL_TASK_RESULTS,
            LocaleManager.wrap(context).getString(R.string.notif_channel_task_results),
            // Second-tier semantics: the shade + sound, never heads-up.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_task_results_desc)
            enableVibration(true)
        }
        val deviceEvents = NotificationChannel(
            CHANNEL_DEVICE_EVENTS,
            LocaleManager.wrap(context).getString(R.string.notif_channel_device_events),
            // Silent log semantics — the alert exists in the shade for later.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_device_events_desc)
        }
        val keepAlive = NotificationChannel(
            CHANNEL_KEEPALIVE,
            LocaleManager.wrap(context).getString(R.string.notif_channel_keepalive),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_keepalive_desc)
        }
        runCatching {
            nm.createNotificationChannels(listOf(interactive, taskResults, deviceEvents, keepAlive))
        }
    }

    private fun render(
        requests: List<PendingInteraction>,
        enabled: Boolean,
        fg: SessionRef?,
        appFg: Boolean,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!enabled) {
            posted.keys.toList().forEach { key -> nm.cancel(posted.remove(key)!!) }
            return
        }
        val groups = requests.groupBy { "${it.serverId}|${it.conversationId}" }
        // Conversations whose last waiting request resolved: cancel the alert.
        posted.keys.filterNot { it in groups.keys }.toList().forEach { key ->
            nm.cancel(posted.remove(key)!!)
        }
        groups.forEach { (groupKey, items) ->
            val first = items.first()
            val id = notificationId(first.serverId, first.conversationId)
            // Viewing the conversation right now → the alert would be noise.
            // But only while the app itself is foreground (批v161.3 R1): behind
            // the shade or another app the screen mirror is meaningless — the
            // suppression must not eat the alert the user cannot see.
            if (appFg && fg == SessionRef(first.serverId, first.conversationId)) {
                if (groupKey in posted) {
                    nm.cancel(id)
                    posted.remove(groupKey)
                }
                return@forEach
            }
            runCatching { nm.notify(id, buildNotification(items)) }
            posted[groupKey] = id
        }
    }

    private fun buildNotification(items: List<PendingInteraction>): Notification {
        // The app language rides the custom LocaleManager, not the system config.
        val ctx = LocaleManager.wrap(context)
        val first = items.first()
        val lines = items.map { item ->
            val label = ctx.getString(kindLabel(item.kind))
            val title = item.title.ifBlank { label }
            "$label：$title"
        }
        val text = if (lines.size == 1) {
            "${lines[0]} · ${first.serverName}"
        } else {
            ctx.getString(R.string.notif_interactive_multi, lines.size)
        }
        // Deep link carries the owning device; the shell handoffs to it before
        // opening (conversation ids are server-local).
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("codeg://conversation/${first.conversationId}?serverId=${Uri.encode(first.serverId)}"),
            ctx,
            MainActivity::class.java,
        )
        val contentIntent = PendingIntent.getActivity(
            ctx,
            first.conversationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_INTERACTIVE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(first.serverName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(contentIntent)
            // 系统级通知体系: readable on the lock screen (it names the device,
            // never message content) and a full-screen promotion when the device
            // is locked — an agent blocked on a permission is exactly what the
            // full-screen intent use case was written for. Same deep link the
            // content tap uses; immutable is fine (no fill-in needed).
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(contentIntent, true)
            // Tap opens the conversation — the alert served its purpose.
            .setAutoCancel(true)
            // Re-posts while the same request still waits must stay silent (the
            // notification is refreshed on every relevant state change).
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    companion object {
        const val CHANNEL_INTERACTIVE = "interactive_requests"
        const val CHANNEL_TASK_RESULTS = "task_results"
        const val CHANNEL_DEVICE_EVENTS = "device_events"
        const val CHANNEL_KEEPALIVE = "service_keepalive"

        /** Stable per-conversation notification id (server-local conversation ids
         *  collide across devices — the device id joins the hash). */
        fun notificationId(serverId: String, conversationId: Int): Int =
            31 * serverId.hashCode() + conversationId

        fun kindLabel(kind: InteractionKind): Int = when (kind) {
            InteractionKind.PERMISSION -> R.string.workbench_kind_permission
            InteractionKind.QUESTION -> R.string.workbench_kind_question
            InteractionKind.PLAN -> R.string.workbench_kind_plan
        }
    }
}
