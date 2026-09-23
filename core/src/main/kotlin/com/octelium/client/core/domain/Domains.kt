package com.octelium.client.core.domain

import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.daemon.v1.Daemonv1.AuthenticationStatus
import octelium.api.client.daemon.v1.Daemonv1.ConnectionOptions
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import octelium.api.client.daemon.v1.Daemonv1.DomainState
import octelium.api.client.daemon.v1.Daemonv1.GetStatusResponse
import octelium.api.client.daemon.v1.Daemonv1.Operation
import java.net.IDN
import java.util.Locale

enum class ConnectivityTone {
    CONNECTED,
    PENDING,
    IDLE,
    ERROR,
}

enum class LabelTone {
    NEUTRAL,
    SLATE,
    EMERALD,
    SKY,
    AMBER,
    ROSE,
}

fun isOperationActive(arg: Operation?): Boolean = when (arg?.state) {
    Operation.State.PENDING,
    Operation.State.RUNNING,
    Operation.State.WAITING_FOR_USER -> true

    else -> false
}

fun getActiveOperation(arg: DomainState?): Operation? {
    if (arg == null || !arg.hasLastOperation()) {
        return null
    }

    return arg.lastOperation.takeIf { isOperationActive(it) }
}

fun getPendingOpenURL(arg: DomainState?): String? {
    val op = getActiveOperation(arg) ?: return null
    if (op.state != Operation.State.WAITING_FOR_USER) {
        return null
    }

    if (!op.hasAction() || !op.action.hasOpenURL()) {
        return null
    }

    return op.action.openURL.url.ifEmpty { null }
}

fun isAuthenticated(arg: DomainState?): Boolean =
    arg?.authentication?.state == AuthenticationStatus.State.AUTHENTICATED

fun isConnected(arg: DomainState?): Boolean =
    arg?.connection?.state == ConnectionStatus.State.CONNECTED

fun isConnectionBusy(arg: DomainState?): Boolean = when (arg?.connection?.state) {
    ConnectionStatus.State.CONNECTING,
    ConnectionStatus.State.RECONNECTING,
    ConnectionStatus.State.DISCONNECTING -> true

    else -> false
}

fun isConnectionActive(arg: DomainState?): Boolean = when (arg?.connection?.state) {
    ConnectionStatus.State.CONNECTING,
    ConnectionStatus.State.CONNECTED,
    ConnectionStatus.State.RECONNECTING,
    ConnectionStatus.State.DISCONNECTING -> true

    else -> false
}

fun canConnect(arg: DomainState?): Boolean =
    isAuthenticated(arg) &&
        !isConnectionBusy(arg) &&
        !isConnected(arg) &&
        !isOperationActive(arg?.lastOperation)

fun isTeardownOperation(arg: Operation?): Boolean = when (arg?.type) {
    Operation.Type.DISCONNECT,
    Operation.Type.LOGOUT,
    Operation.Type.DELETE -> true

    else -> false
}

fun canDisconnect(arg: DomainState?): Boolean {
    val op = arg?.takeIf { it.hasLastOperation() }?.lastOperation
    if (isOperationActive(op) && isTeardownOperation(op)) {
        return false
    }

    return when (arg?.connection?.state) {
        ConnectionStatus.State.CONNECTED,
        ConnectionStatus.State.CONNECTING,
        ConnectionStatus.State.RECONNECTING -> true

        else -> isOperationActive(op)
    }
}

fun getConnectionStateLabel(arg: ConnectionStatus.State?): String = when (arg) {
    ConnectionStatus.State.CONNECTED -> "Connected"
    ConnectionStatus.State.CONNECTING -> "Connecting"
    ConnectionStatus.State.RECONNECTING -> "Reconnecting"
    ConnectionStatus.State.DISCONNECTING -> "Disconnecting"
    else -> "Disconnected"
}

fun getConnectionStateTone(arg: ConnectionStatus.State?): ConnectivityTone = when (arg) {
    ConnectionStatus.State.CONNECTED -> ConnectivityTone.CONNECTED
    ConnectionStatus.State.CONNECTING,
    ConnectionStatus.State.RECONNECTING,
    ConnectionStatus.State.DISCONNECTING -> ConnectivityTone.PENDING

    else -> ConnectivityTone.IDLE
}

fun getAuthenticationStateLabel(arg: AuthenticationStatus.State?): String = when (arg) {
    AuthenticationStatus.State.AUTHENTICATED -> "Signed in"
    AuthenticationStatus.State.AUTHENTICATING -> "Signing in"
    AuthenticationStatus.State.LOGGING_OUT -> "Signing out"
    else -> "Signed out"
}

fun getAuthenticationStateTone(arg: AuthenticationStatus.State?): LabelTone = when (arg) {
    AuthenticationStatus.State.AUTHENTICATED -> LabelTone.EMERALD
    AuthenticationStatus.State.AUTHENTICATING,
    AuthenticationStatus.State.LOGGING_OUT -> LabelTone.AMBER

    else -> LabelTone.SLATE
}

fun getOperationTypeLabel(arg: Operation.Type?): String = when (arg) {
    Operation.Type.AUTHENTICATE -> "Signing in"
    Operation.Type.CONNECT -> "Connecting"
    Operation.Type.DISCONNECT -> "Disconnecting"
    Operation.Type.LOGOUT -> "Signing out"
    Operation.Type.DELETE -> "Removing"
    else -> "Working"
}

fun getTunnelModeLabel(arg: ConnectionOptions.TunnelMode?): String = when (arg) {
    ConnectionOptions.TunnelMode.WIREGUARD -> "WireGuard"
    ConnectionOptions.TunnelMode.QUICV0 -> "QUIC"
    else -> "Automatic"
}

fun getImplementationModeLabel(arg: ConnectionOptions.ImplementationMode?): String = when (arg) {
    ConnectionOptions.ImplementationMode.KERNEL -> "Kernel"
    ConnectionOptions.ImplementationMode.TUN -> "TUN"
    ConnectionOptions.ImplementationMode.GVISOR -> "gVisor"
    else -> "Automatic"
}

fun getL3ModeLabel(arg: ConnectionOptions.L3Mode?): String = when (arg) {
    ConnectionOptions.L3Mode.V4 -> "IPv4 only"
    ConnectionOptions.L3Mode.V6 -> "IPv6 only"
    ConnectionOptions.L3Mode.BOTH -> "Dual stack"
    else -> "Automatic"
}

fun getDNSModeLabel(arg: ConnectionOptions.DNS.Mode?): String = when (arg) {
    ConnectionOptions.DNS.Mode.DISABLED -> "Disabled"
    ConnectionOptions.DNS.Mode.FULL -> "Full"
    else -> "Split"
}

fun getErrorTitle(arg: Daemonv1.Error?): String = when (arg?.code) {
    Daemonv1.Error.Code.AUTHENTICATION_REQUIRED -> "Sign in required"
    Daemonv1.Error.Code.AUTHENTICATION_FAILED -> "Sign in failed"
    Daemonv1.Error.Code.AUTHENTICATION_TIMED_OUT -> "Sign in timed out"
    Daemonv1.Error.Code.CLUSTER_UNREACHABLE -> "Cluster unreachable"
    Daemonv1.Error.Code.CONNECTION_FAILED -> "Connection failed"
    Daemonv1.Error.Code.NETWORK_CONFIGURATION_FAILED -> "Network configuration failed"
    Daemonv1.Error.Code.DNS_CONFIGURATION_FAILED -> "DNS configuration failed"
    Daemonv1.Error.Code.LOCAL_PORT_CONFLICT -> "Local port already in use"
    Daemonv1.Error.Code.PERMISSION_DENIED -> "Not permitted"
    Daemonv1.Error.Code.OPERATION_CANCELED -> "Canceled"
    else -> "Something went wrong"
}

fun getErrorHint(arg: Daemonv1.Error?): String? = when (arg?.code) {
    Daemonv1.Error.Code.AUTHENTICATION_REQUIRED ->
        "Your credentials are no longer usable. Sign in to the Cluster again."

    Daemonv1.Error.Code.AUTHENTICATION_TIMED_OUT ->
        "The browser sign in was not completed in time. Try again."

    Daemonv1.Error.Code.CLUSTER_UNREACHABLE ->
        "Check your Internet connection and that the Cluster domain is correct."

    Daemonv1.Error.Code.NETWORK_CONFIGURATION_FAILED ->
        "The VPN interface, its addresses or its routes could not be configured."

    Daemonv1.Error.Code.DNS_CONFIGURATION_FAILED ->
        "The DNS configuration of the VPN could not be applied."

    Daemonv1.Error.Code.PERMISSION_DENIED ->
        "This operation is not permitted on this device."

    else -> null
}

fun isErrorRetryable(arg: Daemonv1.Error?): Boolean =
    arg != null && arg.retryable && arg.code != Daemonv1.Error.Code.OPERATION_CANCELED

fun getDomainState(status: GetStatusResponse?, domain: String?): DomainState? {
    if (status == null || domain.isNullOrEmpty()) {
        return null
    }

    return status.domainsList.find { it.domain == domain }
}

fun getDomains(status: GetStatusResponse?): List<String> =
    status?.domainsList.orEmpty().map { it.domain }.sorted()

fun selectDomain(status: GetStatusResponse?, preferred: String?): String? {
    val domains = status?.domainsList.orEmpty()
    if (domains.isEmpty()) {
        return null
    }

    if (preferred != null && domains.any { it.domain == preferred }) {
        return preferred
    }

    domains.find { isConnected(it) }?.let { return it.domain }
    domains.find { isAuthenticated(it) }?.let { return it.domain }

    return domains.map { it.domain }.sorted().firstOrNull()
}

fun resolveSelectedDomain(primaryDomain: String?, status: GetStatusResponse?, selected: String?): String? =
    primaryDomain ?: selectDomain(status, selected)

fun getTunnelDomainState(status: GetStatusResponse?): DomainState? =
    status?.domainsList.orEmpty().find { isConnectionActive(it) }

private val rgxDomain = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
private val rgxScheme = Regex("^[a-z][a-z0-9+.-]*://")

fun validateDomain(arg: String): String? {
    val domain = arg.trim().lowercase(Locale.ROOT)

    if (domain.isEmpty()) {
        return "The Cluster domain is required"
    }

    if (domain.length > 253) {
        return "The Cluster domain is too long"
    }

    if (domain.split(".").any { it.length > 63 }) {
        return "A Cluster domain label is too long"
    }

    if (!rgxDomain.matches(domain)) {
        return "Invalid Cluster domain"
    }

    return null
}

fun normalizeDomain(arg: String): String {
    var ret = arg.trim().lowercase(Locale.ROOT)

    ret = ret.replace(rgxScheme, "")
    ret = ret.substringBefore("/")
    ret = ret.substringBefore("?")
    ret = ret.substringBefore("#")
    ret = ret.substringAfterLast("@")
    ret = ret.substringBefore(":")
    ret = ret.trimEnd('.')

    return try {
        IDN.toASCII(ret, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    } catch (err: IllegalArgumentException) {
        ret
    }
}
