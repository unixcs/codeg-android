package app.codeg.android.feature.sessions

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ScanFolder
import app.codeg.android.core.model.ScanResult
import app.codeg.android.core.model.ScanSession
import app.codeg.android.core.model.ScanSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入 UX 批（M7）的纯函数层测试：可见性、元数据搜索、组头三态与工具条
 * 全选的集合运算。这些 file-level internal 函数是 toggleSession / importSelected
 * 护栏口径的同一份实现（legalKeys 的判定与 VM 里逐字相同），这里钉死：
 * 组选/全选永远只碰"可见且合法"的 keys，被搜索/筛掉的已勾项保留。
 */
class SessionImportFilterLogicTest {

    private fun session(
        id: String,
        agent: AgentType = AgentType.CLAUDE_CODE,
        title: String? = null,
        status: ScanSessionStatus = ScanSessionStatus.NEW,
        branch: String? = null,
    ): ScanSession = ScanSession(
        externalId = id,
        agentType = agent,
        title = title,
        startedAt = "2026-11-01T10:00:00Z",
        messageCount = 4,
        gitBranch = branch,
        status = status,
    )

    private fun folder(
        path: String,
        sessions: List<ScanSession>,
        agentTypes: List<AgentType> = sessions.map { it.agentType }.distinct(),
    ): ScanFolder = ScanFolder(
        path = path,
        name = path.substringAfterLast('/'),
        agentTypes = agentTypes,
        sessions = sessions,
    )

    private fun compute(
        folders: List<ScanFolder>,
        query: String = "",
        hiddenFolders: Set<String> = emptySet(),
        hiddenAgents: Set<AgentType> = emptySet(),
        showImported: Boolean = false,
        includeDeleted: Boolean = false,
    ): List<VisibleFolder> = computeVisibleFolders(
        ScanResult(
            folders = folders,
            noFolderCount = 0,
            totalSessions = folders.sumOf { it.sessions.size },
            importableCount = folders.sumOf { it.newCount },
        ),
        query,
        hiddenFolders,
        hiddenAgents,
        showImported,
        includeDeleted,
    )

    @Test
    fun `组头三态 - 未选整组加入 全选整组清除 部分勾选补全`() {
        val a = session("a", title = "A")
        val b = session("b", title = "B")
        val groups = compute(listOf(folder("/p/929", listOf(a, b))))
        val keys = groups.single().legalKeys
        assertEquals(setOf("claude_code|a", "claude_code|b"), keys)

        // 未选 → 整组加入
        assertEquals(ImportSelectState.NONE, folderToggleState(keys, emptySet()))
        assertEquals(keys, toggleKeys(emptySet(), keys))
        // 全选 → 整组清除
        assertEquals(ImportSelectState.ALL, folderToggleState(keys, keys))
        assertEquals(emptySet<String>(), toggleKeys(keys, keys))
        // 部分 → 补全为全选
        val partial = setOf("claude_code|a")
        assertEquals(ImportSelectState.SOME, folderToggleState(keys, partial))
        assertEquals(keys, toggleKeys(partial, keys))
    }

    @Test
    fun `组头 legalKeys 不含 UNKNOWN 与未含 deleted 的 deleted 行`() {
        val unknown = session("u", agent = AgentType.UNKNOWN)
        val deleted = session("d", status = ScanSessionStatus.DELETED)
        val imported = session("i", status = ScanSessionStatus.IMPORTED)
        val fresh = session("n", title = "N")
        val groups = compute(
            listOf(
                folder("/p/x", listOf(unknown, deleted, imported, fresh), agentTypes = listOf(AgentType.CLAUDE_CODE, AgentType.UNKNOWN)),
            ),
        )
        assertEquals(setOf("claude_code|n"), groups.single().legalKeys)
        // D3 默认口径：imported/deleted 行直接不进 visibleSessions（开关才可见）
        assertEquals(2, groups.single().visibleSessions.size)
        assertEquals(ImportSelectState.NONE, folderToggleState(groups.single().legalKeys, setOf("claude_code|d")))
    }

    @Test
    fun `全选可见只作用于搜索命中的行 - 被筛掉的已勾项保留`() {
        val scoped = compute(
            folders = listOf(
                folder("/p/929", listOf(session("a", title = "deploy doc"), session("b", title = "fix bug"))),
                folder("/p/aios", listOf(session("c", title = "deploy doc too"), session("d", title = "misc"))),
            ),
            query = "deploy",
        )
        val visibleKeys = scoped.flatMapTo(mutableSetOf()) { it.legalKeys }
        assertEquals(setOf("claude_code|a", "claude_code|c"), visibleKeys)

        val preSelected = setOf("claude_code|b") // 被搜索藏起来、但已勾选
        val afterAll = toggleKeys(preSelected, visibleKeys)
        assertEquals(setOf("claude_code|b", "claude_code|a", "claude_code|c"), afterAll)
        // 全不选只清可见项
        val afterNone = toggleKeys(afterAll, visibleKeys)
        assertEquals(setOf("claude_code|b"), afterNone)
        assertTrue(allVisibleSelected(visibleKeys, afterAll))
        assertFalse(allVisibleSelected(visibleKeys, afterNone))
    }

    @Test
    fun `搜索命中标题 目录名 路径 agent 显示名与分支 忽略大小写 空词全量`() {
        val folders = listOf(
            folder(
                "/p/929",
                listOf(
                    session("t", title = "Migration Plan"),
                    session("g", branch = "feat/fleet-v1"),
                    session("x", title = "unrelated"),
                ),
            ),
        )
        // flatMap 而非 single().visibleSessions：无匹配时组被丢弃、compute 返回空列表
        fun ids(query: String) = compute(folders, query = query).flatMap { it.visibleSessions }.map { it.externalId }

        assertTrue("deploy".contains("ploy", ignoreCase = true)) // sanity of contains semantics
        assertEquals(listOf("t", "g", "x"), ids(""))          // 空词全量
        assertEquals(listOf("t"), ids("migration"))           // 标题，ignoreCase
        assertEquals(listOf("g"), ids("fleet"))               // 分支
        assertEquals(listOf("t", "g", "x"), ids("/p/929"))    // 路径全组
        assertEquals(listOf("t", "g", "x"), ids("929"))       // 目录名
        assertEquals(listOf("t", "g", "x"), ids("claude"))    // agent 显示名 "Claude Code"
        assertEquals(emptyList<String>(), ids("zcode"))       // 无 ZCode 行
        assertEquals(listOf("t", "g", "x"), ids("  "))        // 空白等价空词
    }

    @Test
    fun `搜索命中 agent 显示名`() {
        val folders = listOf(
            folder("/p/mix", listOf(session("c", agent = AgentType.CODEX), session("k", agent = AgentType.CLAUDE_CODE))),
        )
        // "odex" 只命中 "Codex CLI"，不命中 "Claude Code"
        val codexOnly = compute(folders, query = "odex")
        assertEquals(listOf("c"), codexOnly.single().visibleSessions.map { it.externalId })
    }

    @Test
    fun `默认只看可导入 - showImported 与 includeDeleted 开关恢复可见性`() {
        val sessions = listOf(
            session("n", title = "new one"),
            session("i", status = ScanSessionStatus.IMPORTED, title = "already here"),
            session("d", status = ScanSessionStatus.DELETED, title = "soft deleted"),
        )
        val folders = listOf(folder("/p/p", sessions))

        val def = compute(folders)
        assertEquals(listOf("n"), def.single().visibleSessions.map { it.externalId })

        val withImported = compute(folders, showImported = true)
        assertEquals(listOf("n", "i"), withImported.single().visibleSessions.map { it.externalId })

        val withDeleted = compute(folders, includeDeleted = true)
        assertEquals(listOf("n", "d"), withDeleted.single().visibleSessions.map { it.externalId })

        val all = compute(folders, showImported = true, includeDeleted = true)
        assertEquals(listOf("n", "i", "d"), all.single().visibleSessions.map { it.externalId })
        // includeDeleted 打开后 deleted 行进入 legalKeys（选中即恢复）
        assertEquals(setOf("claude_code|n", "claude_code|d"), all.single().legalKeys)
    }

    @Test
    fun `隐藏目录与 agent 后空组不渲染 - 全部隐藏得到空列表`() {
        val folders = listOf(
            folder("/p/929", listOf(session("a"), session("c", agent = AgentType.CODEX))),
            folder("/p/aios", listOf(session("b"))),
        )
        val hiddenFolder = compute(folders, hiddenFolders = setOf("/p/929"))
        assertEquals(listOf("b"), hiddenFolder.single().visibleSessions.map { it.externalId })

        val hiddenAgent = compute(folders, hiddenAgents = setOf(AgentType.CODEX))
        // /p/929 只剩 claude 行，组保留；agent 过滤作用于行
        assertEquals(2, hiddenAgent.size)
        assertEquals(listOf("a"), hiddenAgent.first().visibleSessions.map { it.externalId })

        val allHidden = compute(
            folders,
            hiddenFolders = setOf("/p/929", "/p/aios"),
            hiddenAgents = setOf(AgentType.CLAUDE_CODE, AgentType.CODEX),
        )
        assertTrue(allHidden.isEmpty())

        // agent 过滤把组内行清光 → 整组消失
        val emptied = compute(listOf(folder("/p/one", listOf(session("a"), session("c", agent = AgentType.CODEX)))), hiddenAgents = setOf(AgentType.CLAUDE_CODE, AgentType.CODEX))
        assertTrue(emptied.isEmpty())
    }

    @Test
    fun `folderToggleState 与 allVisibleSelected 的空集边界`() {
        assertEquals(ImportSelectState.NONE, folderToggleState(emptySet(), setOf("anything")))
        assertFalse(allVisibleSelected(emptySet(), setOf("anything")))
        assertTrue(allVisibleSelected(setOf("k"), setOf("k", "other")))
        assertFalse(allVisibleSelected(setOf("k", "k2"), setOf("k")))
    }
}
