package com.octelium.client.core.connect

import com.octelium.client.core.tunnel.DNSMode
import com.octelium.client.core.tunnel.TunnelMode
import octelium.api.main.user.v1.Userv1

data class ConnectOptions(
    val l3Mode: Userv1.ConnectRequest.Initialize.L3Mode = Userv1.ConnectRequest.Initialize.L3Mode.V6,
    val tunnelMode: TunnelMode = TunnelMode.WIREGUARD,
    val dnsMode: DNSMode = DNSMode.DEFAULT,
    val mtu: Int = 0,
)

data class Connection(
    val state: Userv1.ConnectionState,
    val tunnelMode: TunnelMode,
    val dnsMode: DNSMode,
    val mtu: Int,
)

fun getInitializeRequest(opts: ConnectOptions): Userv1.ConnectRequest = Userv1.ConnectRequest.newBuilder()
    .setInitialize(
        Userv1.ConnectRequest.Initialize.newBuilder()
            .setL3Mode(opts.l3Mode)
            .setConnectionType(
                if (opts.tunnelMode == TunnelMode.QUICV0) {
                    Userv1.ConnectRequest.Initialize.ConnectionType.QUICV0
                } else {
                    Userv1.ConnectRequest.Initialize.ConnectionType.UNSET
                },
            )
            .setIgnoreDNS(opts.dnsMode == DNSMode.DISABLED)
    )
    .build()

fun reduceConnectionState(state: Userv1.ConnectionState, resp: Userv1.ConnectResponse): Userv1.ConnectionState? =
    when (resp.eventCase) {
        Userv1.ConnectResponse.EventCase.STATE -> resp.state

        Userv1.ConnectResponse.EventCase.ADDGATEWAY -> {
            val gw = resp.addGateway.gateway
            val idx = state.gatewaysList.indexOfFirst { it.id == gw.id }
            if (idx >= 0) {
                state.toBuilder().setGateways(idx, gw).build()
            } else {
                state.toBuilder().addGateways(gw).build()
            }
        }

        Userv1.ConnectResponse.EventCase.UPDATEGATEWAY -> {
            val gw = resp.updateGateway.gateway
            val idx = state.gatewaysList.indexOfFirst { it.id == gw.id }
            if (idx >= 0) state.toBuilder().setGateways(idx, gw).build() else null
        }

        Userv1.ConnectResponse.EventCase.DELETEGATEWAY -> {
            val idx = state.gatewaysList.indexOfFirst { it.id == resp.deleteGateway.id }
            if (idx >= 0) state.toBuilder().removeGateways(idx).build() else null
        }

        Userv1.ConnectResponse.EventCase.UPDATEDNS -> {
            if (resp.updateDNS.dns.serversCount == 0) null else state.toBuilder().setDns(resp.updateDNS.dns).build()
        }

        else -> null
    }
