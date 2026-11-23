package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批v194 M3: the transcript → artifacts pure function. */
class SessionArtifactsTest {

    private fun turn(blocks: List<ContentBlock>, role: TurnRole = TurnRole.ASSISTANT) =
        MessageTurn(id = "t${Instant.now().nano}", role = role, blocks = blocks, timestamp = Instant.now())

    private fun use(id: String, name: String, input: String) =
        ContentBlock.ToolUse(id = id, name = name, inputPreview = input)

    private fun result(id: String, error: Boolean = false) =
        ContentBlock.ToolResult(id = id, outputPreview = "", isError = error)

    @Test
    fun `write tool result yields an added doc artifact`() {
        val turns = listOf(
            turn(listOf(use("1", "write_file", """{"file_path":"/w/report.md","content":"hi"}"""))),
            turn(listOf(result("1"))),
        )
        val arts = SessionArtifacts.derive(turns, workingDir = null)
        assertEquals(1, arts.size)
        val a = arts[0]
        assertEquals("/w/report.md", a.path)
        assertEquals("report.md", a.name)
        assertEquals("w", a.dirTail)
        assertEquals("md", a.ext)
        assertTrue(a.isDocLike)
        assertEquals(ArtifactOp.ADDED, a.op)
        assertEquals("Write", a.toolLabel)
    }

    @Test
    fun `failed call is not an artifact`() {
        val turns = listOf(
            turn(listOf(use("1", "write_file", """{"file_path":"/w/a.md"}"""))),
            turn(listOf(result("1", error = true))),
            turn(listOf(use("2", "edit", """{"file_path":"/w/b.md"}"""))),
            turn(listOf(result("2"))),
        )
        val arts = SessionArtifacts.derive(turns, workingDir = null)
        assertEquals(listOf("/w/b.md"), arts.map { it.path })
        assertEquals(ArtifactOp.MODIFIED, arts[0].op)
    }

    @Test
    fun `unresolved running call produces nothing`() {
        val turns = listOf(turn(listOf(use("1", "write_file", """{"file_path":"/w/a.md"}"""))))
        assertTrue(SessionArtifacts.derive(turns, workingDir = null).isEmpty())
    }

    @Test
    fun `apply_patch body yields one entry per file with multi count`() {
        val patch = """
            *** Begin Patch
            *** Add File: docs/plan.md
            +hello
            *** Update File: src/App.kt
             old
            *** Delete File: junk.log
            *** End Patch
        """.trimIndent()
        val turns = listOf(
            turn(listOf(use("1", "apply_patch", """{"patch":${jsonStr(patch)}}"""))),
            turn(listOf(result("1"))),
        )
        val arts = SessionArtifacts.derive(turns, workingDir = "/w")
        assertEquals(listOf("/w/junk.log", "/w/src/App.kt", "/w/docs/plan.md"), arts.map { it.path })
        assertEquals("Patch (3)", arts[0].toolLabel)
        assertEquals(ArtifactOp.DELETED, arts.first { it.path == "/w/junk.log" }.op)
        assertEquals(ArtifactOp.MODIFIED, arts.first { it.path == "/w/src/App.kt" }.op)
        assertTrue(arts.first { it.path == "/w/docs/plan.md" }.isDocLike)
        assertFalse(arts.first { it.path == "/w/src/App.kt" }.isDocLike)
    }

    @Test
    fun `rewrite collapses to latest state and newest sorts first`() {
        val turns = listOf(
            turn(listOf(use("1", "write_file", """{"file_path":"/w/a.md"}"""))),
            turn(listOf(result("1"))),
            turn(listOf(use("2", "edit", """{"file_path":"/w/a.md"}"""))),
            turn(listOf(result("2"))),
            turn(listOf(use("3", "write_file", """{"file_path":"/w/z.md"}"""))),
            turn(listOf(result("3"))),
        )
        val arts = SessionArtifacts.derive(turns, workingDir = null)
        assertEquals(listOf("/w/z.md", "/w/a.md"), arts.map { it.path })
        assertEquals(ArtifactOp.MODIFIED, arts[1].op)
    }

    @Test
    fun `relative path resolves against working dir and mcp prefix strips`() {
        val turns = listOf(
            turn(listOf(use("1", "mcp__fs__write_file", """{"path":"out/notes.txt"}"""))),
            turn(listOf(result("1"))),
        )
        val arts = SessionArtifacts.derive(turns, workingDir = "/w/proj")
        assertEquals("/w/proj/out/notes.txt", arts[0].path)
        assertEquals("Write", arts[0].toolLabel)
    }

    @Test
    fun `non-write tools are ignored`() {
        val turns = listOf(
            turn(listOf(use("1", "read", """{"file_path":"/w/a.md"}"""))),
            turn(listOf(result("1"))),
            turn(listOf(use("2", "bash", """{"command":"echo hi > x.md"}"""))),
            turn(listOf(result("2"))),
        )
        assertTrue(SessionArtifacts.derive(turns, workingDir = null).isEmpty())
    }

    @Test
    fun `multiedit changes array yields one artifact per entry`() {
        val turns = listOf(
            turn(
                listOf(
                    use(
                        "1",
                        "multiedit",
                        """{"changes":[{"file_path":"/w/a.kt","old":"x","new":"y"},{"file_path":"/w/b.md","old":"p","new":"q"},{"note":"no path"}]}""",
                    ),
                ),
            ),
            turn(listOf(result("1"))),
        )
        val arts = SessionArtifacts.derive(turns, workingDir = null)
        assertEquals(listOf("/w/a.kt", "/w/b.md"), arts.map { it.path })
        assertEquals("Edit", arts[0].toolLabel)
        assertEquals(ArtifactOp.MODIFIED, arts[0].op)
        assertTrue(arts[1].isDocLike)
    }

    @Test
    fun `edits array without per-entry paths falls back to top-level path`() {
        val turns = listOf(
            turn(listOf(use("1", "edit", """{"file_path":"/w/a.kt","edits":[{"old":"x","new":"y"}]}"""))),
            turn(listOf(result("1"))),
        )
        val arts = SessionArtifacts.derive(turns, workingDir = null)
        assertEquals(listOf("/w/a.kt"), arts.map { it.path })
    }

    @Test
    fun `garbage input does not throw`() {
        val turns = listOf(
            turn(listOf(use("1", "write_file", "not-json{"))),
            turn(listOf(result("1"))),
        )
        assertTrue(SessionArtifacts.derive(turns, workingDir = null).isEmpty())
    }
}

private fun jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
