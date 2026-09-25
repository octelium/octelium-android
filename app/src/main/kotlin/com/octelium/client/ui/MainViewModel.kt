package com.octelium.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octelium.client.AppContainer
import com.octelium.client.OcteliumApp
import com.octelium.client.core.cluster.ClusterClient
import com.octelium.client.core.cluster.getClusterAPIHost
import com.octelium.client.core.domain.resolveSelectedDomain
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.core.network.HostCheck
import com.octelium.client.core.prefs.Prefs
import com.octelium.client.core.prefs.ThemeMode
import com.octelium.client.runtime.RuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import octelium.api.client.daemon.v1.Daemonv1

class MainViewModel(private val container: AppContainer) : ViewModel() {

    val runtimeState = container.runtime.state
    val status = container.statusStore.status
    val logs = container.logStore.logs
    val networkInfo = container.network.info
    val isOffline = container.network.isOffline

    val cluster: ClusterClient
        get() = container.cluster

    val prefs: StateFlow<Prefs?> = container.prefs.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val hasRequestedNotifications: StateFlow<Boolean?> =
        container.prefs.hasRequestedNotifications.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _selectedDomain = MutableStateFlow<String?>(null)
    val selectedDomain: StateFlow<String?> = _selectedDomain.asStateFlow()

    private val _authCallbackError = MutableStateFlow<String?>(null)
    val authCallbackError: StateFlow<String?> = _authCallbackError.asStateFlow()

    private val _isCompletingAuthentication = MutableStateFlow(false)
    val isCompletingAuthentication: StateFlow<Boolean> = _isCompletingAuthentication.asStateFlow()

    init {
        viewModelScope.launch {
            combine(prefs.filterNotNull(), status) { p, s -> p to s }.collect { (p, s) ->
                val next = resolveSelectedDomain(p.primaryDomain, s, _selectedDomain.value)
                if (next == _selectedDomain.value) {
                    return@collect
                }

                _selectedDomain.value = next
                if (p.primaryDomain == null && next != null) {
                    container.prefs.setPrimaryDomain(next)
                }
            }
        }

        viewModelScope.launch {
            container.runtime.state.filterIsInstance<RuntimeState.Ready>().first()
            container.vpn.autoConnect()
        }
    }

    fun selectDomain(domain: String?) {
        _selectedDomain.value = domain
        viewModelScope.launch {
            container.prefs.setPrimaryDomain(domain)
        }
    }

    fun setTheme(arg: ThemeMode) {
        viewModelScope.launch {
            container.prefs.setTheme(arg)
        }
    }

    fun setMultiCluster(arg: Boolean) {
        viewModelScope.launch {
            container.prefs.setMultiCluster(arg)
        }
    }

    fun setHasRequestedNotifications() {
        viewModelScope.launch {
            container.prefs.setHasRequestedNotifications()
        }
    }

    fun retryStart() {
        container.runtime.start()
    }

    fun resetState() {
        _selectedDomain.value = null
        viewModelScope.launch {
            container.prefs.setPrimaryDomain(null)
        }
        container.cluster.close()
        container.runtime.reset()
    }

    suspend fun authenticateBrowser(domain: String): Daemonv1.Operation {
        val ret = container.auth.authenticateBrowser(domain)
        selectDomain(ret.domain.ifEmpty { domain })
        return ret
    }

    suspend fun authenticateToken(domain: String, authenticationToken: String): Daemonv1.Operation {
        val ret = container.auth.authenticateToken(domain, authenticationToken)
        selectDomain(ret.domain.ifEmpty { domain })
        return ret
    }

    suspend fun checkClusterAPIHost(domain: String): HostCheck = container.hosts.check(getClusterAPIHost(domain))

    fun handleAuthCallback(url: String) {
        viewModelScope.launch {
            _authCallbackError.value = null

            if (!container.auth.isCallbackURL(url)) {
                return@launch
            }

            _isCompletingAuthentication.value = true
            try {
                container.auth.completeAuthentication(url)
            } catch (err: Exception) {
                _authCallbackError.value = getErrorMessage(err)
            } finally {
                _isCompletingAuthentication.value = false
            }
        }
    }

    fun dismissAuthCallbackError() {
        _authCallbackError.value = null
    }

    suspend fun connect(domain: String): Daemonv1.Operation = container.vpn.connect(domain)

    suspend fun disconnect(domain: String): Daemonv1.Operation = container.vpn.disconnect(domain)

    suspend fun logout(domain: String): Daemonv1.Operation {
        val client = container.runtime.awaitClient()
        val ret = client.logout(domain)
        container.cluster.invalidate(domain)
        container.statusStore.update(client.getStatus())
        return ret
    }

    suspend fun deleteDomain(domain: String): Daemonv1.Operation {
        val client = container.runtime.awaitClient()
        val ret = client.deleteDomain(domain)
        container.cluster.invalidate(domain)

        if (prefs.value?.primaryDomain == domain) {
            selectDomain(null)
        }

        container.statusStore.update(client.getStatus())
        return ret
    }

    suspend fun cancelOperation(id: String): Daemonv1.Operation {
        val client = container.runtime.awaitClient()
        val ret = client.cancelOperation(id)
        container.statusStore.update(client.getStatus())
        return ret
    }

    suspend fun updateDomainSettings(domain: String, settings: Daemonv1.DomainSettings): Daemonv1.DomainSettings {
        val client = container.runtime.awaitClient()
        val ret = client.updateDomainSettings(domain, settings)
        container.statusStore.update(client.getStatus())
        return ret
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel((this[APPLICATION_KEY] as OcteliumApp).container)
            }
        }
    }
}
