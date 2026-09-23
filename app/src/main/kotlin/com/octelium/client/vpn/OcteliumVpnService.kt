package com.octelium.client.vpn

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.octelium.client.OcteliumApp
import com.octelium.client.core.domain.getDomainState
import com.octelium.client.core.domain.getTunnelDomainState
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.core.tunnel.EstablishedTunnel
import com.octelium.client.core.tunnel.IPFamily
import com.octelium.client.core.tunnel.TunnelSpec
import com.octelium.client.core.tunnel.isTunnelReleased
import com.octelium.client.core.tunnel.shouldKeepTunnelService
import com.octelium.client.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import octelium.api.client.daemon.v1.Daemonv1.DomainState
import java.io.IOException

class OcteliumVpnService : VpnService() {

    private val container
        get() = (application as OcteliumApp).container

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val lock = Any()
    private var tun: ParcelFileDescriptor? = null
    private var tunDomain: String? = null

    @Volatile
    private var isStopping = false

    override fun onCreate() {
        super.onCreate()

        container.tunnels.attach(this)

        scope.launch {
            combine(
                container.statusStore.status,
                container.tunnels.holds,
                container.runtime.state,
            ) { status, holds, runtime -> Triple(status, holds, runtime) }.collectLatest { (status, holds, runtime) ->
                if (isStopping) {
                    return@collectLatest
                }

                val domain = synchronized(lock) { tunDomain }
                val state = getDomainState(status, domain) ?: getTunnelDomainState(status)

                updateNotification(state)

                if (isTunnelReleased(status, domain)) {
                    closeTun()
                }

                if (runtime is RuntimeState.Loading || shouldKeepTunnelService(status, holds)) {
                    return@collectLatest
                }

                delay(STOP_DELAY_MS)
                stop()
            }
        }

        scope.launch {
            container.network.network.filterNotNull().collectLatest {
                setUnderlyingNetworks(arrayOf(it))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isStopping = false

        if (!startForeground()) {
            stop()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> {}
            ACTION_DISCONNECT -> scope.launch {
                runCatching { container.vpn.disconnectTunnel() }.onFailure {
                    Log.w(LOG_TAG, "Could not disconnect: ${getErrorMessage(it)}")
                }
            }

            else -> scope.launch {
                container.vpn.connectAlwaysOn()
            }
        }

        return START_NOT_STICKY
    }

    override fun onRevoke() {
        val domain = synchronized(lock) { tunDomain } ?: getTunnelDomainState(container.statusStore.status.value)?.domain

        closeTun()

        if (domain != null) {
            container.scope.launch {
                runCatching { container.vpn.disconnect(domain) }.onFailure {
                    Log.w(LOG_TAG, "Could not disconnect after the VPN was revoked: ${getErrorMessage(it)}")
                }
            }
        }

        super.onRevoke()
    }

    override fun onDestroy() {
        container.tunnels.detach(this)
        closeTun()
        scope.cancel()

        super.onDestroy()
    }

    fun establish(domain: String, spec: TunnelSpec): EstablishedTunnel {
        val builder = Builder()
            .setSession(domain)
            .setBlocking(true)
            .setMetered(false)
            .setConfigureIntent(Notifications.getMainActivityIntent(this))
            .addDisallowedApplication(packageName)

        if (spec.mtu > 0) {
            builder.setMtu(spec.mtu)
        }

        for (itm in spec.addresses) {
            builder.addAddress(itm.address, itm.prefixLength)
        }

        for (itm in spec.routes) {
            builder.addRoute(itm.address, itm.prefixLength)
        }

        for (itm in spec.dnsServers) {
            builder.addDnsServer(itm)
        }

        for (itm in spec.searchDomains) {
            builder.addSearchDomain(itm)
        }

        for (itm in spec.bypassFamilies) {
            builder.allowFamily(if (itm == IPFamily.V4) OsConstants.AF_INET else OsConstants.AF_INET6)
        }

        container.network.network.value?.let {
            builder.setUnderlyingNetworks(arrayOf(it))
        }

        val pfd = builder.establish() ?: throw IllegalStateException("The VPN permission is not granted")

        return object : EstablishedTunnel {
            override val fd: Int = pfd.fd

            override fun commit() {
                val old = synchronized(lock) {
                    val ret = tun
                    tun = pfd
                    tunDomain = domain
                    ret
                }

                old?.closeQuietly()
            }

            override fun abort() {
                pfd.closeQuietly()
            }
        }
    }

    private fun startForeground(): Boolean {
        val notification = Notifications.getVpnNotification(this, getTunnelDomainState(container.statusStore.status.value))

        return try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_VPN,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                } else {
                    0
                },
            )
            true
        } catch (err: Exception) {
            Log.w(LOG_TAG, "Could not start the foreground VPN service", err)
            false
        }
    }

    private fun updateNotification(state: DomainState?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            NotificationManagerCompat.from(this).notify(Notifications.ID_VPN, Notifications.getVpnNotification(this, state))
        } catch (err: SecurityException) {
            Log.d(LOG_TAG, "Could not update the VPN notification: ${err.message}")
        }
    }

    private fun closeTun() {
        val old = synchronized(lock) {
            val ret = tun
            tun = null
            tunDomain = null
            ret
        }

        old?.closeQuietly()
    }

    private fun stop() {
        isStopping = true
        closeTun()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.octelium.client.vpn.START"
        const val ACTION_DISCONNECT = "com.octelium.client.vpn.DISCONNECT"

        private const val LOG_TAG = "OcteliumVpnService"
        private const val STOP_DELAY_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, OcteliumVpnService::class.java).setAction(ACTION_START)

            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (err: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && err is ForegroundServiceStartNotAllowedException) {
                    throw IllegalStateException("The VPN service cannot be started while Octelium is in the background")
                }
                throw err
            }
        }
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    try {
        close()
    } catch (err: IOException) {
        Log.d("OcteliumVpnService", "Could not close the TUN file descriptor: ${err.message}")
    }
}
