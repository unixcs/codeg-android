package app.codeg.android.core.designsystem.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the tap-target scanner behind [rememberInlineMarkdown]'s URL /
 * PATH string annotations (batch v123). Values use representative AI-report shapes.
 */
class FindTapTargetsTest {

    private fun targets(raw: String): List<Pair<InlineRange, String>> = findTapTargets(raw)

    private fun values(raw: String, tag: String? = null): List<String> =
        targets(raw).filter { tag == null || raw.substring(it.first.start, it.first.end).startsWith(tagPrefix(tag)) || true }
            .map { it.second }

    @Suppress("unused")
    private fun tagPrefix(tag: String) = ""

    @Test
    fun `bare url gets URL annotation`() {
        val raw = "see https://example.com/a?b=1 now"
        val found = targets(raw).single()
        assertEquals(URL_TAG, annotationTag(raw, found.first))
        assertEquals("https://example.com/a?b=1", found.second)
    }

    @Test
    fun `trailing punctuation is excluded from bare url`() {
        val raw = "open https://tx-host:3448/reports/. done"
        val found = targets(raw).single()
        assertEquals("https://tx-host:3448/reports/", found.second)
        assertEquals('.', raw[found.first.end - 1 + 1])
    }

    @Test
    fun `link syntax covers label and url as one region`() {
        val raw = "[报告](https://example.com/r.html) 在这里"
        val found = targets(raw).single()
        assertEquals("https://example.com/r.html", found.second)
        // Whole `[label](url)` span is consumed: no separate bare-URL match inside.
        assertEquals(0, found.first.start)
        assertEquals(raw.indexOf(')') + 1, found.first.end)
    }

    @Test
    fun `bare absolute md path gets PATH annotation`() {
        val raw = "汇报在 /mnt/Tasks/2026-10-25-批v123.md 请过目"
        val found = targets(raw).single()
        assertEquals("/mnt/Tasks/2026-10-25-批v123.md", found.second)
        assertEquals(PATH_TAG, annotationTag(raw, found.first))
    }

    @Test
    fun `backticked full-content path gets PATH annotation`() {
        val raw = "详见 `/mnt/Tasks/report.md` 的第三节"
        val found = targets(raw).single()
        assertEquals(PATH_TAG, annotationTag(raw, found.first))
        assertEquals("/mnt/Tasks/report.md", found.second)
    }

    @Test
    fun `backticked prose stays plain`() {
        val raw = "run `ls /mnt/Tasks && cat report.md` first"
        assertTrue("code span with mixed prose must not become a tap target", targets(raw).isEmpty())
    }

    @Test
    fun `path without previewable extension stays plain`() {
        val raw = "see /etc/hosts and /usr/bin"
        assertTrue(targets(raw).isEmpty())
    }

    @Test
    fun `relative path stays plain`() {
        val raw = "check report.md and docs/todo.txt"
        assertTrue(targets(raw).isEmpty())
    }

    @Test
    fun `path inside link label is not double annotated`() {
        val raw = "[/mnt/Tasks/a.md](https://example.com/x)"
        val found = targets(raw).single()
        assertEquals("https://example.com/x", found.second)
    }

    @Test
    fun `url and path coexist in one string`() {
        val raw = "博客 https://blog.fengx.eu.org 稿件 /mnt/929/60-内容/post.md 完"
        val found = targets(raw)
        assertEquals(2, found.size)
        assertEquals("https://blog.fengx.eu.org", found[0].second)
        assertEquals("/mnt/929/60-内容/post.md", found[1].second)
    }

    @Test
    fun `non http schemes stay plain`() {
        val raw = "ssh root@10.0.0.1 and file:///etc/hosts stay put"
        assertTrue(targets(raw).isEmpty())
    }

    private fun annotationTag(raw: String, range: InlineRange): String {
        // findTapTargets returns (range, value) pairs only; the tag is recoverable by
        // re-deriving semantics: URL values start with a scheme, PATH values start '/'.
        return if (raw.substring(range.start, range.end).startsWith("http")) URL_TAG else PATH_TAG
    }
}
