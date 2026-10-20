package com.mynok.myagent.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mynok.myagent.data.db.ConversationEntity
import com.mynok.myagent.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val currentId: String? = null,
)

class SessionsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val repo = container.repository

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeConversations().collect { list ->
                _state.update { it.copy(conversations = list) }
            }
        }
    }

    fun select(id: String) = _state.update { it.copy(currentId = id) }

    fun delete(id: String) {
        viewModelScope.launch { repo.deleteConversation(id) }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch { repo.renameConversation(id, title) }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SessionsViewModel(container) as T
        }
    }
}
