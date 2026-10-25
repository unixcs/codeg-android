package app.codeg.android

import android.app.Application
import app.codeg.android.core.crash.CrashReporter
import app.codeg.android.core.crash.CrashStore
import app.codeg.android.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
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
 */
@HiltAndroidApp
class CodegApplication : Application() {

    @Inject
    lateinit var crashStore: CrashStore

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

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
    }
}
