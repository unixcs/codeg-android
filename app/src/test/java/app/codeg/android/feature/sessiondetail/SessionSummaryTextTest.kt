package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the one-tap "copy for handoff" alignment block (batch v123):
 * the agent-prefixed CLI id, optional-line dropping, and the nothing-to-copy
 * degradation. The block is what a user pastes into a fresh session to realign
 * the AI, so every present line must keep its label.
 */
class SessionSummaryTextTest {

    @Test
    fun `full block keeps every labeled line in order`() {
        val text = buildSessionSummaryText(
            workingDir = "/mnt/vps/projects/demo",
            title = "修复登录超时",
            agentName = "ZCode",
            cliId = "sess-abc",
            codegId = "42",
            gitBranch = "main",
        )!!
        val lines = text.split("\n")
        assertEquals(
            listOf(
                "工作目录: /mnt/vps/projects/demo",
                "会话标题: 修复登录超时",
                "ZCode CLI ID: sess-abc",
                "Codeg ID: 42",
                "Git 分支: main",
            ),
            lines,
        )
    }

    @Test
    fun `agent name prefixes the CLI id line`() {
        val text = buildSessionSummaryText("/tmp", null, "Claude Code", "s1", "1", null)!!
        assertTrue(text.contains("Claude Code CLI ID: s1"))
    }

    @Test
    fun `absent optional lines are dropped, not emptied`() {
        val text = buildSessionSummaryText("/tmp", null, "Codex CLI", null, "7", null)!!
        val lines = text.split("\n")
        assertEquals(2, lines.size)
        assertFalse(lines.any { it.startsWith("会话标题") })
        assertFalse(lines.any { it.contains("CLI ID") })
        assertFalse(lines.any { it.startsWith("Git 分支") })
    }

    @Test
    fun `blank strings degrade the same as null`() {
        assertEquals(
            buildSessionSummaryText(null, null, "ZCode", null, null, null),
            buildSessionSummaryText("  ", "  ", "ZCode", " ", null, "  "),
        )
    }

    @Test
    fun `title alone is enough to copy`() {
        val text = buildSessionSummaryText(null, "title", "ZCode", null, null, null)!!
        assertEquals("会话标题: title", text)
    }

    @Test
    fun `blank branch line is dropped like null`() {
        val text = buildSessionSummaryText(null, null, "ZCode", null, "42", "  ")!!
        assertEquals("Codeg ID: 42", text)
    }

    @Test
    fun `truly nothing returns null`() {
        assertNull(buildSessionSummaryText(null, null, "ZCode", null, null, null))
    }

    @Test
    fun `codeg id alone is enough to copy`() {
        val text = buildSessionSummaryText(null, null, "ZCode", null, "42", null)!!
        assertEquals("Codeg ID: 42", text)
    }
}
