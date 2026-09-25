package com.octelium.client.core.client

import com.octelium.client.core.connect.ConnectOptions
import com.octelium.client.core.connect.Connection
import com.octelium.client.core.tunnel.DNSMode
import com.octelium.client.core.tunnel.TunnelMode
import io.grpc.Status
import io.grpc.StatusException
import octelium.api.client.daemon.v1.Daemonv1.ConnectionOptions
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import octelium.api.main.user.v1.Userv1
import java.net.IDN
import java.util.Locale

const val MIN_CONNECTION_MTU = 576
const val MAX_CONNECTION_MTU = 1500

private val rgxDNSName = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
private val rgxIPv4 = Regex("^[0-9.]+$")

fun invalidArgument(message: String): StatusException = Status.INVALID_ARGUMENT.withDescription(message).asException()

fun getConnectOptions(arg: ConnectionOptions?): ConnectOptions {
    val o = arg ?: ConnectionOptions.getDefaultInstance()

    validateConnectionOptions(o)

    return ConnectOptions(
        l3Mode = when (o.l3Mode) {
            ConnectionOptions.L3Mode.V4 -> Userv1.ConnectRequest.Initialize.L3Mode.V4
            ConnectionOptions.L3Mode.BOTH -> Userv1.ConnectRequest.Initialize.L3Mode.BOTH
            else -> Userv1.ConnectRequest.Initialize.L3Mode.V6
        },
        tunnelMode = if (o.tunnelMode == ConnectionOptions.TunnelMode.QUICV0) TunnelMode.QUICV0 else TunnelMode.WIREGUARD,
        dnsMode = when (o.dns.mode) {
            ConnectionOptions.DNS.Mode.DISABLED -> DNSMode.DISABLED
            ConnectionOptions.DNS.Mode.FULL -> DNSMode.FULL
            else -> DNSMode.DEFAULT
        },
        mtu = o.mtu,
    )
}

fun validateConnectionOptions(o: ConnectionOptions) {
    if (o.l3Mode == ConnectionOptions.L3Mode.UNRECOGNIZED) {
        throw invalidArgument("Unsupported l3Mode: ${o.l3ModeValue}")
    }

    if (o.tunnelMode == ConnectionOptions.TunnelMode.UNRECOGNIZED) {
        throw invalidArgument("Unsupported tunnelMode: ${o.tunnelModeValue}")
    }

    when (o.implementationMode) {
        ConnectionOptions.ImplementationMode.IMPLEMENTATION_MODE_UNSPECIFIED,
        ConnectionOptions.ImplementationMode.TUN -> {}

        else -> throw invalidArgument(
            "Unsupported implementationMode on this platform: ${o.implementationModeValue}",
        )
    }

    if (o.hasDns()) {
        if (o.dns.mode == ConnectionOptions.DNS.Mode.UNRECOGNIZED) {
            throw invalidArgument("Unsupported DNS mode: ${o.dns.modeValue}")
        }

        if (o.dns.enableLocalServer || o.dns.localServerListenAddress.isNotEmpty()) {
            throw invalidArgument("The local DNS server is not supported on this platform")
        }
    }

    val svcOpts = o.serviceOptions
    if (svcOpts.serveAll || svcOpts.serveCount > 0 || svcOpts.publishCount > 0 ||
        svcOpts.enableEmbeddedSSH || svcOpts.enableEmbeddedSOCKS5
    ) {
        throw invalidArgument("Serving and publishing Services are not supported on this platform")
    }

    if (o.mtu != 0 && (o.mtu < MIN_CONNECTION_MTU || o.mtu > MAX_CONNECTION_MTU)) {
        throw invalidArgument("The MTU must be between $MIN_CONNECTION_MTU and $MAX_CONNECTION_MTU")
    }
}

fun normalizeConnectionOptions(o: ConnectionOptions?): ConnectionOptions {
    val ret = (o ?: ConnectionOptions.getDefaultInstance()).toBuilder()

    if (ret.dns.mode == ConnectionOptions.DNS.Mode.MODE_UNSPECIFIED) {
        ret.setDns(ret.dns.toBuilder().setMode(ConnectionOptions.DNS.Mode.DEFAULT))
    }

    return ret.build()
}

fun setConnectionStatusFromConnection(st: ConnectionStatus.Builder, conn: Connection?) {
    if (conn == null) {
        return
    }

    val dnsServers = conn.state.dns.serversList

    st.setMtu(conn.mtu)
        .addAllAddresses(conn.state.addressesList)
        .setImplementationMode(ConnectionOptions.ImplementationMode.TUN)
        .setTunnelMode(
            if (conn.tunnelMode == TunnelMode.QUICV0) {
                ConnectionOptions.TunnelMode.QUICV0
            } else {
                ConnectionOptions.TunnelMode.WIREGUARD
            },
        )
        .setDns(
            ConnectionStatus.DNS.newBuilder()
                .setMode(
                    when (conn.dnsMode) {
                        DNSMode.DISABLED -> ConnectionOptions.DNS.Mode.DISABLED
                        DNSMode.FULL -> ConnectionOptions.DNS.Mode.FULL
                        DNSMode.DEFAULT -> ConnectionOptions.DNS.Mode.DEFAULT
                    },
                )
                .setIsConfigured(conn.dnsMode != DNSMode.DISABLED && dnsServers.isNotEmpty())
                .addAllServers(dnsServers)
        )
}

fun canonicalizeDomain(arg: String): String {
    val domain = arg.trim()
    if (domain.isEmpty()) {
        throw invalidArgument("The Cluster domain is not set")
    }

    val invalidErr = invalidArgument("Invalid Cluster domain: $arg")

    val ret = try {
        IDN.toASCII(domain.removeSuffix("."), IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    } catch (err: IllegalArgumentException) {
        throw invalidErr
    }

    if (ret.length > 253 || !rgxDNSName.matches(ret) || rgxIPv4.matches(ret) ||
        ret.split(".").any { it.length > 63 }
    ) {
        throw invalidErr
    }

    return ret
}
