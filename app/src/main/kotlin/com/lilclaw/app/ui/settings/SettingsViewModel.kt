package com.lilclaw.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lilclaw.app.service.GatewayManager
import com.lilclaw.app.service.GatewayState
import com.lilclaw.app.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val provider: String = "",
    val model: String = "",
    val apiKey: String = "",
    val gatewayState: GatewayState = GatewayState.Idle,
    val gatewayPort: Int = 3000,
    val editing: Boolean = false,   // 是否在编辑 AI 服务配置
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val gatewayManager: GatewayManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    init {
        viewModelScope.launch {
            combine(
                settings.provider,
                settings.model,
                settings.apiKey,
                settings.gatewayPort,
                gatewayManager.state,
            ) { provider, model, apiKey, port, gwState ->
                SettingsState(
                    provider = provider,
                    model = model,
                    apiKey = apiKey,
                    gatewayPort = port,
                    gatewayState = gwState,
                )
            }.collect { _state.value = it }
        }
    }

    fun setEditing(editing: Boolean) {
        _state.update { it.copy(editing = editing) }
    }

    fun onProviderChanged(provider: String) {
        _state.update { it.copy(provider = provider) }
    }
    fun onApiKeyChanged(apiKey: String) {
        _state.update { it.copy(apiKey = apiKey) }
    }
    fun onModelChanged(model: String) {
        _state.update { it.copy(model = model) }
    }

    /** 保存 AI 服务配置并退出编辑。 */
    fun saveProvider() {
        val s = _state.value
        viewModelScope.launch {
            settings.updateProvider(s.provider, s.apiKey, s.model)
            _state.update { it.copy(editing = false) }
        }
    }

    fun cancelEdit() {
        setEditing(false)
    }

    fun startGateway() {
        val s = _state.value
        gatewayManager.quickStart(
            port = s.gatewayPort,
            provider = s.provider,
            apiKey = s.apiKey,
            model = s.model,
        )
    }

    fun stopGateway() = gatewayManager.stop()

    fun restartGateway() {
        val s = _state.value
        gatewayManager.restart(s.gatewayPort, s.provider, s.apiKey, s.model)
    }
}
