package com.octelium.client

import android.content.Context
import com.octelium.client.auth.AuthController
import com.octelium.client.core.cluster.CLUSTER_API_PORT
import com.octelium.client.core.cluster.ChannelFactory
import com.octelium.client.core.cluster.ClusterClient
import com.octelium.client.core.cluster.getChangedSessions
import com.octelium.client.core.cluster.getClusterAPIHost
import com.octelium.client.core.cluster.getSessionKeys
import com.octelium.client.core.local.LogStore
import com.octelium.client.core.local.StatusStore
import com.octelium.client.core.network.HostResolver
import com.octelium.client.network.NetworkMonitor
import com.octelium.client.network.SystemHostResolver
import com.octelium.client.prefs.PrefsRepository
import com.octelium.client.runtime.ClientRuntime
import com.octelium.client.runtime.OcteliumRuntime
import com.octelium.client.vpn.Notifications
import com.octelium.client.vpn.TunnelManager
import com.octelium.client.vpn.VpnController
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class AppContainer(
    private val context: Context,
    getRuntime: ((AppContainer) -> ClientRuntime)? = null,
    channels: ChannelFactory? = null,
    preferences: PrefsRepository? = null,
    resolver: HostResolver? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val statusStore = StatusStore()
    val logStore = LogStore()
    val prefs = preferences ?: PrefsRepository(context)
    val tunnels = TunnelManager(context)
    val runtime: ClientRuntime = getRuntime?.invoke(this)
        ?: OcteliumRuntime(context, scope, statusStore, logStore, tunnels)
    val network = NetworkMonitor(context, scope, runtime)
    val hosts: HostResolver = resolver ?: SystemHostResolver(context)
    val auth = AuthController(
        getClient = { runtime.awaitClient() },
        getInfo = { runtime.awaitInfo() },
        statusStore = statusStore,
        hosts = hosts,
    )
    val vpn = VpnController(context, runtime, statusStore, tunnels, prefs)

    val cluster = ClusterClient(
        credentials = { runtime.awaitClient().getAPICredential(it) },
        channels = channels ?: ChannelFactory(::newClusterChannel),
    )

    fun start() {
        Notifications.createChannels(context)

        runtime.start()
        network.start()

        scope.launch {
            var previous = emptyMap<String, String>()

            statusStore.status.collect { status ->
                val current = getSessionKeys(status)
                getChangedSessions(previous, current).forEach { cluster.invalidate(it) }
                previous = current
            }
        }
    }

    private fun newClusterChannel(domain: String): ManagedChannel =
        OkHttpChannelBuilder.forAddress(getClusterAPIHost(domain), CLUSTER_API_PORT)
            .useTransportSecurity()
            .userAgent("octelium-android/${BuildConfig.VERSION_NAME}")
            .idleTimeout(5, TimeUnit.MINUTES)
            .build()
}
