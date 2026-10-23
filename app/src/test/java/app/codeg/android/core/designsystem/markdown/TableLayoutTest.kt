package app.codeg.android.core.designsystem.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks down the table width distribution that replaced the fixed 130dp cells. */
class TableLayoutTest {

    @Test
    fun `two columns fill the available width`() {
        val layout = tableColumnWidths(listOf(100f, 100f), availableWidth = 300f)
        assertFalse(layout.overflow)
        assertEquals(2, layout.widths.size)
        assertEquals(300f, layout.widths.sum(), 0.01f)
        assertEquals(150f, layout.widths[0], 0.01f)
    }

    @Test
    fun `wide content is compressed proportionally, not scrolled`() {
        val layout = tableColumnWidths(listOf(300f, 300f, 300f), availableWidth = 500f)
        assertFalse(layout.overflow)
        assertEquals(500f, layout.widths.sum(), 0.01f)
        assertTrue(layout.widths.all { it >= 64f })
    }

    @Test
    fun `content below the per-column floor overflows at natural widths`() {
        val natural = listOf(300f, 300f, 300f)
        val layout = tableColumnWidths(natural, availableWidth = 180f)
        assertTrue(layout.overflow)
        assertEquals(natural, layout.widths)
    }

    @Test
    fun `empty table has no columns`() {
        val layout = tableColumnWidths(emptyList(), availableWidth = 300f)
        assertFalse(layout.overflow)
        assertTrue(layout.widths.isEmpty())
    }

    @Test
    fun `header only table still estimates every column`() {
        val widths = estimateNaturalWidths(listOf("A", "B"), emptyList())
        assertEquals(2, widths.size)
        assertTrue(widths.all { it >= 64f })
    }

    @Test
    fun `ragged rows widen the column count`() {
        val widths = estimateNaturalWidths(
            header = listOf("A", "B"),
            rows = listOf(listOf("1"), listOf("1", "2", "3")),
        )
        assertEquals(3, widths.size)
    }

    @Test
    fun `cjk text estimates wider than ascii`() {
        val ascii = estimateNaturalWidths(listOf("abcd"), emptyList()).single()
        val cjk = estimateNaturalWidths(listOf("中文中文"), emptyList()).single()
        assertTrue(cjk > ascii)
    }
}
