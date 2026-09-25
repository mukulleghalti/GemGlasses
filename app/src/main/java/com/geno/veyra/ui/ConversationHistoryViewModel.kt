package com.geno.veyra.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geno.veyra.state.ArchivedSession
import com.geno.veyra.state.ConversationArchive
import com.geno.veyra.state.ConversationHit
import com.geno.veyra.state.SessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backing state for the Conversation History screen: the on-device
 * archive of past assistant sessions with search, per-session delete,
 * and clear-all.
 */
@HiltViewModel
class ConversationHistoryViewModel @Inject constructor(
    private val archive: ConversationArchive,
) : ViewModel() {

    private val _sessions =
        MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> =
        _sessions.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _hits =
        MutableStateFlow<List<ConversationHit>>(emptyList())
    val hits: StateFlow<List<ConversationHit>> = _hits.asStateFlow()

    private val _selected =
        MutableStateFlow<ArchivedSession?>(null)
    val selected: StateFlow<ArchivedSession?> = _selected.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _sessions.value = archive.listSessions(limit = 50)
        }
    }

    fun setQuery(text: String) {
        _query.value = text
        viewModelScope.launch(Dispatchers.IO) {
            _hits.value = archive.search(text)
        }
    }

    fun openSession(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _selected.value = archive.getSession(id)
        }
    }

    fun closeSession() {
        _selected.value = null
    }

    fun deleteSession(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            archive.deleteSession(id)
            if (_selected.value?.id == id) {
                _selected.value = null
            }
            _sessions.value = archive.listSessions(limit = 50)
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            archive.clearAll()
            _selected.value = null
            _sessions.value = emptyList()
            _hits.value = emptyList()
        }
    }
}
