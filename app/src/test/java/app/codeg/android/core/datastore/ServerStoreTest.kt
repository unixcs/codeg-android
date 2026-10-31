package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Batch v136 regression: every store in the app shares ONE Preferences DataStore
 * (`codeg_prefs`), so `dataStore.data` re-emits on any write anywhere. The profile
 * flows must only emit on real profile/selection changes — before the equality
 * gate, the "recent searches" write that follows each successful query looked like
 * a device change and wiped the search screen's results (the "second search finds
 * nothing" report).
 */
class ServerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun newDataStore(): DataStore<Preferences> {
        val dir = tmp.newFolder()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "codeg_prefs.preferences_pb")
        }
    }

    private fun collectorScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }

    private fun profile(id: String) =
        ServerProfile(id = id, name = id.uppercase(), baseUrl = "https://$id.test")

    /** Skips the flow's initial (pre-write) emission. */
    private suspend fun <T> Channel<T>.awaitMatching(predicate: (T) -> Boolean): T =
        withTimeout(5_000) {
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
        val collector = collectorScope()
        collector.launch { store.profiles.collect { profilesSeen.send(it) } }
        collector.launch { store.selectedId.collect { selectionSeen.send(it) } }

        store.upsert(profile("a"))
        assertEquals(listOf("a"), profilesSeen.awaitMatching { it.isNotEmpty() }.map { it.id })
        assertEquals("a", selectionSeen.awaitMatching { it != null })

        // A sibling store's write goes through the SAME preferences file...
        recents.add("ai chat")

        // ...and must not surface as a profile/selection emission.
        assertNull(
            "a recent-searches write must not look like a profile change",
            withTimeoutOrNull(500) { profilesSeen.receive() },
        )
        assertNull(
            "a recent-searches write must not look like a selection change",
            withTimeoutOrNull(500) { selectionSeen.receive() },
        )

        // Genuine changes still flow.
        store.upsert(profile("b"))
        store.select("b")
        assertEquals(listOf("a", "b"), profilesSeen.awaitMatching { it.size == 2 }.map { it.id })
        assertEquals("b", selectionSeen.awaitMatching { it == "b" })
    }
}
