package com.octelium.client.core.tunnel

import com.octelium.client.core.local.LogEntry
import octelium.api.main.user.v1.Userv1
import java.io.Closeable

enum class TunnelState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

enum class TunnelError(val code: Int) {
    INVALID_ARGUMENT(1),
    INVALID_STATE(2),
    NOT_FOUND(3),
    UNSUPPORTED(4),
    UNAUTHENTICATED(5),
    UNAVAILABLE(6),
    PLATFORM(7),
    TRANSPORT(8),
    TIMEOUT(9),
    INTERNAL(10),
    ;

    companion object {
        fun fromCode(code: Int): TunnelError = entries.find { it.code == code } ?: INTERNAL
    }
}

class TunnelException(val error: TunnelError, message: String) : Exception(message)

enum class TunnelMode {
    WIREGUARD,
    QUICV0,
}

enum class DNSMode {
    DEFAULT,
    DISABLED,
    FULL,
}

data class TunnelPreferences(
    val tunnelMode: TunnelMode = TunnelMode.WIREGUARD,
    val dnsMode: DNSMode = DNSMode.DEFAULT,
    val mtu: Int = 0,
    val keepAliveSeconds: Int = 0,
)

data class TunnelConfig(
    val domain: String,
    val state: Userv1.ConnectionState,
    val preferences: TunnelPreferences,
)

data class TunnelStatus(
    val state: TunnelState,
    val error: TunnelError? = null,
    val message: String = "",
)

data class DNSConfig(
    val servers: List<String>,
    val searchDomains: List<String> = emptyList(),
    val matchDomains: List<String> = emptyList(),
    val matchAllDomains: Boolean = false,
)

data class NetworkConfig(
    val generation: Long,
    val addresses: List<String>,
    val routes: List<String>,
    val dns: DNSConfig?,
    val mtu: Int,
)

sealed interface TunnelRequest {
    data class ApplyNetworkConfig(val config: NetworkConfig) : TunnelRequest

    data object GetAccessToken : TunnelRequest
}

sealed interface TunnelResponse {
    data class ApplyNetworkConfig(val tunFD: Int) : TunnelResponse

    data class GetAccessToken(val accessToken: String) : TunnelResponse

    data class Error(val error: TunnelError, val message: String) : TunnelResponse
}

interface TunnelHandler {
    fun onStatus(status: TunnelStatus)

    fun onLog(log: LogEntry)

    fun onRequest(requestID: Long, request: TunnelRequest)
}

interface Tunnel : Closeable {
    fun setConfig(config: TunnelConfig)

    fun setNetworkState(isAvailable: Boolean, id: String)

    fun complete(requestID: Long, response: TunnelResponse): Int
}

fun interface TunnelFactory {
    fun create(handler: TunnelHandler): Tunnel
}
