package app.codeg.android.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class DocShareMimeTest {

    @Test
    fun `text-like documents are shared as attachments`() {
        assertEquals("application/octet-stream", resolveShareFileMime("notes.md"))
        assertEquals("application/octet-stream", resolveShareFileMime("index.html"))
        assertEquals("application/octet-stream", resolveShareFileMime("data.json"))
        assertEquals("application/octet-stream", resolveShareFileMime("main.kt"))
    }

    @Test
    fun `real media keeps its specific mime`() {
        assertEquals("application/pdf", resolveShareFileMime("doc.pdf"))
        assertEquals("image/png", resolveShareFileMime("shot.png"))
        assertEquals("video/mp4", resolveShareFileMime("demo.mp4"))
        assertEquals("audio/mpeg", resolveShareFileMime("voice.mp3"))
    }
}
