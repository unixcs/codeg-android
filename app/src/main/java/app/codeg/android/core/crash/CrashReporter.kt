package app.codeg.android.core.crash

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device-local crash capture: the last uncaught exception is persisted to a
 * small ring of files under `cacheDir/crash/`, and the next launch promotes the
 * newest one into the settings DataStore so the About page can show it. This is
 * the only crash telemetry in the app — nothing leaves the device.
 *
 * The handler core ([handleUncaught]) is a pure JVM function over a [File]
 * directory so it stays unit-testable; the Application just wires a real
 * directory and the previous default handler. Everything inside runs on a
 * thread that is already dying: no coroutines, no DataStore, no logging
 * framework — plain file IO under a total try/catch, with a reentrancy gate so
 * a crash inside the handler itself cannot recurse.
 */
object CrashReporter {

    /** Reentrancy gate: once a handler invocation is in flight, any nested
     *  crash goes straight to the previous handler. */
    @Volatile
    internal var handling = AtomicBoolean(false)

    const val DIR_NAME = "crash"
    const val FILE_PREFIX = "crash-"
    const val KEEP = 3

    /** Max persisted stack size (chars). A stack beyond this is truncated with a marker. */
    const val MAX_STACK_CHARS = 16_000

    /**
     * Write a crash file for [throwable] into [dir], prune older ones beyond
     * [KEEP], then hand off to [previous]. Never throws; failures inside are
     * swallowed — the process is dying anyway, and [previous] must still run.
     */
    fun handleUncaught(
        thread: Thread,
        throwable: Throwable,
        dir: File,
        versionName: String,
        previous: Thread.UncaughtExceptionHandler?,
    ) {
        if (!handling.compareAndSet(false, true)) {
            previous?.uncaughtException(thread, throwable)
            return
        }
        try {
            try {
                writeCrashFile(thread, throwable, dir, versionName)
            } catch (_: Throwable) {
                // Best-effort only — never let persistence failures mask the original crash.
            }
        } finally {
            // The gate stays closed while the previous handler runs — a nested
            // crash in that window must not re-enter the write path. Reset only
            // after it returns (if previous itself throws, the gate stays shut:
            // the process is dying anyway, which is the standard ACRA semantic).
            previous?.uncaughtException(thread, throwable)
            handling.set(false)
        }
    }

    /** Persist one crash record. Internal so tests can drive it directly. */
    internal fun writeCrashFile(thread: Thread, throwable: Throwable, dir: File, versionName: String) {
        if (!dir.isDirectory && !dir.mkdirs()) return
        val stack = stackTraceOf(throwable)
        val body = buildString {
            append("time: ").append(System.currentTimeMillis()).append('\n')
            append("version: ").append(versionName).append('\n')
            append("thread: ").append(thread.name).append('\n')
            append("stack:\n").append(stack)
        }
        val out = File(dir, "$FILE_PREFIX${System.currentTimeMillis()}.txt")
        try {
            out.writeText(body)
        } catch (_: Throwable) {
            return
        }
        prune(dir, keep = KEEP)
    }

    /** Newest crash file in [dir], or null. */
    fun newestCrashFile(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.maxByOrNull { it.name }

    /** Read and delete the newest crash file, returning its content or null. */
    fun drainNewest(dir: File): String? {
        val file = newestCrashFile(dir) ?: return null
        val content = try {
            file.readText()
        } catch (_: Throwable) {
            return null
        }
        // Delete only after a successful read; a failed delete just means the
        // same crash is drained again next launch (harmless, overwritten later).
        try {
            file.delete()
        } catch (_: Throwable) {
            // ignore
        }
        return content
    }

    private fun prune(dir: File, keep: Int) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return
        if (files.size <= keep) return
        files.sortedByDescending { it.name }
            .drop(keep)
            .forEach { f -> runCatching { f.delete() } }
    }

    /** Stack trace text, capped at [MAX_STACK_CHARS] with an explicit truncation marker. */
    internal fun stackTraceOf(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val full = sw.toString()
        return if (full.length <= MAX_STACK_CHARS) {
            full
        } else {
            full.take(MAX_STACK_CHARS) + "\n… (truncated)"
        }
    }
}
