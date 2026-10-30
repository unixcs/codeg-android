package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.FolderDetail
import java.time.Instant

/**
 * Draft-folder precedence for a new task (batch v134).
 *
 * 1. [folderIdArg] — an explicit preselection (e.g. the worktree a branch switch
 *    opened). Matched against [allFolders], worktrees included, because that is
 *    exactly what the caller handed us.
 * 2. [pinnedFolderId] — the Settings pin.
 * 3. [lastUsedFolderId] — the folder of the last draft the user sent or picked.
 * 4. The most recently opened top-level folder, then anything at all.
 *
 * The two remembered ids are validated ONE AT A TIME against [topLevel] (the
 * picker-visible set). Comparing `pinned ?: lastUsed` as a single id would let a
 * stale pin (folder closed since it was pinned) swallow a perfectly valid
 * last-used value and fall through to "most recent" instead.
 */
internal fun resolveDraftFolder(
    allFolders: List<FolderDetail>,
    topLevel: List<FolderDetail>,
    folderIdArg: Int?,
    pinnedFolderId: Int?,
    lastUsedFolderId: Int?,
): FolderDetail? {
    if (folderIdArg != null) {
        allFolders.firstOrNull { it.id == folderIdArg }?.let { return it }
    }
    for (memoryId in listOfNotNull(pinnedFolderId, lastUsedFolderId)) {
        topLevel.firstOrNull { it.id == memoryId }?.let { return it }
    }
    return topLevel.firstOrNull()
        ?: allFolders.maxByOrNull { it.lastOpenedAt ?: Instant.MIN }
        ?: allFolders.firstOrNull()
}
