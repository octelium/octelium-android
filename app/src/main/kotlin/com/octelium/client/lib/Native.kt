package com.octelium.client.lib

import androidx.annotation.Keep

@Keep
interface NativeCallbacks {
    fun onEvent(type: Int, state: Int, error: Int, logLevel: Int, createdAt: Long, message: String?)

    fun onRequest(requestID: Long, type: Int, networkConfig: NativeNetworkConfig?)
}

@Keep
class NativeResult(
    val code: Int,
    val handle: Long,
    val context: Long,
    val message: String?,
)

@Keep
class NativeDualStackNetwork(
    @JvmField val v4: String,
    @JvmField val v6: String,
)

@Keep
class NativeGatewayWireGuard(
    @JvmField val publicKey: String,
    @JvmField val port: Int,
    @JvmField val keepAliveSeconds: Int,
)

@Keep
class NativeGatewayQUICV0(
    @JvmField val port: Int,
    @JvmField val keepAliveSeconds: Int,
)

@Keep
class NativeGateway(
    @JvmField val id: String,
    @JvmField val hostname: String,
    @JvmField val addresses: Array<String>,
    @JvmField val cidrs: Array<String>,
    @JvmField val wireguard: NativeGatewayWireGuard?,
    @JvmField val quicv0: NativeGatewayQUICV0?,
)

@Keep
class NativeConfig(
    @JvmField val domain: String,
    @JvmField val mtu: Int,
    @JvmField val l3Mode: Int,
    @JvmField val x25519Key: ByteArray?,
    @JvmField val addresses: Array<NativeDualStackNetwork>,
    @JvmField val gateways: Array<NativeGateway>,
    @JvmField val dnsServers: Array<String>,
    @JvmField val cidr: NativeDualStackNetwork,
    @JvmField val tunnelMode: Int,
    @JvmField val dnsMode: Int,
    @JvmField val preferredMTU: Int,
    @JvmField val keepAliveSeconds: Int,
)

@Keep
class NativeDNSConfig(
    @JvmField val servers: Array<String>,
    @JvmField val searchDomains: Array<String>,
    @JvmField val matchDomains: Array<String>,
    @JvmField val matchAllDomains: Boolean,
)

@Keep
class NativeNetworkConfig(
    @JvmField val generation: Long,
    @JvmField val addresses: Array<String>,
    @JvmField val addressPrefixLens: IntArray,
    @JvmField val routes: Array<String>,
    @JvmField val routePrefixLens: IntArray,
    @JvmField val dns: NativeDNSConfig?,
    @JvmField val mtu: Int,
)

@Keep
object Native {
    @JvmStatic
    external fun open(path: String): String?

    @JvmStatic
    external fun abiVersion(): Int

    @JvmStatic
    external fun hostABIVersion(): Int

    @JvmStatic
    external fun version(): String?

    @JvmStatic
    external fun newTunnel(callbacks: NativeCallbacks, logLevel: Int): NativeResult

    @JvmStatic
    external fun setConfig(tunnel: Long, config: NativeConfig): NativeResult

    @JvmStatic
    external fun setNetworkState(tunnel: Long, isAvailable: Boolean, id: String): NativeResult

    @JvmStatic
    external fun completeRequest(
        tunnel: Long,
        requestID: Long,
        result: Int,
        message: String?,
        tunFD: Int,
        accessToken: String?,
    ): NativeResult

    @JvmStatic
    external fun freeTunnel(tunnel: Long, context: Long)
}
