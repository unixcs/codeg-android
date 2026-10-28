package app.codeg.android.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Mutator semantics of the session-project persistence (batch v132). Uses a real
 * Preferences DataStore on a temp file — the store's whole point is that every
 * mutation lands in ONE edit, so an in-memory fake would prove nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionProjectStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.newStore(): Pair<SessionProjectStore, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dir = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "projects.preferences_pb")
        }
        return SessionProjectStore(dataStore) to scope
    }

    private fun project(id: String, name: String = id) =
        SessionProject(id = id, name = name, createdAt = 1L, sortOrder = 1)

    private fun ref(server: String, id: Int) = SessionRef(server, id)

    @Test
    fun `create rename remove cascade`() = runTest {
        val (store, scope) = newStore()
        try {
            store.create(project("p1", "Work"))
            store.create(project("p2", "Study"))
            assertEquals(listOf("p1", "p2"), store.projects.first().map { it.id })

            store.rename("p1", "Personal")
            assertEquals("Personal", store.projects.first().first { it.id == "p1" }.name)

            // Removing a project also drops its membership entry.
            store.setMembership("p1", ref("a", 1), true)
            store.setMembership("p2", ref("a", 1), true)
            store.removeProject("p1")
            assertEquals(listOf("p2"), store.projects.first().map { it.id })
            assertEquals(setOf("p2"), store.members.first().keys)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `setMembership toggles by ref key, not by object identity`() = runTest {
        val (store, scope) = newStore()
        try {
            store.create(project("p1"))
            store.setMembership("p1", ref("a", 1), true)
            store.setMembership("p1", ref("a", 1), true) // idempotent add
            assertEquals(listOf(ref("a", 1)), store.members.first()["p1"])
            store.setMembership("p1", SessionRef("a", 1), false) // equal-content ref removes
            assertFalse(store.members.first()["p1"].orEmpty().isNotEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `removeRef drops the ref from every project, removeRefsForServer from all projects`() = runTest {
        val (store, scope) = newStore()
        try {
            store.create(project("p1"))
            store.create(project("p2"))
            store.setMembership("p1", ref("a", 1), true)
            store.setMembership("p1", ref("a", 2), true)
            store.setMembership("p2", ref("a", 1), true)
            store.setMembership("p2", ref("b", 5), true)

            store.removeRef("a", 1)
            assertEquals(listOf(ref("a", 2)), store.members.first()["p1"])
            assertEquals(listOf(ref("b", 5)), store.members.first()["p2"])

            store.removeRefsForServer("b")
            assertEquals(listOf(ref("a", 2)), store.members.first()["p1"])
            assertTrue("empty entries are cleaned up", store.members.first()["p2"] == null)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pruneServer drops only this server's dead refs and keeps live ones`() = runTest {
        val (store, scope) = newStore()
        try {
            store.create(project("p1"))
            store.setMembership("p1", ref("a", 1), true)
            store.setMembership("p1", ref("a", 2), true)
            store.setMembership("p1", ref("b", 3), true)

            // liveKeys carries BARE conversation-id strings — the same shape the
            // aggregate fetch passes (convs.map { it.id.toString() }).
            store.pruneServer("a", liveKeys = setOf("2"))
            assertEquals(
                "a:1 pruned (dead), a:2 kept (live), b:3 untouched",
                setOf("a:2", "b:3"),
                store.members.first()["p1"]?.map { it.key }?.toSet(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `corrupt json degrades to empty instead of throwing on every read`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dir = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "corrupt.preferences_pb")
        }
        try {
            dataStore.edit { it[stringPreferencesKey("session_projects")] = "{not json" }
            dataStore.edit { it[stringPreferencesKey("session_project_members")] = "][ garbage" }
            val store = SessionProjectStore(dataStore)
            assertEquals(0, store.projects.first().size)
            assertTrue(store.members.first().isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
