package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure mapping tests for the transcript doc-preview (batch v123). */
class DocPreviewKindTest {

    @Test
    fun `markdown names map to markdown rendering`() {
        assertEquals(DocPreviewKind.MARKDOWN, docPreviewKindOf("汇报.md"))
        assertEquals(DocPreviewKind.MARKDOWN, docPreviewKindOf("README.markdown"))
        assertEquals(DocPreviewKind.MARKDOWN, docPreviewKindOf("Report.MD"))
    }

    @Test
    fun `html names map to html rendering`() {
        assertEquals(DocPreviewKind.HTML, docPreviewKindOf("index.html"))
        assertEquals(docPreviewKindOf("a.HTML"), DocPreviewKind.HTML)
        assertEquals(docPreviewKindOf("page.htm"), DocPreviewKind.HTML)
    }

    @Test
    fun `plain text shapes map to text rendering`() {
        for (name in listOf("notes.txt", "app.log", "data.json", "out.csv", "Makefile")) {
            assertEquals("expected TEXT for $name", DocPreviewKind.TEXT, docPreviewKindOf(name))
        }
    }

    @Test
    fun `unknown extensions stay OTHER`() {
        assertEquals(DocPreviewKind.OTHER, docPreviewKindOf("photo.png"))
        assertEquals(DocPreviewKind.OTHER, docPreviewKindOf("app.apk"))
    }
}
