package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.feature.sessiondetail.rendering.ToolDerive

/**
 * 批v194 M3: derive the files a session produced from its persisted transcript —
 * zero storage, zero server involvement (the MindFS card's data path is a server
 * dual-channel; here the transcript already carries the truth, so the card is a
 * pure function of the turns the screen already holds).
 *
 * A file counts as an artifact only when its write/edit tool call RESOLVED with a
 * non-error result (a running or failed call is not yet a product). Paths come
 * from the tool input (same keys [ToolDerive] uses) or from `*** … File:` headers
 * of an apply_patch body; relative paths resolve against the session working dir.
 * The same path written twice collapses to the latest state, ordered most-recent
 * first — the chip row's "find the doc the agent just made" need.
 */
enum class ArtifactOp { ADDED, MODIFIED, DELETED }

data class SessionArtifact(
    val path: String,
    val name: String,
    val dirTail: String,
    val ext: String,
    val toolLabel: String,
    val op: ArtifactOp,
    val isDocLike: Boolean,
    /** Monotonic last-seen counter; the display list sorts this descending. */
    val lastSeen: Int,
)

object SessionArtifacts {

    /** One resolved write call awaiting its (non-error) result; ops are PER FILE. */
    private data class Call(val writes: List<Pair<String, ArtifactOp>>, val label: String)

    private const val PATH_KEYS = "file_path,path,filename,file,target_file"
    private val docExts = setOf("md", "markdown", "txt", "html", "htm")

    fun derive(turns: List<MessageTurn>, workingDir: String?): List<SessionArtifact> {
        val pending = HashMap<String, Call>()
        val byPath = LinkedHashMap<String, SessionArtifact>()
        var seen = 0

        for (turn in turns) {
            for (block in turn.blocks) {
                when (block) {
                    is ContentBlock.ToolUse -> {
                        val call = readCall(block.name, block.inputPreview, workingDir) ?: continue
                        val id = block.id ?: continue
                        pending[id] = call
                    }
                    is ContentBlock.ToolResult -> {
                        val id = block.id ?: continue
                        if (block.isError) {
                            pending.remove(id)
                            continue
                        }
                        val call = pending.remove(id) ?: continue
                        for ((path, op) in call.writes) {
                            seen += 1
                            byPath[path] = SessionArtifact(
                                path = path,
                                name = path.substringAfterLast('/'),
                                dirTail = if (path.contains('/')) path.substringBeforeLast('/').substringAfterLast('/') else "",
                                ext = path.substringAfterLast('.', "").lowercase(),
                                toolLabel = call.label,
                                op = op,
                                isDocLike = path.substringAfterLast('.', "").lowercase() in docExts,
                                lastSeen = seen,
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        return byPath.values.sortedByDescending { it.lastSeen }
    }

    /** The single write-path extraction for one tool call; null when the tool wrote nothing we can name. */
    private fun readCall(name: String, inputPreview: String?, workingDir: String?): Call? {
        val n = canonical(name)
        val parsed = ToolDerive.parseJson(inputPreview)
        val args = ToolDerive.effectiveArgs(parsed)

        if (n == "apply_patch" || n == "applypatch" || n == "patch") {
            val body = args.string("patch") ?: args.string("input")
            if (body == null) return null
            val paths = mutableListOf<Pair<String, ArtifactOp>>()
            for (line in body.split("\n")) {
                val t = line.trim()
                val idx = t.indexOf(':')
                if (idx <= 0) continue
                val head = t.substring(0, idx).trim()
                val rest = t.substring(idx + 1).trim()
                if (rest.isEmpty()) continue
                val op = when {
                    head == "*** Add File" -> ArtifactOp.ADDED
                    head == "*** Update File" -> ArtifactOp.MODIFIED
                    head == "*** Delete File" -> ArtifactOp.DELETED
                    else -> continue
                }
                paths.add(rest to op)
            }
            if (paths.isEmpty()) return null
            val resolved = paths.map { resolve(it.first, workingDir) to it.second }
            val label = if (resolved.size > 1) "Patch (${resolved.size})" else "Patch"
            return Call(resolved, label)
        }

        val isWrite = n in writeNames
        val isEdit = n in editNames
        if (!isWrite && !isEdit) return null
        val op = if (isWrite) ArtifactOp.ADDED else ArtifactOp.MODIFIED
        // Multi-file edit tools (multiedit-style) carry one path PER entry in a
        // changes[]/edits[] array (mirror ToolDerive.editFileCount). Entries
        // without a path fall through to the top-level key, never drop the call.
        for (arrKey in listOf("changes", "edits")) {
            val arr = args?.get(arrKey) as? kotlinx.serialization.json.JsonArray ?: continue
            val writes = arr.mapNotNull { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                PATH_KEYS.split(',').firstNotNullOfOrNull { k -> obj.string(k)?.trim()?.takeUnless { it.isEmpty() } }
                    ?.let { resolve(it, workingDir) to op }
            }.distinctBy { it.first }
            if (writes.isNotEmpty()) return Call(writes, labelOf(n))
        }
        for (key in PATH_KEYS.split(',')) {
            val raw = args.string(key)?.trim().takeUnless { it.isNullOrEmpty() } ?: continue
            return Call(listOf(resolve(raw, workingDir) to op), labelOf(n))
        }
        return null
    }

    private val writeNames = setOf(
        "write", "write_file", "writefile", "create_file", "notebookedit", "notebook_edit", "write_to_file",
    )
    private val editNames = setOf(
        "edit", "str_replace", "str_replace_editor", "str_replace_based_edit_tool",
        "multiedit", "edit_file", "update_file", "replace_in_file",
    )

    private fun labelOf(n: String): String = when (n) {
        in writeNames -> "Write"
        else -> "Edit"
    }

    /** Mirror ToolDerive's canonicalization: lowercase, strip the `mcp__x__` prefix. */
    private fun canonical(name: String): String {
        var n = name.lowercase()
        val idx = n.lastIndexOf("__")
        if (idx >= 0) n = n.substring(idx + 2)
        return n
    }

    private fun resolve(path: String, workingDir: String?): String {
        if (path.startsWith("/")) return path
        val wd = workingDir?.trim()?.trimEnd('/') ?: return path
        if (wd.isEmpty()) return path
        return "$wd/${path.removePrefix("./")}"
    }
}

/** Read a string field from a (possibly null) JsonObject; null if absent/non-string. */
private fun kotlinx.serialization.json.JsonObject?.string(key: String): String? {
    val prim = this?.get(key) as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return if (prim.isString) prim.content else null
}
