package com.octelium.client.core.domain

import octelium.api.client.daemon.v1.Daemonv1.ConnectionOptions
import octelium.api.client.daemon.v1.Daemonv1.DomainSettings

const val MIN_MTU = 576
const val MAX_MTU = 1500

val TUNNEL_MODES = listOf(
    ConnectionOptions.TunnelMode.TUNNEL_MODE_UNSPECIFIED,
    ConnectionOptions.TunnelMode.WIREGUARD,
    ConnectionOptions.TunnelMode.QUICV0,
)

val L3_MODES = listOf(
    ConnectionOptions.L3Mode.L3_MODE_UNSPECIFIED,
    ConnectionOptions.L3Mode.BOTH,
    ConnectionOptions.L3Mode.V4,
    ConnectionOptions.L3Mode.V6,
)

val DNS_MODES = listOf(
    ConnectionOptions.DNS.Mode.DEFAULT,
    ConnectionOptions.DNS.Mode.FULL,
    ConnectionOptions.DNS.Mode.DISABLED,
)

fun getDNSModeOptionLabel(arg: ConnectionOptions.DNS.Mode): String = when (arg) {
    ConnectionOptions.DNS.Mode.FULL -> "Full DNS"
    ConnectionOptions.DNS.Mode.DISABLED -> "Disabled"
    else -> "Split DNS"
}

data class DomainSettingsForm(
    val autoConnect: Boolean = false,
    val tunnelMode: ConnectionOptions.TunnelMode = ConnectionOptions.TunnelMode.TUNNEL_MODE_UNSPECIFIED,
    val dnsMode: ConnectionOptions.DNS.Mode = ConnectionOptions.DNS.Mode.DEFAULT,
    val l3Mode: ConnectionOptions.L3Mode = ConnectionOptions.L3Mode.L3_MODE_UNSPECIFIED,
    val mtu: String = "",
)

fun getDomainSettingsForm(settings: DomainSettings?): DomainSettingsForm {
    if (settings == null) {
        return DomainSettingsForm()
    }

    val options = settings.connectionOptions

    return DomainSettingsForm(
        autoConnect = settings.autoConnect,
        tunnelMode = options.tunnelMode.takeIf { it in TUNNEL_MODES }
            ?: ConnectionOptions.TunnelMode.TUNNEL_MODE_UNSPECIFIED,
        dnsMode = options.dns.mode.takeIf { it in DNS_MODES } ?: ConnectionOptions.DNS.Mode.DEFAULT,
        l3Mode = options.l3Mode.takeIf { it in L3_MODES } ?: ConnectionOptions.L3Mode.L3_MODE_UNSPECIFIED,
        mtu = if (options.mtu > 0) options.mtu.toString() else "",
    )
}

fun validateDomainSettingsForm(form: DomainSettingsForm): String? {
    val mtu = form.mtu.trim()
    if (mtu.isEmpty()) {
        return null
    }

    val value = mtu.toIntOrNull()
    if (value == null || value < MIN_MTU || value > MAX_MTU) {
        return "The MTU must be between $MIN_MTU and $MAX_MTU"
    }

    return null
}

fun toDomainSettings(domain: String, form: DomainSettingsForm): DomainSettings =
    DomainSettings.newBuilder()
        .setDomain(domain)
        .setAutoConnect(form.autoConnect)
        .setConnectionOptions(
            ConnectionOptions.newBuilder()
                .setTunnelMode(form.tunnelMode)
                .setL3Mode(form.l3Mode)
                .setDns(ConnectionOptions.DNS.newBuilder().setMode(form.dnsMode))
                .setMtu(form.mtu.trim().toIntOrNull() ?: 0)
        )
        .build()
