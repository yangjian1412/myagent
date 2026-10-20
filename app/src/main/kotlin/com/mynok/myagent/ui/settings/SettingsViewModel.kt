package com.mynok.myagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mynok.myagent.data.repo.AppSettings
import com.mynok.myagent.data.repo.ThemeColor
import com.mynok.myagent.data.repo.ThemeMode
import com.mynok.myagent.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
)

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val store = container.settingsStore

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { store.setThemeMode(mode) }
    }

    fun setThemeColor(color: ThemeColor) {
        viewModelScope.launch { store.setThemeColor(color) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { store.setDynamicColor(enabled) }
    }

    fun setAutoTitle(enabled: Boolean) {
        viewModelScope.launch { store.setAutoTitle(enabled) }
    }

    fun setHideTabLabels(enabled: Boolean) {
        viewModelScope.launch { store.setHideTabLabels(enabled) }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(container) as T
        }
    }
}
