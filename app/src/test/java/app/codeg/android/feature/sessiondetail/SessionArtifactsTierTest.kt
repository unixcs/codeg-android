package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionArtifactsTierTest {
    @Test
    fun `title tap toggles between collapsed and compact`() {
        assertEquals(SessionArtifactsTier.COMPACT, SessionArtifactsTier.COLLAPSED.toggleExpanded())
        assertEquals(SessionArtifactsTier.COLLAPSED, SessionArtifactsTier.COMPACT.toggleExpanded())
        assertEquals(SessionArtifactsTier.COLLAPSED, SessionArtifactsTier.FULL.toggleExpanded())
    }

    @Test
    fun `full expand button toggles full and compact`() {
        assertEquals(SessionArtifactsTier.FULL, SessionArtifactsTier.COLLAPSED.toggleFullExpand())
        assertEquals(SessionArtifactsTier.FULL, SessionArtifactsTier.COMPACT.toggleFullExpand())
        assertEquals(SessionArtifactsTier.COMPACT, SessionArtifactsTier.FULL.toggleFullExpand())
    }
}
