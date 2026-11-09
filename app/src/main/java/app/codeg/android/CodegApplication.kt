package app.codeg.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.codeg.android.core.crash.CrashReporter
import app.codeg.android.core.crash.CrashStore
import app.codeg.android.core.data.GlobalEventWatcher
import app.codeg.android.core.data.InteractiveRequestWatcher
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.notify.AppForeground
import app.codeg.android.core.notify.KeepAliveService
import app.codeg.android.core.notify.InteractiveNotifier
import app.codeg.android.core.notify.TaskResultNotifier
import app.codeg.android.core.notify.TaskResultWatcher
import app.codeg.android.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Application entry point. [@HiltAndroidApp][HiltAndroidApp] triggers Hilt's
 * code generation and creates the application-level dependency container.
 *
 * Also installs the device-local crash capture (batch v123): uncaught
 * exceptions are persisted to `cacheDir/crash/` and promoted into the
 * [CrashStore] on next launch so the About page can show the last crash.
 * Nothing here can throw ahead of the default handler —
 * [CrashReporter.handleUncaught] is fully guarded.
 *
 * Publishes app foreground/background as a process-wide [StateFlow] (batch
 * v130; owned by [AppForeground] since 批v161.3 R1 so the notifiers share it)
 * so polling loops (Activity feed) can pause while backgrounded instead of
 * waking the radio against every device for the app's whole background life.
 */
@HiltAndroidApp
class CodegApplication : Application() {

    @Inject
    lateinit var crashStore: CrashStore

    @Inject
    lateinit var interactiveNotifier: InteractiveNotifier

    // 批v161.3 R1: Hilt builds @Singleton classes lazily — the notifier is only
    // reachable through its three collectors, and nothing else held a
    // process-lifetime reference, so WITHOUT this injection the collectors
    // never ran and all three notification classes (task results / turn
    // completions / device outages) were silently dead.
    @Inject
    lateinit var taskResultNotifier: TaskResultNotifier

    // 系统级通知体系: both watchers run for the whole process now — the shell's
    // old foreground-only refcount meant the app went deaf the moment it was
    // backgrounded, but the notifications exist precisely for that state (the
    // keep-alive service keeps its own refcount reference; harmless overlap).
    @Inject
    lateinit var interactionWatcher: InteractiveRequestWatcher

    @Inject
    lateinit var taskResultWatcher: TaskResultWatcher

    // 批v173 C1: the push layer must outlive the shell AND the keep-alive
    // service — it feeds both in-app refreshes and (via the notifier) the
    // run-settled notifications that only exist while backgrounded. Same
    // laziness trap as the notifier above: nothing else holds a process-
    // lifetime reference, so the injection IS the activation.
    @Inject
    lateinit var globalEventWatcher: GlobalEventWatcher

    // 批v161.3 R1: the foreground mirror moved into [AppForeground] so the
    // notifiers can read it too; this alias keeps the polling ViewModels'
    // `(appContext as CodegApplication).appForeground` reads working.
    @Inject
    lateinit var appForegroundRef: AppForeground

    @Inject
    lateinit var appSettingsStore: AppSettingsStore

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    /** Process-wide foreground [StateFlow] — now owned by [AppForeground]. */
    val appForeground: StateFlow<Boolean> get() = appForegroundRef.fg

    override fun onCreate() {
        super.onCreate()
        val crashDir = File(cacheDir, CrashReporter.DIR_NAME)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }.getOrDefault("")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashReporter.handleUncaught(thread, throwable, crashDir, versionName, previous)
        }
        // Promote a crash persisted by the previous process into the store
        // (About page). Process-lifetime scope: must survive any screen.
        appScope.launch { runCatching { crashStore.promoteFromFile(crashDir) } }
        // 批v160 W8: notification channels up front; the keep-alive foreground
        // service simply FOLLOWS its setting — the settings toggle only writes
        // the pref, this collector owns the Android side effect (start/stop),
        // so cold start and in-UI toggles take the same path.
        interactiveNotifier.ensureChannels()
        // Reference-counted start: the shell no longer holds a reference (the
        // MainShell DisposableEffect is gone) — this is THE process-lifetime one.
        interactionWatcher.start()
        taskResultWatcher.start()
        globalEventWatcher.start()
        appScope.launch {
            appSettingsStore.settings
                .map { it.keepAliveEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    try {
                        if (enabled) KeepAliveService.start(this@CodegApplication)
                        else KeepAliveService.stop(this@CodegApplication)
                    } catch (_: Exception) {
                        // Android 12+ forbids FGS starts from the background; a
                        // cold start in that state just stays without the service
                        // until the next foreground toggle.
                    }
                }
        }
    }
}
