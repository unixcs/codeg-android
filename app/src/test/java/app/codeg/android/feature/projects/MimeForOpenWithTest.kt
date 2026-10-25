package app.codeg.android.feature.projects

import org.junit.Assert.assertEquals
import org.junit.Test

/** Batch v125: the open-with card's MIME decides which apps the chooser offers. */
class MimeForOpenWithTest {
    @Test fun textFamilyIsPlainTextSoAnyEditorCanOpenIt() {
        assertEquals("text/plain", mimeForOpenWith("手册.md"))
        assertEquals("text/plain", mimeForOpenWith("notes.markdown"))
        assertEquals("text/plain", mimeForOpenWith("run.log"))
        assertEquals("text/plain", mimeForOpenWith("data.json"))
        assertEquals("text/plain", mimeForOpenWith("build.gradle.kts"))
    }

    @Test fun extensionLookupIsCaseInsensitive() {
        assertEquals("text/html", mimeForOpenWith("Page.HTML"))
        assertEquals("image/jpeg", mimeForOpenWith("photo.Jpeg"))
    }

    @Test fun specificViewersGetSpecificMimes() {
        assertEquals("text/html", mimeForOpenWith("index.html"))
        assertEquals("image/png", mimeForOpenWith("shot.png"))
        assertEquals("application/pdf", mimeForOpenWith("doc.pdf"))
        assertEquals("application/zip", mimeForOpenWith("bundle.zip"))
    }

    @Test fun unknownOrMissingExtensionFallsBackToWildcard() {
        assertEquals("*/*", mimeForOpenWith("no-extension"))
        assertEquals("*/*", mimeForOpenWith("weird.xyzzy"))
        assertEquals("*/*", mimeForOpenWith(".hidden"))
    }
}
