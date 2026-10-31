package app.codeg.android.feature.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.common.DefaultDispatcherProvider
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.RecentSearchesStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.ServerStore
import app.codeg.android.core.network.CodegClientFactory
import app.codeg.android.core.security.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Batch v136 regression for the "second search finds nothing" report.
 *
 * Root cause: every store shares one Preferences DataStore, so the recent-searches
 * write that follows each successful query re-emitted `selectedProfile`; the search
 * VM treated that as a device change and cleared `results` without re-running the
 * query, leaving the "nothing matched <query>" page on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    /** Single-threaded Main: viewModelScope state updates are serialized like on device. */
    private lateinit var mainExecutor: ExecutorService

    @Before
    fun setUp() {
        // viewModelScope needs a Main dispatcher; the search pipeline uses real
        // debounce time, so a real dispatcher keeps the timing observable. One thread,
        // because two coroutines racing on a pool makes ordering assertions flaky in
        // ways the app itself cannot reproduce.
        mainExecutor = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
    }

    @After
    fun tearDown() {
        // Cancel leftover work BEFORE taking Main away, then tear down the executor:
        // a resume landing on a closed executor would fail an unrelated test.
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    // region Fixture

    private class FakeSecretStore : SecretStore {
        private val values = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { values[key] = value }
        override fun get(key: String): String? = values[key]
        override fun remove(key: String) { values.remove(key) }
    }

    private class World(
        val store: ServerStore,
        val recents: RecentSearchesStore,
        val secrets: FakeSecretStore,
        val vm: SearchViewModel,
        private val http: HttpClient,
        private val scope: CoroutineScope,
    ) {
        fun close() {
            // The VM's own scope outlives the store scope; cancel it first so no
            // collector survives into the next test's dispatcher swap.
            vm.viewModelScope.cancel()
            http.close()
            scope.cancel()
        }
    }

    private fun summary(id: Int, title: String): String =
        """{"id":$id,"folder_id":1,"title":"$title","agent_type":"claude_code",""" +
            """"status":"completed","created_at":"2026-10-01T00:00:00Z",""" +
            """"updated_at":"2026-10-01T00:00:00Z"}"""

    private fun newWorld(withTokenA: Boolean = true): World {
        val dir = tmp.newFolder()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "codeg_prefs.preferences_pb")
        }
        val store = ServerStore(dataStore)
        val recents = RecentSearchesStore(dataStore)
        val secrets = FakeSecretStore()

        val engine = MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (request.url.encodedPath) {
                "/api/list_all_conversations" -> {
                    val body = (request.body as? TextContent)?.text.orEmpty()
                    val search = runCatching {
                        Json.parseToJsonElement(body).jsonObject["search"]?.jsonPrimitive?.content
                    }.getOrNull()
                    // A answers slowly, so a device switch can race a live request.
                    if (request.url.host == "a.test") delay(400)
                    val rows = when {
                        search == null -> emptyList()
                        request.url.host == "a.test" && search == "ai chat" ->
                            listOf(summary(1, "ai chat one"), summary(2, "ai chat two"))
                        request.url.host == "b.test" && search == "ai chat" ->
                            listOf(summary(7, "ai chat on b"))
                        request.url.host == "c.test" && search == "ai chat" ->
                            listOf(summary(9, "ai chat on c"))
                        else -> emptyList()
                    }
                    respond("[${rows.joinToString(",")}]", HttpStatusCode.OK, json)
                }
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val http = HttpClient(engine)
        val repository = ServerRepository(
            serverStore = store,
            secretStore = secrets,
            factory = CodegClientFactory(object : dagger.Lazy<HttpClient> {
                override fun get(): HttpClient = http
            }),
            dispatchers = DefaultDispatcherProvider,
        )
        runBlocking {
            store.upsert(ServerProfile(id = "a", name = "A", baseUrl = "https://a.test"))
            if (withTokenA) secrets.put("a", "token-a")
            store.upsert(ServerProfile(id = "b", name = "B", baseUrl = "https://b.test"))
            secrets.put("b", "token-b")
            store.select("a")
        }
        return World(store, recents, secrets, SearchViewModel(repository, recents), http, scope)
    }

    // endregion

    @Test
    fun `a search survives the recent-searches write that follows it`() = runBlocking {
        val world = newWorld()
        try {
            withTimeout(5_000) { world.vm.ui.first { it.serverId == "a" } }

            world.vm.onQueryChange("ai chat")
            withTimeout(5_000) {
                world.vm.ui.first { it.query == "ai chat" && !it.searching && it.results.size == 2 }
            }

            // The successful search persists the term into the SAME preferences file.
            // Before the v136 fix that write re-emitted the profile flow and cleared
            // `results`, leaving the "nothing matched" page with the query still shown.
            withTimeout(5_000) { world.recents.searches.first { "ai chat" in it } }
            delay(300)

            val state = world.vm.ui.value
            assertEquals("ai chat", state.query)
            assertEquals(2, state.results.size)
        } finally {
            world.close()
        }
    }

    @Test
    fun `switching devices re-runs the visible query on the new device`() = runBlocking {
        val world = newWorld()
        try {
            withTimeout(5_000) { world.vm.ui.first { it.serverId == "a" } }
            world.vm.onQueryChange("ai chat")
            withTimeout(5_000) {
                world.vm.ui.first { it.query == "ai chat" && !it.searching && it.results.size == 2 }
            }

            world.store.select("b")

            // Results are device-scoped: they must be refetched from B for the query
            // still visible in the field instead of being dropped on the floor.
            val switched = withTimeout(5_000) {
                world.vm.ui.first { it.serverId == "b" && !it.searching && it.results.isNotEmpty() }
            }
            assertEquals(listOf(7), switched.results.map { it.id })
            assertEquals("ai chat", switched.query)
        } finally {
            world.close()
        }
    }

    @Test
    fun `a slow response from the old device cannot overwrite the new device's results`() = runBlocking {
        val world = newWorld()
        try {
            withTimeout(5_000) { world.vm.ui.first { it.serverId == "a" } }
            world.vm.onQueryChange("ai chat")
            // A answers in ~400ms; switch while that request is in flight.
            withTimeout(5_000) { world.vm.ui.first { it.searching } }
            world.store.select("b")

            val onB = withTimeout(5_000) {
                world.vm.ui.first { it.serverId == "b" && !it.searching && it.results.isNotEmpty() }
            }
            assertEquals(listOf(7), onB.results.map { it.id })

            // Give A's late response time to arrive; the epoch guard must drop it.
            delay(700)
            val settled = world.vm.ui.value
            assertEquals("b", settled.serverId)
            assertEquals(listOf(7), settled.results.map { it.id })
            assertEquals(false, settled.searching)
        } finally {
            world.close()
        }
    }

    @Test
    fun `editing the selected device's URL refetches from the new endpoint`() = runBlocking {
        val world = newWorld()
        try {
            withTimeout(5_000) { world.vm.ui.first { it.serverId == "a" } }
            world.vm.onQueryChange("ai chat")
            withTimeout(5_000) {
                world.vm.ui.first { it.query == "ai chat" && !it.searching && it.results.size == 2 }
            }

            // Same id, different base URL: the rows belong to the OLD endpoint and must
            // not survive the move (their ids would be sent to the new server).
            world.store.upsert(ServerProfile(id = "a", name = "A", baseUrl = "https://c.test"))

            val moved = withTimeout(5_000) {
                world.vm.ui.first { !it.searching && it.results.map { r -> r.id } == listOf(9) }
            }
            assertEquals("a", moved.serverId)
            assertEquals("ai chat", moved.query)
        } finally {
            world.close()
        }
    }

    @Test
    fun `retry recovers once a token is added for the selected device`() = runBlocking {
        val world = newWorld(withTokenA = false)
        try {
            // No token: the VM still resolves the selected device (serverId), and the
            // first non-empty query must say "no device", not "nothing matched".
            withTimeout(5_000) { world.vm.ui.first { it.serverId == "a" } }
            world.vm.onQueryChange("ai chat")
            withTimeout(5_000) { world.vm.ui.first { it.query == "ai chat" && it.noDevice } }
            assertEquals(0, world.vm.ui.value.results.size)

            // Token edits never emit on the profile flow; Retry must re-resolve.
            world.secrets.put("a", "token-a")
            world.vm.retry()

            val healed = withTimeout(5_000) {
                world.vm.ui.first { !it.searching && !it.noDevice && it.results.size == 2 }
            }
            assertEquals("a", healed.serverId)
            assertEquals(listOf(1, 2), healed.results.map { it.id })
        } finally {
            world.close()
        }
    }
}
