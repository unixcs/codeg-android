package app.codeg.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.codeg.android.core.crash.CrashReporter
import app.codeg.android.core.crash.CrashStore
import app.codeg.android.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * v130) so polling loops (Activity feed) can pause while backgrounded
 * instead of waking the radio against every device for the app's whole
 * background life.
 */
@HiltAndroidApp
class CodegApplication : Application() {

    @Inject
    lateinit var crashStore: CrashStore

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    private val _appForeground = MutableStateFlow(true)
    val appForeground: StateFlow<Boolean> = _appForeground

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { _appForeground.value = true }
            override fun onStop(owner: LifecycleOwner) { _appForeground.value = false }
        })
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
    }
}
