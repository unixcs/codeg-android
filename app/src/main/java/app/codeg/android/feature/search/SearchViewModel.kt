package app.codeg.android.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.RecentSearchesStore
import app.codeg.android.core.model.ConversationSummary
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
    private var folderNames: Map<Int, String> = emptyMap()

    init {
        viewModelScope.launch {
            repository.selectedProfile.collectLatest { profile ->
                // Results are server-local (ids collide across devices) and this VM
                // outlives profile switches — stale rows must not survive a device
                // change, or rename/pin/delete would hit the WRONG device's session.
                _ui.update { it.copy(results = emptyList(), serverId = profile?.id) }
                client = profile?.let { repository.client(it) }
                folderNames = client?.let { c -> runCatching { c.listFolders() }.getOrNull()?.associate { it.id to it.name } } ?: emptyMap()
                _ui.update { it.copy(folderNames = folderNames) }
            }
        }
        viewModelScope.launch {
            _query.debounce(300).collectLatest { q -> runSearch(q) }
        }
    }

    fun onQueryChange(value: String) { _query.value = value }

    fun submit() {
        val q = _query.value.trim()
        if (q.isNotEmpty()) viewModelScope.launch { recentStore.add(q) }
    }

    fun useRecent(term: String) { _query.value = term }

    fun clearRecent() { viewModelScope.launch { recentStore.clear() } }

    /** Re-run the current query after a failure (drives the inline-error Retry button). */
    fun retry() { viewModelScope.launch { runSearch(_query.value) } }

    /** Optimistic pin/unpin on a search-result row; failure reverts. */
    fun setPinned(conversation: ConversationSummary, pinned: Boolean) {
        val active = client ?: return
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
            } catch (e: Exception) {
                _ui.update { it.copy(results = previous, error = e.displayMessage()) }
            }
        }
    }

    /**
     * Delete a search-result conversation. Removes the row locally first (the results
     * list only refreshes on a new debounced query), then persists; failure restores.
     */
    fun delete(conversation: ConversationSummary) {
        val active = client ?: return
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
                _ui.update { it.copy(results = previous, error = e.displayMessage()) }
            }
        }
    }

    /**
     * Rename a search-result conversation (batch v133). Awaits the server, patches
     * the row in place; failure surfaces the error (nothing was changed locally).
     */
    fun rename(conversation: ConversationSummary, title: String) {
        val active = client ?: return
        viewModelScope.launch {
            try {
                active.renameConversation(conversation.id, title)
                _ui.update { state -> state.copy(results = state.results.map {
                    if (it.id == conversation.id) it.copy(title = title) else it
                }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.displayMessage()) }
            }
        }
    }

    private suspend fun runSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {
            _ui.update { it.copy(results = emptyList(), searching = false, query = "", error = null) }
            return
        }
        val c = client ?: return
        _ui.update { it.copy(searching = true, query = q, error = null) }
        try {
            val results = c.listConversations(search = q, sortBy = "updated")
            _ui.update { it.copy(results = results, searching = false) }
            recentStore.add(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(searching = false, error = e.displayMessage()) }
        }
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<ConversationSummary> = emptyList(),
    val folderNames: Map<Int, String> = emptyMap(),
    val searching: Boolean = false,
    val error: String? = null,
    /** Owning device of [results]; screens key pending dialogs on it so a floating-ball
     *  device switch closes them before their stale server-local ids can misfire. */
    val serverId: String? = null,
)
