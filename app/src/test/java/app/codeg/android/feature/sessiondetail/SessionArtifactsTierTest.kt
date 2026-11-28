package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionArtifactsTierTest {
    @Test
    fun `title tap cycles collapse compact and full`() {
        assertEquals(SessionArtifactsTier.COMPACT, SessionArtifactsTier.COLLAPSED.next())
        assertEquals(SessionArtifactsTier.FULL, SessionArtifactsTier.COMPACT.next())
        assertEquals(SessionArtifactsTier.COLLAPSED, SessionArtifactsTier.FULL.next())
    }
}
