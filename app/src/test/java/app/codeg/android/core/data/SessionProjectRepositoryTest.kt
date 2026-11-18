package app.codeg.android.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.codeg.android.core.datastore.SessionProjectStore
import kotlinx.coroutines.CoroutineExceptionHandler
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
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            println("SessionProjectRepositoryTest: swallowed scaffolding exception: $throwable")
        })
        val dir = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "repo.preferences_pb")
        }
        // The StateFlows are fed on `scope` (Dispatchers.Default) — the SAME
        // cross-dispatch lag as production, which is the whole point here.
        return SessionProjectRepository(SessionProjectStore(dataStore), scope) to scope
    }

    /** R2-A P3: the `members` StateFlow is fed Eagerly on Dispatchers.Default — a
     *  write's echo lags the suspend call by 1-2 hops with no happens-before edge.
     *  The member-derived assertions poll instead of racing (the activateCreated
     *  test below already does this for `projects`). Budget 20s, not 2s: a loaded
     *  CI runner stalled the echo past 2s once (Release gate, rc.1); a genuine
     *  regression still fails, just later. */
    private fun awaitMemberKey(repo: SessionProjectRepository, key: String) {
        repeat(200) {
            if (repo.members.value.values.any { refs -> refs.any { it.key == key } }) return
            Thread.sleep(100)
        }
        error("members never echoed $key: ${repo.members.value}")
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
                    // 批v183: 20s budget like awaitMemberKey — the 2s one flaked on
                    // loaded CI runners (rc.1, rc.18 Release gates).
                    var ok = false
                    repeat(200) {
                        if (repo.projects.value.any { it.id == created.id }) {
                            ok = true; return@repeat
                        }
                        Thread.sleep(100)
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
            awaitMemberKey(repo, "a:1")

            val keys = repo.activeMemberKeys.first()
            assertEquals(setOf("a:1"), keys)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `activeMemberKeys of an empty project is an empty set, never null`() = runBlocking {
        // R1-B P2-3: an id with zero members is a filter that matches NOTHING.
        // Emitting null here would mean "no filter" and show every session.
        val (repo, scope) = newRepo()
        try {
            val p = repo.create("Empty")
            repo.activateCreated(p.id)
            assertEquals(emptySet<String>(), repo.activeMemberKeys.first())
        } finally {
            scope.cancel()
        }
    }

    // ---- 批v160 W9: the 未添加 sentinel scope ----

    @Test
    fun `setActive accepts the unassigned sentinel and isUnassignedActive reports it`() = runBlocking {
        val (repo, scope) = newRepo()
        try {
            repo.setActive(UNASSIGNED_PROJECT_ID)
            assertEquals(UNASSIGNED_PROJECT_ID, repo.activeProjectId.value)
            org.junit.Assert.assertTrue(repo.isUnassignedActive())

            repo.setActive(null)
            assertNull(repo.activeProjectId.value)
            org.junit.Assert.assertFalse(repo.isUnassignedActive())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `activeExcludeKeys yields every assigned key only for the sentinel`() = runBlocking {
        val (repo, scope) = newRepo()
        try {
            val p = repo.create("P1")
            repo.setMembership(p.id, app.codeg.android.core.datastore.SessionRef("a", 1), true)
            repo.setMembership(p.id, app.codeg.android.core.datastore.SessionRef("a", 2), true)
            awaitMemberKey(repo, "a:1")

            // No scope: null (the caller keeps every row).
            assertNull(repo.activeExcludeKeys.first())
            // A real project active: null too (memberKeys owns the narrowing).
            repo.setActive(p.id)
            assertNull(repo.activeExcludeKeys.first())
            // Sentinel active: the complement — every ASSIGNED key.
            repo.setActive(UNASSIGNED_PROJECT_ID)
            assertEquals(setOf("a:1", "a:2"), repo.activeExcludeKeys.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `memberProjects inverts members into sessions-per-project`() = runBlocking {
        // R2-B P2-1: the aggregate's hidden-project filter needs session →
        // project ids without scanning every project's member list per row.
        val (repo, scope) = newRepo()
        try {
            val p1 = repo.create("P1")
            val p2 = repo.create("P2")
            repo.setMembership(p1.id, app.codeg.android.core.datastore.SessionRef("a", 1), true)
            repo.setMembership(p2.id, app.codeg.android.core.datastore.SessionRef("a", 1), true)
            repo.setMembership(p1.id, app.codeg.android.core.datastore.SessionRef("a", 2), true)
            awaitMemberKey(repo, "a:2")

            val inverted = repo.memberProjects.first()
            assertEquals(setOf(p1.id, p2.id), inverted["a:1"])
            assertEquals(setOf(p1.id), inverted["a:2"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `remove drops the project and clears an active scope on it`() = runBlocking {
        val (repo, scope) = newRepo()
        try {
            val p = repo.create("P1")
            repo.activateCreated(p.id)
            repo.remove(p.id)
            // The in-memory scope clears synchronously (remove's first line).
            assertNull(repo.activeProjectId.value)
            var gone = false
            repeat(200) {
                if (repo.projects.value.none { it.id == p.id }) { gone = true; return@repeat }
                Thread.sleep(100)
            }
            org.junit.Assert.assertTrue("projects flow must drop the removed id", gone)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sentinel with zero members excludes nothing but is never null`() = runBlocking {
        // Twin of the R1-B P2-3 empty-project rule, complement direction: a
        // zero-member 未添加 scope means "exclude nobody", not "no guard".
        val (repo, scope) = newRepo()
        try {
            repo.setActive(UNASSIGNED_PROJECT_ID)
            assertEquals(emptySet<String>(), repo.activeExcludeKeys.first())
        } finally {
            scope.cancel()
        }
    }
}
