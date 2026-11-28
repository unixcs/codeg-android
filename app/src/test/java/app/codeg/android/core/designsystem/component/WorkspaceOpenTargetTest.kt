package app.codeg.android.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceOpenTargetTest {

    @Test
    fun `nested artifact keeps the workspace root and full relative path`() {
        assertEquals(
            WorkspaceOpenTarget("/workspace/project", "out/demo.mp4"),
            resolveWorkspaceOpenTarget("/workspace/project", "/workspace/project/out/demo.mp4"),
        )
        assertEquals(
            WorkspaceOpenTarget("/workspace/project", "a/b/c.mp3"),
            resolveWorkspaceOpenTarget("/workspace/project/", "/workspace/project/a/b/c.mp3"),
        )
    }

    @Test
    fun `artifact outside the workspace re-roots at its parent`() {
        assertEquals(
            WorkspaceOpenTarget("/tmp/render", "demo.mp4"),
            resolveWorkspaceOpenTarget("/workspace/project", "/tmp/render/demo.mp4"),
        )
    }

    @Test
    fun `relative artifact resolves against the workspace`() {
        assertEquals(
            WorkspaceOpenTarget("/workspace/project", "out/demo.mp4"),
            resolveWorkspaceOpenTarget("/workspace/project", "./out/demo.mp4"),
        )
    }

    @Test
    fun `traversal and unusable names are rejected`() {
        assertNull(resolveWorkspaceOpenTarget("/workspace/project", "/workspace/project/../secret.mp4"))
        assertNull(resolveWorkspaceOpenTarget("/workspace/project", "../secret.mp4"))
        assertNull(resolveWorkspaceOpenTarget("/workspace/project", "/workspace/project/out/.."))
        assertNull(resolveWorkspaceOpenTarget("", "/workspace/project/out/demo.mp4"))
    }
}
