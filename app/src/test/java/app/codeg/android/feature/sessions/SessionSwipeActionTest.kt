package app.codeg.android.feature.sessions

import app.codeg.android.core.datastore.SESSION_SWIPE_ACTIONS
import app.codeg.android.core.datastore.SESSION_SWIPE_NONE
import app.codeg.android.core.datastore.sanitizeSessionSwipeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批v168: the swipe-binding catalog and its storage-side sanitize rules. */
class SessionSwipeActionTest {

    @Test
    fun `every action id round-trips through from`() {
        SessionSwipeAction.entries.forEach { action ->
            assertEquals(action, SessionSwipeAction.from(action.id))
        }
    }

    @Test
    fun `none unknown blank and null resolve to no binding`() {
        assertNull(SessionSwipeAction.from(null))
        assertNull(SessionSwipeAction.from(SessionSwipeAction.NONE_ID))
        assertNull(SessionSwipeAction.from(""))
        assertNull(SessionSwipeAction.from("legacy_garbage"))
    }

    @Test
    fun `ids are unique and non-blank`() {
        val ids = SessionSwipeAction.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun `storage whitelist is exactly the enum catalog plus none`() {
        assertEquals(
            SessionSwipeAction.entries.map { it.id }.toSet() + SESSION_SWIPE_NONE,
            SESSION_SWIPE_ACTIONS,
        )
    }

    @Test
    fun `sanitize keeps known ids and falls back on everything else`() {
        SessionSwipeAction.entries.forEach { action ->
            assertEquals(action.id, sanitizeSessionSwipeAction(action.id, default = "copy"))
        }
        assertEquals(SESSION_SWIPE_NONE, sanitizeSessionSwipeAction(SESSION_SWIPE_NONE, default = "copy"))
        assertEquals("copy", sanitizeSessionSwipeAction(null, default = "copy"))
        assertEquals("copy", sanitizeSessionSwipeAction("nope", default = "copy"))
        assertEquals("copy", sanitizeSessionSwipeAction("", default = "copy"))
    }
}
