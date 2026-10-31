package app.codeg.android.feature.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [isDuplicateDetailPush] — the v134 same-conversation stacking guard. */
class ConversationNavTest {

    private val detail = "conversation/{id}"

    @Test
    fun `same id on top of the detail route dedupes`() {
        assertTrue(isDuplicateDetailPush(detail, 5, 5, "a", "a"))
    }

    @Test
    fun `different id on top still pushes`() {
        assertFalse(isDuplicateDetailPush(detail, 9, 5, "a", "a"))
    }

    @Test
    fun `cross-device same id is a different conversation and pushes`() {
        // Ball recent-session rank pointing at device B's #5 while device A's #5
        // detail is on top — server-local ids collide, this must NOT dedupe.
        assertFalse(isDuplicateDetailPush(detail, 5, 5, "b", "a"))
    }

    @Test
    fun `deep link (null server) dedupes on same id`() {
        assertTrue(isDuplicateDetailPush(detail, 5, 5, null, "a"))
    }

    @Test
    fun `unknown top device (process restore) dedupes on same id`() {
        assertTrue(isDuplicateDetailPush(detail, 5, 5, "a", null))
    }

    @Test
    fun `new-task route never matches the detail prefix`() {
        assertFalse(isDuplicateDetailPush("conversation_new?folderId={folderId}", null, 5, "a", "a"))
    }

    @Test
    fun `non-detail top (tab) always pushes`() {
        assertFalse(isDuplicateDetailPush("chats", null, 5, "a", "a"))
        assertFalse(isDuplicateDetailPush(null, null, 5, "a", "a"))
    }

    // ---- isConversationDetailRoute (v136 ball replace-top) ----

    @Test
    fun `detail route template is a conversation detail`() {
        assertTrue(isConversationDetailRoute("conversation/{id}"))
        assertTrue(isConversationDetailRoute("conversation/{id}?serverId={serverId}"))
    }

    @Test
    fun `new-task composer and tabs are not conversation details`() {
        // "conversation_new..." must never be mistaken for "conversation/" — the
        // replace-top rule would pop the composer instead of a real detail.
        assertFalse(isConversationDetailRoute("conversation_new?folderId={folderId}"))
        assertFalse(isConversationDetailRoute("conversation_new"))
        assertFalse(isConversationDetailRoute("chats"))
        assertFalse(isConversationDetailRoute("activity"))
        assertFalse(isConversationDetailRoute("conversation"))
        assertFalse(isConversationDetailRoute(null))
    }
}
