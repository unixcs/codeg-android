package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Batch v136 regression: every store in the app shares ONE Preferences DataStore
 * (`codeg_prefs`), so `dataStore.data` re-emits on any write anywhere. The profile
 * flows must only emit on real profile/selection changes — before the equality
 * gate, the "recent searches" write that follows each successful query looked like
 * a device change and wiped the search screen's results (the "second search finds
 * nothing" report).
 *
 * 批v146 rewrite: the original version raced fixed 500ms "nothing must arrive"
 * windows against real DataStore file I/O — vacuous on a loaded runner and the
 * single recurring CI false-red across v142/v144/v146 (three batches ate reruns,
 * one even at a 20s budget). Now every wait is "await a future state with a
 * generous budget" and the negative assertion replays the RECORDED emission
 * history: DataStore serializes writes through one actor, so once the post-`b`
 * emission is observed, any emission the gated `recents` write would ever
 * produce is already in the log — duplicates there mean a broken gate, without
 * any wall-clock race.
 */
class ServerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val executors = mutableListOf<ExecutorService>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        executors.forEach { it.shutdownNow() }
    }

    /**
     * 批v165 rc.3: the shared Dispatchers.IO pool arrives poisoned by leaked
     * coroutines from the VM tests immediately preceding this class in
     * execution order (SearchViewModelTest's real-dispatcher worlds — 批v163
     * documented the same flake class as "rotating victims", and no forkEvery
     * segment can separate alphabetically-adjacent classes). In the rc.3
     * Verify red the symptom was precisely one of the two `data` collectors
     * starving for the whole 90s budget while its sibling kept receiving from
     * the same DataStore. Run this test's DataStore actor AND collectors on a
     * dedicated 2-thread executor instead of the shared pool — isolation, not
     * a bigger budget, is what stops the rotation.
     */
    private fun testDispatcher(): ExecutorCoroutineDispatcher {
        val executor = Executors.newFixedThreadPool(2)
        executors += executor
        return executor.asCoroutineDispatcher()
    }

    private fun newDataStore(): DataStore<Preferences> {
        val dir = tmp.newFolder()
        val scope = CoroutineScope(testDispatcher() + SupervisorJob() + testScopeHandler()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "codeg_prefs.preferences_pb")
        }
    }

    private fun collectorScope(): CoroutineScope =
        CoroutineScope(testDispatcher() + SupervisorJob() + testScopeHandler()).also { scopes += it }

    /** Teardown noise silencer: after `tearDown` cancels a scope, its coroutines
     *  can still race the TemporaryFolder deletion and throw (e.g. writing to the
     *  removed file). Unhandled, that lands on whichever test happens to be
     *  running (seen as UncaughtExceptionsBeforeTest) — a false red that rotates.
     *  It is scaffolding noise, not a regression signal: real failures assert in
     *  the test body via suspend calls, which propagate normally. */
    private fun testScopeHandler() = CoroutineExceptionHandler { _, throwable ->
        println("ServerStoreTest: swallowed scaffolding exception: $throwable")
    }

    private fun profile(id: String) =
        ServerProfile(id = id, name = id.uppercase(), baseUrl = "https://$id.test")

    /** Skips emissions the predicate rejects. 90s, not 5s: this spans REAL
     *  DataStore file I/O on Dispatchers.IO under a loaded CI runner (the v146
     *  20s budget was still hit twice in one Release run; rc.6 Verify ate a
     *  5-red streak with reruns no longer rescuing, so 45s → 90s — a genuine
     *  regression still fails, just later). */
    private suspend fun <T> Channel<T>.awaitMatching(predicate: (T) -> Boolean): T =
        withTimeout(90_000) {
            while (true) {
                val value = receive()
                if (predicate(value)) return@withTimeout value
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    @Test
    fun `unrelated preference writes do not look like profile changes`() = runBlocking {
        val dataStore = newDataStore()
        val store = ServerStore(dataStore)
        val recents = RecentSearchesStore(dataStore)

        val profilesSeen = Channel<List<ServerProfile>>(Channel.UNLIMITED)
        val selectionSeen = Channel<String?>(Channel.UNLIMITED)
        // Full emission history (every collector callback appends here); the
        // gate assertion at the end replays it. Lock-guarded: two collectors.
        val historyLock = Any()
        val profilesHistory = mutableListOf<List<ServerProfile>>()
        val selectionsHistory = mutableListOf<String?>()
        val collector = collectorScope()
        collector.launch {
            store.profiles.collect { p ->
                synchronized(historyLock) { profilesHistory += p }
                profilesSeen.send(p)
            }
        }
        collector.launch {
            store.selectedId.collect { s ->
                synchronized(historyLock) { selectionsHistory += s }
                selectionSeen.send(s)
            }
        }

        // 批v165 rc.3 root cause — androidx datastore 1.1.1 TOCTOU: a collector's
        // initial dirty read stamps its startState with the version read AFTER
        // the file read; if a write lands between the two, the write's cache entry
        // (same version) is dropped forever by `dropWhile { version <= startState }`
        // and that collector never sees the write (the rc.3 Verify/Release reds:
        // profiles emitted [a] while selectedId sat silent for the whole 90s).
        // Draining both initial emissions closes the window deterministically:
        // every write below happens while both collectors are already parked on
        // their update wait, so versions strictly increase past their startState.
        withTimeout(90_000) { assertEquals(emptyList<ServerProfile>(), profilesSeen.receive()) }
        withTimeout(90_000) { assertEquals(null, selectionSeen.receive()) }

        store.upsert(profile("a"))
        assertEquals(listOf("a"), profilesSeen.awaitMatching { it.isNotEmpty() }.map { it.id })
        assertEquals("a", selectionSeen.awaitMatching { it != null })

        // A sibling store's write goes through the SAME preferences file...
        recents.add("ai chat")

        // ...and must not surface as a profile/selection emission — asserted
        // deterministically on the recorded history below, not by racing a
        // fixed window.
        store.upsert(profile("b"))
        assertEquals(
            listOf("a", "b"),
            profilesSeen.awaitMatching { it.size == 2 }.map { it.id },
        )

        // The `recents` write was ordered before upsert(b) in DataStore's
        // single write actor, so its (never-should-happen) emissions are
        // already recorded once the [a,b] emission is observed: snapshot and
        // assert the gate. History may lead with the flows' initial values
        // (empty list / null) depending on collector start timing — filter
        // those, then require EXACTLY the two genuine changes, no duplicates.
        synchronized(historyLock) {
            assertEquals(
                "a recent-searches write must not look like a profile change",
                listOf(listOf("a"), listOf("a", "b")),
                profilesHistory.filter { it.isNotEmpty() }.map { row -> row.map { it.id } },
            )
            assertEquals(
                "a recent-searches write must not look like a selection change",
                listOf("a"),
                selectionsHistory.filterNotNull(),
            )
        }

        // Genuine changes keep flowing after the unrelated write.
        store.select("b")
        assertEquals("b", selectionSeen.awaitMatching { it == "b" })
    }
}
