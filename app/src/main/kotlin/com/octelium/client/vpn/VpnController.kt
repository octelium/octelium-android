package com.octelium.client.vpn

import android.content.Context
import android.net.VpnService
import android.util.Log
import com.octelium.client.core.domain.getTunnelDomainState
import com.octelium.client.core.local.LocalClient
import com.octelium.client.core.local.StatusStore
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.core.tunnel.getAlwaysOnDomain
import com.octelium.client.core.tunnel.getAutoConnectDomain
import com.octelium.client.prefs.PrefsRepository
import com.octelium.client.runtime.ClientRuntime
import octelium.api.client.daemon.v1.Daemonv1.Operation
import java.util.concurrent.atomic.AtomicBoolean

class VpnController(
    private val context: Context,
    private val runtime: ClientRuntime,
    private val statusStore: StatusStore,
    private val tunnels: TunnelManager,
    private val prefs: PrefsRepository,
) {
    private val hasAutoConnected = AtomicBoolean(false)

    fun isPermissionGranted(): Boolean = VpnService.prepare(context) == null

    suspend fun connect(domain: String): Operation {
        if (!isPermissionGranted()) {
            throw IllegalStateException("The VPN permission is not granted")
        }

        tunnels.acquire()
        try {
            OcteliumVpnService.start(context)

            val client = runtime.awaitClient()
            val ret = client.connect(domain)
            refreshStatus(client)

            return ret
        } finally {
            tunnels.release()
        }
    }

    suspend fun disconnect(domain: String): Operation {
        val client = runtime.awaitClient()
        val ret = client.disconnect(domain)
        refreshStatus(client)
        return ret
    }

    suspend fun disconnectTunnel() {
        val state = getTunnelDomainState(statusStore.status.value) ?: return
        disconnect(state.domain)
    }

    suspend fun connectAlwaysOn() {
        tunnels.acquire()
        try {
            val client = runtime.awaitClient()
            val status = client.getStatus()
            statusStore.update(status)

            if (getTunnelDomainState(status) != null) {
                return
            }

            val domain = getAlwaysOnDomain(status, prefs.get().primaryDomain)
            if (domain == null) {
                Notifications.notifyAlert(
                    context,
                    "Sign in required",
                    "Open Octelium and sign in to your Cluster in order to connect this device.",
                )
                return
            }

            client.connect(domain)
            refreshStatus(client)
        } catch (err: Exception) {
            Log.w(LOG_TAG, "Could not connect the always-on VPN: ${getErrorMessage(err)}")
            Notifications.notifyAlert(context, "Could not connect", getErrorMessage(err))
        } finally {
            tunnels.release()
        }
    }

    suspend fun autoConnect() {
        if (!isPermissionGranted() || !hasAutoConnected.compareAndSet(false, true)) {
            return
        }

        val client = runtime.awaitClient()
        val domain = getAutoConnectDomain(client.getStatus(), prefs.get().primaryDomain) ?: return

        try {
            connect(domain)
        } catch (err: Exception) {
            Log.w(LOG_TAG, "Could not auto connect $domain: ${getErrorMessage(err)}")
        }
    }

    private suspend fun refreshStatus(client: LocalClient) {
        try {
            statusStore.update(client.getStatus())
        } catch (err: Exception) {
            Log.d(LOG_TAG, "Could not refresh the status: ${getErrorMessage(err)}")
        }
    }

    companion object {
        private const val LOG_TAG = "VpnController"
    }
}
