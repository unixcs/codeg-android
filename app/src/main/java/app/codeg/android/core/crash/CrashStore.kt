package app.codeg.android.core.crash

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One persisted crash report, shown on the About page until dismissed. */
@Serializable
data class CrashRecord(
    val time: Long,
    val version: String,
    val thread: String,
    val stack: String,
)

/**
 * DataStore side of crash capture: promotes the newest crash file written by
 * [CrashReporter] into a single DataStore key at launch, and exposes it for the
 * About page. Files are drained only after a successful DataStore write, so a
 * crash between the two simply re-promotes next launch.
 */
@Singleton
class CrashStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val lastCrash: Flow<CrashRecord?> = dataStore.data.map { prefs -> decode(prefs[KEY]) }

    /**
     * Launch-time promotion: read the newest crash file under [dir] (created by
     * the uncaught handler last run), store it, then delete the file. Safe to
     * call every launch — no file is a no-op.
     */
    suspend fun promoteFromFile(dir: File) {
        val content = withContext(Dispatchers.IO) { CrashReporter.drainNewest(dir) } ?: return
        val record = parseCrashFile(content) ?: return
        store(record)
    }

    suspend fun store(record: CrashRecord) {
        dataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(CrashRecord.serializer(), record)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    internal fun decode(raw: String?): CrashRecord? =
        raw?.let { runCatching { json.decodeFromString(CrashRecord.serializer(), it) }.getOrNull() }

    internal companion object {
        val KEY = stringPreferencesKey("last_crash")

        /**
         * Parse the plain-text crash file written by [CrashReporter.writeCrashFile]
         * back into a [CrashRecord]. Internal + pure for tests.
         */
        internal fun parseCrashFile(content: String): CrashRecord? {
            var time: Long? = null
            var version = ""
            var thread = ""
            val stack = StringBuilder()
            for (line in content.lineSequence()) {
                when {
                    line.startsWith("time: ") && time == null ->
                        time = line.removePrefix("time: ").trim().toLongOrNull()
                    line.startsWith("version: ") && version.isEmpty() ->
                        version = line.removePrefix("version: ").trim()
                    line.startsWith("thread: ") && thread.isEmpty() ->
                        thread = line.removePrefix("thread: ").trim()
                    // Everything from "stack:" on is verbatim trace (it may itself
                    // contain lines that look like headers).
                    stack.isNotEmpty() || line == "stack:" ->
                        if (line == "stack:" && stack.isEmpty()) stack.appendLine() else stack.append(line).append('\n')
                }
            }
            time ?: return null
            return CrashRecord(
                time = time,
                version = version,
                thread = thread,
                stack = stack.toString().trimEnd('\n'),
            )
        }
    }
}
