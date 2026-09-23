package com.octelium.client.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.core.network.NetworkInfo
import com.octelium.client.core.network.NetworkState
import com.octelium.client.core.network.NetworkTransport
import com.octelium.client.core.network.getNetworkState
import com.octelium.client.runtime.ClientRuntime
import com.octelium.client.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NetworkMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val runtime: ClientRuntime,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network.asStateFlow()

    private val _info = MutableStateFlow<NetworkInfo?>(null)
    val info: StateFlow<NetworkInfo?> = _info.asStateFlow()

    private val state = MutableStateFlow<NetworkState?>(null)

    val isOffline: StateFlow<Boolean> = state
        .map { it?.isAvailable == false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return
            }

            val ret = getNetworkInfo(network, caps)
            _network.value = network
            _info.value = ret
            state.value = getNetworkState(ret)
        }

        override fun onLost(network: Network) {
            if (_network.value != network) {
                return
            }

            _network.value = null
            _info.value = null
            state.value = getNetworkState(null)
        }
    }

    fun start() {
        cm.registerDefaultNetworkCallback(callback)

        if (cm.activeNetwork == null) {
            state.compareAndSet(null, getNetworkState(null))
        }

        scope.launch {
            combine(
                state.filterNotNull().distinctUntilChanged(),
                runtime.state.filterIsInstance<RuntimeState.Ready>().map { it.client }.distinctUntilChanged(),
            ) { s, client -> s to client }.collect { (s, client) ->
                try {
                    client.setNetworkState(s.isAvailable, s.id)
                } catch (err: Exception) {
                    Log.w("NetworkMonitor", "Could not set the network state: ${getErrorMessage(err)}")
                }
            }
        }
    }

    private fun getNetworkInfo(network: Network, caps: NetworkCapabilities): NetworkInfo = NetworkInfo(
        id = network.networkHandle.toString(),
        transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            else -> NetworkTransport.OTHER
        },
        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        isCaptivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        isVPN = false,
    )
}
