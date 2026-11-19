package app.codeg.android.feature.inbox

import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.model.AgentType

/**
 * 批v193 一挂一定: pure seams for the card's one-tap "add to task board" —
 * the ledger ref whose target is replayed into [InboxViewModel.quickInsertTodo].
 * Extracted from the ViewModel so they unit-test without the 9-dependency
 * constructor (the test tree has no VM-double infrastructure).
 */

/**
 * The most recent task-mode CONFIRMED dispatch — the default target for a
 * one-tap insert. Session-mode refs (v149) point at conversations, not todos,
 * and never qualify; ordering is by send time (dispatchedAtEpochMs, falling
 * back to createdAt) rather than store order, so the pick is deterministic
 * regardless of how the ledger sorts.
 */
fun pickLatestTaskRef(refs: List<DispatchRef>): DispatchRef? = refs
    .filter { it.state == DispatchState.CONFIRMED && !it.sessionMode }
    .maxByOrNull { it.dispatchedAtEpochMs ?: it.createdAt }

/**
 * The target half of a one-tap insert, replayed from [ref] (批v166 ledger
 * snapshot: mode + model picks ride along, same contract as redispatch).
 * agentType maps through [AgentType.fromWire] and drops UNKNOWN — re-sending
 * an UNKNOWN wire would create the task on the agent's default instead of the
 * chosen one (the exact trap the redispatch path documents).
 */
data class InsertTargetPayload(
    val serverId: String,
    val folderId: Int,
    val folderName: String,
    val agentType: AgentType?,
    val modeId: String?,
    val configValues: Map<String, String>,
)

fun buildInsertPayload(ref: DispatchRef): InsertTargetPayload = InsertTargetPayload(
    serverId = ref.serverId,
    folderId = ref.folderId,
    folderName = ref.folderName,
    agentType = ref.agentTypeWire?.let { AgentType.fromWire(it) }?.takeIf { it != AgentType.UNKNOWN },
    modeId = ref.modeId,
    configValues = ref.configValues,
)

/**
 * The card-insert content gate — mirrors the editor's InsertTodoSection rule
 * (InboxScreen contentReady): a prompt must exist (description or attachments)
 * AND a title that survives (given, or derivable from the description);
 * otherwise the server's validate_draft would 422 the request.
 */
fun insertContentReady(idea: IdeaEntry): Boolean =
    (idea.description.isNotBlank() || idea.attachments.isNotEmpty()) &&
        (idea.title.isNotBlank() || idea.description.isNotBlank())
