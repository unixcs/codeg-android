package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.FolderDetail
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Batch v134: the new-task default-folder precedence chain. */
class DraftFolderResolverTest {

    private fun folder(id: Int, name: String, parentId: Int? = null, openedAt: Instant? = null) =
        FolderDetail(
            id = id,
            name = name,
            path = "/w/$name",
            parentId = parentId,
            lastOpenedAt = openedAt,
        )

    private val root929 = folder(1, "929", openedAt = Instant.ofEpochSecond(100))
    private val jiaofu = folder(2, "jiaofu", openedAt = Instant.ofEpochSecond(300))
    private val worktree = folder(3, "929-task-7", parentId = 1, openedAt = Instant.ofEpochSecond(200))
    private val all = listOf(root929, jiaofu, worktree)
    private val topLevel = listOf(jiaofu, root929) // most-recently-opened first

    @Test
    fun `explicit argument wins over pin and last used`() {
        val picked = resolveDraftFolder(all, topLevel, folderIdArg = 1, pinnedFolderId = 2, lastUsedFolderId = 2)
        assertEquals(1, picked?.id)
    }

    @Test
    fun `explicit argument resolves a worktree that the picker never lists`() {
        val picked = resolveDraftFolder(all, topLevel, folderIdArg = 3, pinnedFolderId = 2, lastUsedFolderId = 2)
        assertEquals(3, picked?.id)
    }

    @Test
    fun `pin beats last used`() {
        val picked = resolveDraftFolder(all, topLevel, folderIdArg = null, pinnedFolderId = 1, lastUsedFolderId = 2)
        assertEquals(1, picked?.id)
    }

    @Test
    fun `a stale pin falls through to the valid last used`() {
        // Folder 99 was pinned then closed: it is not in topLevel any more.
        val picked = resolveDraftFolder(all, topLevel, folderIdArg = null, pinnedFolderId = 99, lastUsedFolderId = 2)
        assertEquals(2, picked?.id)
    }

    @Test
    fun `stale pin and stale last used fall back to the most recent top level folder`() {
        val picked = resolveDraftFolder(all, topLevel, folderIdArg = null, pinnedFolderId = 99, lastUsedFolderId = 98)
        assertEquals(2, picked?.id) // jiaofu sorts first
    }

    @Test
    fun `no memory picks the first top level folder`() {
        val picked = resolveDraftFolder(all, topLevel, folderIdArg = null, pinnedFolderId = null, lastUsedFolderId = null)
        assertEquals(2, picked?.id)
    }

    @Test
    fun `empty top level set falls back to the most recently opened folder of any kind`() {
        val picked = resolveDraftFolder(all, topLevel = emptyList(), folderIdArg = null, pinnedFolderId = 1, lastUsedFolderId = 2)
        assertEquals(2, picked?.id) // openedAt 300 beats worktree 200 and 929 100
    }

    @Test
    fun `no folders at all resolves to null`() {
        assertNull(resolveDraftFolder(emptyList(), emptyList(), folderIdArg = null, pinnedFolderId = null, lastUsedFolderId = null))
    }
}
