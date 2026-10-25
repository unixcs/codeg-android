package app.codeg.android.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM tests for the crash capture core (batch v123): file writing, the
 * 3-file ring, the reentrancy gate, and the crash-file round-trip. No Android
 * types involved — the handler signature takes a [File], not a Context.
 */
class CrashReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun handlerState(dir: File) = object {
        var previousCalled = 0
        var previousThread: Thread? = null
        var previousThrowable: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { thread, throwable ->
            previousCalled += 1
            previousThread = thread
            previousThrowable = throwable
        }
    }

    @Test
    fun `handleUncaught writes a crash file`() {
        val dir = tmp.newFolder()
        val state = handlerState(dir)
        val boom = IllegalStateException("boom")
        CrashReporter.handleUncaught(Thread.currentThread(), boom, dir, "1.2.3", state.previous)
        val file = CrashReporter.newestCrashFile(dir)
        assertNotNull(file)
        val content = file!!.readText()
        assertTrue(content.contains("version: 1.2.3"))
        assertTrue(content.contains("thread: "))
        assertTrue(content.contains("IllegalStateException: boom"))
    }

    @Test
    fun `ring keeps only the newest KEEP files`() {
        val dir = tmp.newFolder()
        repeat(CrashReporter.KEEP + 2) { idx ->
            CrashReporter.handleUncaught(
                Thread.currentThread(),
                RuntimeException("crash-$idx"),
                dir,
                "1.0",
                null,
            )
            // File names are timestamp-based; force distinct names even within one millisecond.
            Thread.sleep(2)
        }
        val files = dir.listFiles { f -> f.name.startsWith(CrashReporter.FILE_PREFIX) }!!.toList()
        assertEquals(CrashReporter.KEEP, files.size)
        assertTrue(files.none { it.readText().contains("crash-0") })
        assertTrue(files.any { it.readText().contains("crash-${CrashReporter.KEEP + 1}") })
    }

    @Test
    fun `handler still calls the previous handler exactly once`() {
        val dir = tmp.newFolder()
        val state = handlerState(dir)
        val boom = RuntimeException("x")
        CrashReporter.handleUncaught(Thread.currentThread(), boom, dir, "1.0", state.previous)
        assertEquals(1, state.previousCalled)
        assertEquals(boom, state.previousThrowable)
    }

    @Test
    fun `previous handler is called even when writing fails`() {
        val dir = tmp.newFile("not-a-dir") // a regular file where the dir should be → mkdirs fails
        val state = handlerState(dir)
        CrashReporter.handleUncaught(Thread.currentThread(), RuntimeException("x"), dir, "1.0", state.previous)
        assertEquals(1, state.previousCalled)
        assertNull(CrashReporter.newestCrashFile(dir))
    }

    @Test
    fun `reentrant crash goes straight to the previous handler`() {
        val dir = tmp.newFolder()
        val state = handlerState(dir)
        CrashReporter.handleUncaught(Thread.currentThread(), RuntimeException("outer"), dir, "1.0") { t, e ->
            // Simulate a crash INSIDE the handler: the gate must route this to
            // the previous handler without recursing through writeCrashFile again.
            CrashReporter.handleUncaught(t, e, dir, "1.0", state.previous)
        }
        assertEquals(1, state.previousCalled)
        assertEquals(1, dir.listFiles { f -> f.name.startsWith(CrashReporter.FILE_PREFIX) }!!.size)
    }

    @Test
    fun `stack beyond the cap is truncated with a marker`() {
        val deep = deepThrowable(depth = 400)
        val trace = CrashReporter.stackTraceOf(deep)
        assertTrue(trace.length <= CrashReporter.MAX_STACK_CHARS + 20)
        assertTrue(trace.endsWith("… (truncated)"))
    }

    @Test
    fun `drainNewest reads and deletes the newest file`() {
        val dir = tmp.newFolder()
        CrashReporter.handleUncaught(Thread.currentThread(), RuntimeException("first"), dir, "1.0", null)
        Thread.sleep(2)
        CrashReporter.handleUncaught(Thread.currentThread(), RuntimeException("second"), dir, "1.0", null)
        val drained = CrashReporter.drainNewest(dir)
        assertNotNull(drained)
        assertTrue(drained!!.contains("second"))
        // Only the newest was drained; the older file of the ring remains.
        val remaining = CrashReporter.newestCrashFile(dir)
        assertNotNull(remaining)
        assertTrue(remaining!!.readText().contains("first"))
    }

    @Test
    fun `crash file round-trips through parseCrashFile`() {
        val dir = tmp.newFolder()
        CrashReporter.handleUncaught(
            Thread.currentThread(),
            IllegalStateException("round: trip\nwith newlines"),
            dir,
            "9.9.9",
            null,
        )
        val content = CrashReporter.newestCrashFile(dir)!!.readText()
        val record = CrashStore.parseCrashFile(content)
        assertNotNull(record)
        assertEquals("9.9.9", record!!.version)
        assertEquals(Thread.currentThread().name, record.thread)
        assertTrue(record.stack.contains("IllegalStateException: round: trip"))
        assertTrue(record.time > 0)
    }

    @Test
    fun `parseCrashFile tolerates garbage and header-like stack lines`() {
        assertNull(CrashStore.parseCrashFile("not a crash file"))
        val record = CrashStore.parseCrashFile(
            "time: 123\nversion: 1.0\nthread: main\nstack:\n" +
                "java.lang.Exception: x\n\tat version: not-a-header\n",
        )
        assertNotNull(record)
        assertEquals(123L, record!!.time)
        // A stack line that starts with "version: " must stay part of the stack.
        assertTrue(record.stack.contains("at version: not-a-header"))
    }

    private fun deepThrowable(depth: Int): Throwable {
        val cause = RuntimeException("root")
        var t: Throwable = cause
        repeat(depth) {
            t = RuntimeException("layer", t)
        }
        return t
    }
}
