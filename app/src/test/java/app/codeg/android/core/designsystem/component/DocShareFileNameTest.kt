package app.codeg.android.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocShareFileNameTest {

    @Test
    fun `share filename drops traversal prefixes`() {
        assertEquals("report.md", safeShareFileName("../../report.md"))
        assertEquals("report.md", safeShareFileName("..\\..\\report.md"))
        assertEquals("document.txt", safeShareFileName("../"))
        assertEquals("document.txt", safeShareFileName(".."))
    }

    @Test
    fun `share filename keeps a short extension while truncating`() {
        val name = "很长的文档".repeat(30) + ".markdown"
        val safe = safeShareFileName(name)

        assertTrue(safe.codePointCount(0, safe.length) <= 60)
        assertTrue(safe.endsWith(".markdown"))
        assertFalse(safe.contains('/'))
        assertFalse(safe.contains('\\'))
    }
}
