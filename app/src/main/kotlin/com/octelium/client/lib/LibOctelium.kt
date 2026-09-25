package com.octelium.client.lib

import com.octelium.client.core.local.LogEntry
import com.octelium.client.core.local.LogLevel
import com.octelium.client.core.tunnel.DNSConfig
import com.octelium.client.core.tunnel.DNSMode
import com.octelium.client.core.tunnel.NetworkConfig
import com.octelium.client.core.tunnel.Tunnel
import com.octelium.client.core.tunnel.TunnelConfig
import com.octelium.client.core.tunnel.TunnelError
import com.octelium.client.core.tunnel.TunnelException
import com.octelium.client.core.tunnel.TunnelHandler
import com.octelium.client.core.tunnel.TunnelMode
import com.octelium.client.core.tunnel.TunnelRequest
import com.octelium.client.core.tunnel.TunnelResponse
import com.octelium.client.core.tunnel.TunnelState
import com.octelium.client.core.tunnel.TunnelStatus
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

const val ABI_VERSION_MAJOR = 1

private const val EVENT_STATE = 1
private const val EVENT_LOG = 2

private const val REQUEST_APPLY_NETWORK_CONFIG = 1
private const val REQUEST_GET_ACCESS_TOKEN = 2

class LibraryUnavailableException(message: String) : Exception(message)

class LibOctelium private constructor(
    private val handle: Long,
    private val context: Long,
) : Tunnel {

    private val isClosed = AtomicBoolean(false)

    override fun setConfig(config: TunnelConfig) {
        if (isClosed.get()) {
            throw TunnelException(TunnelError.INVALID_STATE, "The tunnel is closed")
        }

        val cfg = getNativeConfig(config)

        try {
            val ret = Native.setConfig(handle, cfg)
            if (ret.code != 0) {
                throw TunnelException(TunnelError.fromCode(ret.code), ret.message.orEmpty())
            }
        } finally {
            cfg.x25519Key?.fill(0)
        }
    }

    override fun setNetworkState(isAvailable: Boolean, id: String) {
        if (isClosed.get()) {
            return
        }

        Native.setNetworkState(handle, isAvailable, id)
    }

    override fun complete(requestID: Long, response: TunnelResponse): Int {
        if (isClosed.get()) {
            return TunnelError.NOT_FOUND.code
        }

        val ret = when (response) {
            is TunnelResponse.ApplyNetworkConfig ->
                Native.completeRequest(handle, requestID, 0, null, response.tunFD, null)

            is TunnelResponse.GetAccessToken ->
                Native.completeRequest(handle, requestID, 0, null, -1, response.accessToken)

            is TunnelResponse.Error ->
                Native.completeRequest(handle, requestID, response.error.code, response.message, -1, null)
        }

        return ret.code
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            Native.freeTunnel(handle, context)
        }
    }

    private class Callbacks(private val handler: TunnelHandler) : NativeCallbacks {
        @Volatile
        var lib: LibOctelium? = null

        override fun onEvent(type: Int, state: Int, error: Int, logLevel: Int, createdAt: Long, message: String?) {
            when (type) {
                EVENT_STATE -> handler.onStatus(
                    TunnelStatus(
                        state = TunnelState.entries.getOrElse(state) { TunnelState.FAILED },
                        error = if (error == 0) null else TunnelError.fromCode(error),
                        message = message.orEmpty(),
                    ),
                )

                EVENT_LOG -> handler.onLog(
                    LogEntry(
                        level = LogLevel.entries.getOrElse(logLevel - 1) { LogLevel.INFO },
                        createdAt = Instant.ofEpochMilli(createdAt),
                        message = message.orEmpty(),
                    ),
                )
            }
        }

        override fun onRequest(requestID: Long, type: Int, networkConfig: NativeNetworkConfig?) {
            val req = when {
                type == REQUEST_APPLY_NETWORK_CONFIG && networkConfig != null ->
                    TunnelRequest.ApplyNetworkConfig(getNetworkConfig(networkConfig))

                type == REQUEST_GET_ACCESS_TOKEN -> TunnelRequest.GetAccessToken

                else -> null
            }

            if (req == null) {
                lib?.complete(requestID, TunnelResponse.Error(TunnelError.UNSUPPORTED, "Unsupported request: $type"))
                return
            }

            try {
                handler.onRequest(requestID, req)
            } catch (err: Exception) {
                lib?.complete(requestID, TunnelResponse.Error(TunnelError.INTERNAL, err.message.orEmpty()))
            }
        }
    }

    companion object {
        @Volatile
        private var isLoaded = false

        @Synchronized
        fun load(path: String = "liboctelium.so", loadLibraries: () -> Unit = ::loadSystemLibraries) {
            if (isLoaded) {
                return
            }

            try {
                loadLibraries()
            } catch (err: UnsatisfiedLinkError) {
                throw LibraryUnavailableException("liboctelium is not bundled with this build: ${err.message}")
            }

            Native.open(path)?.let {
                throw LibraryUnavailableException("Could not load liboctelium: $it")
            }

            val abiVersion = Native.abiVersion()
            if (abiVersion ushr 16 != ABI_VERSION_MAJOR || Native.hostABIVersion() ushr 16 != ABI_VERSION_MAJOR) {
                throw LibraryUnavailableException(
                    "liboctelium implements the C ABI version ${formatABIVersion(abiVersion)} while this application requires the version $ABI_VERSION_MAJOR",
                )
            }

            isLoaded = true
        }

        fun getVersion(): String = Native.version().orEmpty()

        fun getABIVersion(): Int = Native.abiVersion()

        fun create(handler: TunnelHandler, logLevel: LogLevel = LogLevel.INFO): LibOctelium {
            val callbacks = Callbacks(handler)

            val ret = Native.newTunnel(callbacks, logLevel.ordinal + 1)
            if (ret.code != 0) {
                throw TunnelException(TunnelError.fromCode(ret.code), ret.message.orEmpty())
            }

            return LibOctelium(ret.handle, ret.context).also { callbacks.lib = it }
        }

        private fun loadSystemLibraries() {
            System.loadLibrary("octelium")
            System.loadLibrary("octelium_jni")
        }
    }
}

fun formatABIVersion(arg: Int): String = "${arg ushr 16}.${arg and 0xffff}"

fun getNativeConfig(arg: TunnelConfig): NativeConfig {
    val state = arg.state

    return NativeConfig(
        domain = arg.domain,
        mtu = state.mtu,
        l3Mode = state.l3ModeValue,
        x25519Key = if (state.x25519Key.isEmpty) null else state.x25519Key.toByteArray(),
        addresses = state.addressesList.map { NativeDualStackNetwork(it.v4, it.v6) }.toTypedArray(),
        gateways = state.gatewaysList.map { gw ->
            NativeGateway(
                id = gw.id,
                hostname = gw.hostname,
                addresses = gw.addressesList.toTypedArray(),
                cidrs = gw.cidRsList.toTypedArray(),
                wireguard = if (gw.hasWireguard()) {
                    NativeGatewayWireGuard(gw.wireguard.publicKey, gw.wireguard.port, gw.wireguard.keepAliveSeconds)
                } else {
                    null
                },
                quicv0 = if (gw.hasQuicv0()) NativeGatewayQUICV0(gw.quicv0.port, gw.quicv0.keepAliveSeconds) else null,
            )
        }.toTypedArray(),
        dnsServers = state.dns.serversList.toTypedArray(),
        cidr = NativeDualStackNetwork(state.cidr.v4, state.cidr.v6),
        tunnelMode = when (arg.preferences.tunnelMode) {
            TunnelMode.WIREGUARD -> 0
            TunnelMode.QUICV0 -> 1
        },
        dnsMode = when (arg.preferences.dnsMode) {
            DNSMode.DEFAULT -> 0
            DNSMode.DISABLED -> 1
            DNSMode.FULL -> 2
        },
        preferredMTU = arg.preferences.mtu,
        keepAliveSeconds = arg.preferences.keepAliveSeconds,
    )
}

fun getNetworkConfig(arg: NativeNetworkConfig): NetworkConfig = NetworkConfig(
    generation = arg.generation,
    addresses = arg.addresses.mapIndexed { i, itm -> "$itm/${arg.addressPrefixLens[i]}" },
    routes = arg.routes.mapIndexed { i, itm -> "$itm/${arg.routePrefixLens[i]}" },
    dns = arg.dns?.let {
        DNSConfig(
            servers = it.servers.toList(),
            searchDomains = it.searchDomains.toList(),
            matchDomains = it.matchDomains.toList(),
            matchAllDomains = it.matchAllDomains,
        )
    },
    mtu = arg.mtu,
)
