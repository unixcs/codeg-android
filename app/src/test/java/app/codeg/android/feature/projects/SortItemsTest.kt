package app.codeg.android.feature.projects

import app.codeg.android.core.datastore.FILE_SORT_MODES
import app.codeg.android.core.datastore.FILE_SORT_MODE_NAME
import app.codeg.android.core.datastore.FILE_SORT_MODE_SIZE
import app.codeg.android.core.datastore.FILE_SORT_MODE_TIME
import app.codeg.android.core.datastore.FILE_SORT_MODE_TYPE
import app.codeg.android.core.datastore.defaultFileSortDesc
import app.codeg.android.core.model.DirectoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批v194 M0: the four-key file-browser sort + direction flag. */
class SortItemsTest {

    private fun file(name: String, size: Long? = null, mtimeMs: Long? = null) =
        DirectoryItem(name = name, path = "/w/$name", isDir = false, size = size, mtimeMs = mtimeMs)

    private fun dir(name: String) = DirectoryItem(name = name, path = "/w/$name", isDir = true, hasChildren = true)

    @Test
    fun `whitelist gained time and type`() {
        assertEquals(setOf(FILE_SORT_MODE_NAME, FILE_SORT_MODE_SIZE, FILE_SORT_MODE_TIME, FILE_SORT_MODE_TYPE), FILE_SORT_MODES)
    }

    @Test
    fun `absent desc flag defaults descending only for size and time`() {
        assertFalse(defaultFileSortDesc(FILE_SORT_MODE_NAME))
        assertFalse(defaultFileSortDesc(FILE_SORT_MODE_TYPE))
        assertTrue(defaultFileSortDesc(FILE_SORT_MODE_SIZE))
        assertTrue(defaultFileSortDesc(FILE_SORT_MODE_TIME))
        // Legacy upgrade path: an old "size" pref without the flag keeps biggest-first.
        assertTrue(defaultFileSortDesc("size"))
        assertFalse(defaultFileSortDesc(null))
        assertFalse(defaultFileSortDesc("corrupt"))
    }

    @Test
    fun `name asc keeps dirs first and alpha`() {
        val out = sortItems(listOf(file("b.md"), dir("zed"), file("a.md"), dir("arc")), FILE_SORT_MODE_NAME)
        assertEquals(listOf("arc", "zed", "a.md", "b.md"), out.map { it.name })
    }

    @Test
    fun `name desc flips files but dirs stay first`() {
        val out = sortItems(listOf(file("b.md"), dir("zed"), file("a.md"), dir("arc")), FILE_SORT_MODE_NAME, desc = true)
        assertEquals(listOf("arc", "zed", "b.md", "a.md"), out.map { it.name })
    }

    @Test
    fun `size desc default is legacy biggest-first`() {
        val out = sortItems(listOf(file("s", 1), file("l", 9), file("m", 5)), FILE_SORT_MODE_SIZE, desc = true)
        assertEquals(listOf("l", "m", "s"), out.map { it.name })
    }

    @Test
    fun `time desc puts newest first and unprobed last`() {
        val out = sortItems(
            listOf(file("old", mtimeMs = 100), file("new", mtimeMs = 300), file("unknown")),
            FILE_SORT_MODE_TIME,
            desc = true,
        )
        assertEquals(listOf("new", "old", "unknown"), out.map { it.name })
    }

    @Test
    fun `time asc puts unprobed last too`() {
        val out = sortItems(
            listOf(file("old", mtimeMs = 100), file("new", mtimeMs = 300), file("unknown")),
            FILE_SORT_MODE_TIME,
            desc = false,
        )
        assertEquals(listOf("old", "new", "unknown"), out.map { it.name })
    }

    @Test
    fun `type sorts by extension then name`() {
        val out = sortItems(listOf(file("b.md"), file("a.txt"), file("a.md"), file("z.txt")), FILE_SORT_MODE_TYPE)
        assertEquals(listOf("a.md", "b.md", "a.txt", "z.txt"), out.map { it.name })
    }

    @Test
    fun `extensionless files sort under the empty extension`() {
        val out = sortItems(listOf(file("README"), file("a.md")), FILE_SORT_MODE_TYPE)
        assertEquals(listOf("README", "a.md"), out.map { it.name })
    }
}
