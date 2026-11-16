package app.codeg.android.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 批v180-C: the file tree's expand-memory store — key format + flip roundtrip. */
class TreeExpandStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `expansion keys roundtrip per device-root context`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "tree.preferences_pb")
            }
            val store = TreeExpandStore(dataStore)
            assertTrue(store.expanded.first().isEmpty())

            val k1 = "srv-a|/mnt/Projects|/mnt/Projects/929"
            val k2 = "srv-b|/mnt/Projects|/mnt/Projects/929"
            val k3 = "srv-a|/mnt/Projects|/mnt/Projects/929/50-基建"

            store.setExpanded(k1, true)
            store.setExpanded(k3, true)
            // Same path on ANOTHER device (or another root) stays independent.
            store.setExpanded(k2, true)
            store.setExpanded(k2, false)

            val state = store.expanded.first()
            assertTrue(k1 in state)
            assertTrue(k3 in state)
            assertFalse(k2 in state)

            store.setExpanded(k1, false)
            assertFalse(k1 in store.expanded.first())
            // Collapsing the parent leaves the child key alone (inert ghost —
            // it matches nothing while the parent is collapsed).
            assertTrue(k3 in store.expanded.first())
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }
}
