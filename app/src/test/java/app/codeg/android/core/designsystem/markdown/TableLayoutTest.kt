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
    fun `a squeezed narrow column is pinned at the floor and the wide one absorbs the rest`() {
        // The regression that motivated water-fill: naive proportional shrink says
        // [10,500] needs 510dp at the 64dp floor and overflowed at 200dp — but
        // [64,136] fits without scrolling.
        val layout = tableColumnWidths(listOf(10f, 500f), availableWidth = 200f)
        assertFalse(layout.overflow)
        assertEquals(listOf(64f, 136f), layout.widths)
    }

    @Test
    fun `columns below the floor that cannot all fit overflow at the floor widths`() {
        val layout = tableColumnWidths(listOf(300f, 300f, 300f), availableWidth = 180f)
        assertTrue(layout.overflow)
        // The floor widths (not the naturals) — the caller scrolls, so narrower is better.
        assertEquals(listOf(64f, 64f, 64f), layout.widths)
    }

    @Test
    fun `redistribution may exceed a column's natural width to fill the row`() {
        // One huge column + two tiny ones: the tiny columns pin at 64 and the wide
        // column takes everything left — well over its proportional share.
        val layout = tableColumnWidths(listOf(300f, 10f, 10f), availableWidth = 240f)
        assertFalse(layout.overflow)
        assertEquals(240f, layout.widths.sum(), 0.01f)
        assertEquals(64f, layout.widths[1], 0.01f)
        assertEquals(64f, layout.widths[2], 0.01f)
        assertTrue(layout.widths[0] > 64f)
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
