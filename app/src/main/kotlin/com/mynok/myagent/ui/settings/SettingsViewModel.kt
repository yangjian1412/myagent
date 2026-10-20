package com.mynok.myagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mynok.myagent.data.repo.AppSettings
import com.mynok.myagent.data.repo.SettingsStore
import com.mynok.myagent.data.repo.ThemeColor
import com.mynok.myagent.data.repo.ThemeMode
import com.mynok.myagent.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** Agent 名字 / 偏好是逐字输入的，攒一小段再落盘，避免每个按键都写一次 DataStore。 */
    private var agentSaveJob: Job? = null

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

    fun setAgentName(name: String) {
        val value = name.take(SettingsStore.MAX_AGENT_NAME_LEN)
        _state.update { it.copy(settings = it.settings.copy(agentName = value)) }
        scheduleAgentSave()
    }

    fun setAgentPreferences(text: String) {
        val value = text.take(SettingsStore.MAX_AGENT_PREFERENCES_LEN)
        _state.update { it.copy(settings = it.settings.copy(agentPreferences = value)) }
        scheduleAgentSave()
    }

    private fun scheduleAgentSave() {
        agentSaveJob?.cancel()
        agentSaveJob = viewModelScope.launch {
            delay(350)
            val s = _state.value.settings
            store.setAgentName(s.agentName)
            store.setAgentPreferences(s.agentPreferences)
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(container) as T
        }
    }
}
