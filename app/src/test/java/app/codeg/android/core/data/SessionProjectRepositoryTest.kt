package app.codeg.android.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.codeg.android.core.datastore.SessionProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The R3-1 regression: [SessionProjectRepository.setActive] validates against the
 * `projects` StateFlow, which is fed on the ApplicationScope and LAGS the
 * DataStore write by ≥1 dispatcher hop — activating a just-created id through it
 * deterministically drops the filter (8/8 failing scratch runs). The created-id
 * path MUST go through [SessionProjectRepository.activateCreated].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionProjectRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRepo(): Pair<SessionProjectRepository, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dir = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "repo.preferences_pb")
        }
        // The StateFlows are fed on `scope` (Dispatchers.Default) — the SAME
        // cross-dispatch lag as production, which is the whole point here.
        return SessionProjectRepository(SessionProjectStore(dataStore), scope) to scope
    }

    @Test
    fun `activateCreated applies the fresh id immediately, setActive on it would drop it`() = runBlocking {
        val (repo, scope) = newRepo()
        try {
            assertNull(repo.activeProjectId.value)

            val created = repo.create("P1")
            // activateCreated is the ONLY reliable path for a brand-new id.
            repo.activateCreated(created.id)
            assertEquals(created.id, repo.activeProjectId.value)

            // Documented contract: setActive on the id AFTER the flow caught up
            // keeps it (validated normally).
            org.junit.Assert.assertTrue(
                "projects flow must catch up eventually",
                runBlocking {
                    var ok = false
                    repeat(100) {
                        if (repo.projects.value.any { it.id == created.id }) {
                            ok = true; return@repeat
                        }
                        Thread.sleep(20)
                    }
                    ok
                },
            )
            repo.setActive(created.id)
            assertEquals(created.id, repo.activeProjectId.value)

            // setActive on a genuinely unknown id still clears (the guard's purpose).
            repo.setActive("nope")
            assertNull(repo.activeProjectId.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `activeMemberKeys yields member keys of the active project only`() = runBlocking {
        val (repo, scope) = newRepo()
        try {
            val p = repo.create("P1")
            val p2 = repo.create("P2")
            repo.activateCreated(p.id)
            repo.setMembership(p.id, app.codeg.android.core.datastore.SessionRef("a", 1), true)
            repo.setMembership(p2.id, app.codeg.android.core.datastore.SessionRef("b", 2), true)

            val keys = repo.activeMemberKeys.first()
            assertEquals(setOf("a:1"), keys)
        } finally {
            scope.cancel()
        }
    }
}
