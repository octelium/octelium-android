package com.octelium.client.vpn

import android.content.Context
import android.net.VpnService
import com.octelium.client.core.tunnel.EstablishedTunnel
import com.octelium.client.core.tunnel.TunnelHost
import com.octelium.client.core.tunnel.TunnelSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

class TunnelManager(private val context: Context) : TunnelHost {
    private val _service = MutableStateFlow<OcteliumVpnService?>(null)
    val service: StateFlow<OcteliumVpnService?> = _service.asStateFlow()

    private val _holds = MutableStateFlow(0)
    val holds: StateFlow<Int> = _holds.asStateFlow()

    fun attach(arg: OcteliumVpnService) {
        _service.value = arg
    }

    fun detach(arg: OcteliumVpnService) {
        _service.compareAndSet(arg, null)
    }

    fun acquire() {
        _holds.update { it + 1 }
    }

    fun release() {
        _holds.update { maxOf(0, it - 1) }
    }

    override suspend fun establish(domain: String, generation: Long, spec: TunnelSpec): EstablishedTunnel =
        getService().establish(domain, spec)

    private suspend fun getService(): OcteliumVpnService {
        _service.value?.let { return it }

        if (VpnService.prepare(context) != null) {
            throw IllegalStateException("The VPN permission is not granted")
        }

        acquire()
        try {
            OcteliumVpnService.start(context)

            return withTimeoutOrNull(SERVICE_START_TIMEOUT_MS) {
                _service.filterNotNull().first()
            } ?: throw IllegalStateException("The VPN service could not be started")
        } finally {
            release()
        }
    }

    companion object {
        private const val SERVICE_START_TIMEOUT_MS = 10_000L
    }
}
