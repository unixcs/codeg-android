package app.codeg.android.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.RecentSearchesStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Global conversation search: debounced server query + persisted recent terms. Port of iOS `SearchView`. */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val recentStore: RecentSearchesStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    val recent: StateFlow<List<String>> =
        recentStore.searches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var client: CodegClient? = null

    /** The profile [client] was built for. Kept so a search can re-resolve after a
     *  token edit — token changes do NOT emit on the profile flow. */
    private var currentProfile: ServerProfile? = null

    /** `(id, baseUrl)` of the device the current results belong to. Comparing the
     *  endpoint, not just the id, matters: editing a server's URL keeps its id. */
    private var serverKey: Pair<String?, String?>? = null

    /**
     * Epoch of the newest accepted search. `collectLatest` cancels the previous
     * request when a new query arrives, but cancellation only lands at a
     * suspension point: a request that already got its response can still write
     * its (now stale) rows into [_ui]. Every runSearch therefore carries the epoch
     * it started with and drops its writes once a newer search — a debounce
     * emission, a Retry tap, or a device switch — has taken over.
     */
    private var searchEpoch = 0L

    init {
        viewModelScope.launch {
            repository.selectedProfile.collectLatest { profile ->
                // Resolve BEFORE committing any state: repository.client() suspends on
                // the token read, and a cancellation mid-way (collectLatest) must not
                // leave a half-switched client or a wiped list behind.
                val resolved = profile?.let { resolveClient(it) }
                val key = profile?.let { it.id to it.baseUrl }
                // Re-emissions are routine (the repository combines two cold flows), so
                // only a REAL endpoint change may invalidate results. Ids alone are not
                // enough — editing a device's URL keeps the id, and the old server's
                // rows must not outlive the swap (pin/rename would hit the new server).
                val switched = key != serverKey
                val recovered = !switched && resolved != null && _ui.value.noDevice
                serverKey = key
                currentProfile = profile
                client = resolved
                if (switched) {
                    // Results are server-local (ids collide across devices) and this VM
                    // outlives device switches — stale rows must not survive a device
                    // change, or rename/pin/delete would hit the WRONG device's session.
                    _ui.update {
                        it.copy(
                            results = emptyList(),
                            // Folder ids are server-local too: stale labels would tag
                            // the new device's rows with the old device's names.
                            folderNames = emptyMap(),
                            serverId = profile?.id,
                            searching = false,
                            error = null,
                            noDevice = resolved == null,
                        )
                    }
                    // Answer the visible query on the device now on screen. The epoch is
                    // minted AFTER the client swap (never before a suspension), so an old
                    // device's in-flight response can no longer land after the clear.
                    startSearch(_query.value, ++searchEpoch)
                } else if (recovered) {
                    // A token was added for the same device: the profile flow carries no
                    // token edits, so nothing else would clear the flag.
                    _ui.update { it.copy(noDevice = false) }
                    startSearch(_query.value, ++searchEpoch)
                }
                // Fetch folder names AFTER starting the search: runSearch sets
                // `searching` synchronously, so the empty state can't flash during
                // this round trip.
                val names = if (resolved == null) emptyMap() else resolveFolderNames(resolved)
                _ui.update { it.copy(folderNames = names) }
            }
        }
        viewModelScope.launch {
            _query.debounce(300).collectLatest { q -> runSearch(q, ++searchEpoch) }
        }
    }

    fun onQueryChange(value: String) { _query.value = value }

    fun submit() {
        val q = _query.value.trim()
        if (q.isNotEmpty()) viewModelScope.launch { recentStore.add(q) }
    }

    fun useRecent(term: String) { _query.value = term }

    fun clearRecent() { viewModelScope.launch { recentStore.clear() } }

    /** Re-run the current query after a failure (drives the inline-error Retry button).
     *  Also recovers a missing token: [runSearch] re-resolves a null client from the
     *  selected profile, since token edits never emit on the profile flow. */
    fun retry() { viewModelScope.launch { runSearch(_query.value, ++searchEpoch) } }

    /**
     * Launch [runSearch] under [epoch] and return immediately. The caller mints the
     * epoch AFTER any suspension that could reorder it (client swap, token read) —
     * carrying an epoch minted before a suspension is how an old request used to
     * survive a newer one.
     */
    private fun startSearch(raw: String, epoch: Long) {
        viewModelScope.launch { runSearch(raw, epoch) }
    }

    /** Resolve a client for [profile]; "missing token / unusable profile" is a null
     *  client, but cancellation always propagates — a superseded collectLatest action
     *  must actually stop, not run its tail after being cancelled. */
    private suspend fun resolveClient(profile: ServerProfile): CodegClient? =
        try {
            repository.client(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun resolveFolderNames(active: CodegClient): Map<Int, String> =
        try {
            active.listFolders().associate { it.id to it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }

    /** Optimistic pin/unpin on a search-result row; failure reverts.
     *  The revert is epoch-gated: if the device changed while the RPC was out, the
     *  snapshot it captured belongs to the previous device and must not be written
     *  back over the new device's rows. */
    fun setPinned(conversation: ConversationSummary, pinned: Boolean) {
        val active = client ?: return
        val epoch = searchEpoch
        val previous = _ui.value.results
        _ui.update { state ->
            state.copy(
                results = state.results.map {
                    if (it.id == conversation.id) it.copy(pinnedAt = if (pinned) java.time.Instant.now() else null) else it
                },
            )
        }
        viewModelScope.launch {
            try {
                active.setPinned(conversation.id, pinned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == searchEpoch) {
                    _ui.update { it.copy(results = previous, error = e.displayMessage()) }
                }
            }
        }
    }

    /**
     * Delete a search-result conversation. Removes the row locally first (the results
     * list only refreshes on a new debounced query), then persists; failure restores.
     * The restore is epoch-gated (see [setPinned]).
     */
    fun delete(conversation: ConversationSummary) {
        val active = client ?: return
        val epoch = searchEpoch
        val previous = _ui.value.results
        _ui.update { state ->
            state.copy(results = state.results.filterNot { it.id == conversation.id })
        }
        viewModelScope.launch {
            try {
                active.deleteConversation(conversation.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == searchEpoch) {
                    _ui.update { it.copy(results = previous, error = e.displayMessage()) }
                }
            }
        }
    }

    /**
     * Rename a search-result conversation (batch v133). Awaits the server, patches
     * the row in place; failure surfaces the error (nothing was changed locally).
     * Both writes are epoch-gated: the row patched is the one that was on screen when
     * the edit started, not whatever device's row now carries the same id.
     */
    fun rename(conversation: ConversationSummary, title: String) {
        val active = client ?: return
        val epoch = searchEpoch
        viewModelScope.launch {
            try {
                active.renameConversation(conversation.id, title)
                if (epoch == searchEpoch) {
                    _ui.update { state -> state.copy(results = state.results.map {
                        if (it.id == conversation.id) it.copy(title = title) else it
                    }) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == searchEpoch) _ui.update { it.copy(error = e.displayMessage()) }
            }
        }
    }

    private suspend fun runSearch(raw: String, epoch: Long) {
        val q = raw.trim()
        if (q.isEmpty()) {
            if (epoch == searchEpoch) {
                _ui.update { it.copy(results = emptyList(), searching = false, query = "", error = null, noDevice = false) }
            }
            return
        }
        val c = client ?: currentProfile?.let { profile ->
            // Commit the re-resolved client only while this search is still current: a
            // device switch during the token read must not be overwritten by the
            // (now stale) device's client, or every later request would hit the wrong
            // server while the screen shows the new one.
            resolveClient(profile)?.also { fresh -> if (epoch == searchEpoch) client = fresh }
        }
        if (c == null) {
            // No device/token to query: say so instead of rendering the "nothing
            // matched" empty state, which blames the query for a missing server.
            if (epoch == searchEpoch) {
                _ui.update { it.copy(results = emptyList(), searching = false, query = q, error = null, noDevice = true) }
            }
            return
        }
        if (epoch != searchEpoch) return
        _ui.update { it.copy(searching = true, query = q, error = null, noDevice = false) }
        val results = try {
            c.listConversations(search = q, sortBy = "updated")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The client captured its bearer at construction. A rotated/revoked token
            // 401s forever until it is rebuilt, and token edits never emit on the
            // profile flow — drop the cached client so Retry re-resolves it.
            if (e is ApiError.Unauthorized && epoch == searchEpoch) client = null
            if (epoch == searchEpoch) _ui.update { it.copy(searching = false, error = e.displayMessage()) }
            return
        }
        if (epoch != searchEpoch) return // superseded while the request was in flight
        _ui.update { it.copy(results = results, searching = false) }
        // Persisting the term is best-effort: a recents write must never turn a
        // successful search into an error page.
        try {
            recentStore.add(q)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // ignored
        }
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<ConversationSummary> = emptyList(),
    val folderNames: Map<Int, String> = emptyMap(),
    val searching: Boolean = false,
    val error: String? = null,
    /** No device (or no token for it) to search: the screen shows "no server
     *  selected" rather than a "nothing matched" empty state. */
    val noDevice: Boolean = false,
    /** Owning device of [results]; screens key pending dialogs on it so a floating-ball
     *  device switch closes them before their stale server-local ids can misfire. */
    val serverId: String? = null,
)
