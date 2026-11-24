package app.codeg.android.core.designsystem.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedTableLayoutTest {

    @Test
    fun `wide table keeps row identity visible and gives overflow columns real width`() {
        val layout = pinnedTableLayout(
            naturalWidths = listOf(90f, 240f, 240f),
            availableWidth = 360f,
        )
        assertEquals(90f, layout.pinnedWidth)
        assertEquals(2, layout.scrollWidths.size)
        assertEquals(254f, layout.scrollTotalWidth)
        assertEquals(127f, layout.scrollWidths[0])
        assertEquals(127f, layout.scrollWidths[1])
    }

    @Test
    fun `single column table has no scroll body`() {
        val layout = pinnedTableLayout(listOf(200f), availableWidth = 300f)
        assertEquals(104f, layout.pinnedWidth)
        assertEquals(0, layout.scrollWidths.size)
        assertEquals(0f, layout.scrollTotalWidth)
    }
}
