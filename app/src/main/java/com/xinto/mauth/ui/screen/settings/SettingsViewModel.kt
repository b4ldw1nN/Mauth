package com.xinto.mauth.ui.screen.settings

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xinto.mauth.Mauth
import com.xinto.mauth.core.webserver.WebServerManager
import com.xinto.mauth.domain.AuthRepository
import com.xinto.mauth.domain.SettingsRepository
import com.xinto.mauth.service.WebServerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    application: Application,
    private val settings: SettingsRepository,
    private val authRepository: AuthRepository,
    private val webServerManager: WebServerManager,
) : AndroidViewModel(application) {

    val secureMode = settings.getSecureMode()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val pinLock = authRepository.observeIsProtected()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val biometrics = settings.getUseBiometrics()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val webServerEnabled = settings.getWebServerEnabled()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _serverUrl = MutableStateFlow("")
    val serverUrl = _serverUrl.asStateFlow()

    fun updateSecureMode(newSecureMode: Boolean) {
        viewModelScope.launch {
            settings.setSecureMode(newSecureMode)
        }
    }

    fun toggleBiometrics() {
        viewModelScope.launch {
            settings.setUseBiometrics(!biometrics.value)
        }
    }

    fun toggleWebServer(enabled: Boolean) {
        viewModelScope.launch {
            settings.setWebServerEnabled(enabled)
            val app = getApplication<Mauth>()
            if (enabled) {
                val intent = WebServerService.startIntent(app)
                app.startForegroundService(intent)
                delay(500)
                _serverUrl.value = webServerManager.getLocalAddress() ?: ""
            } else {
                app.stopService(Intent(app, WebServerService::class.java))
                _serverUrl.value = ""
            }
        }
    }
}