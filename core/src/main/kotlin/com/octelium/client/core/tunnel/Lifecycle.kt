package com.octelium.client.core.tunnel

import com.octelium.client.core.domain.getDomainState
import com.octelium.client.core.domain.isAuthenticated
import com.octelium.client.core.domain.isConnectionActive
import com.octelium.client.core.domain.isOperationActive
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import octelium.api.client.daemon.v1.Daemonv1.GetStatusResponse
import octelium.api.client.daemon.v1.Daemonv1.Operation

fun shouldKeepTunnelService(status: GetStatusResponse?, holds: Int): Boolean {
    if (holds > 0) {
        return true
    }

    return status?.domainsList.orEmpty().any { itm ->
        isConnectionActive(itm) ||
            (itm.hasLastOperation() &&
                itm.lastOperation.type == Operation.Type.CONNECT &&
                isOperationActive(itm.lastOperation))
    }
}

fun isTunnelReleased(status: GetStatusResponse?, domain: String?): Boolean {
    if (status == null || domain == null) {
        return false
    }

    val state = getDomainState(status, domain) ?: return true

    return state.connection.state == ConnectionStatus.State.DISCONNECTED ||
        state.connection.state == ConnectionStatus.State.STATE_UNSPECIFIED
}

fun getAlwaysOnDomain(status: GetStatusResponse?, primaryDomain: String?): String? {
    val domains = status?.domainsList.orEmpty().filter { isAuthenticated(it) }

    domains.find { it.domain == primaryDomain }?.let { return it.domain }
    domains.find { it.settings.autoConnect }?.let { return it.domain }

    return domains.map { it.domain }.sorted().firstOrNull()
}

fun getAutoConnectDomain(status: GetStatusResponse?, primaryDomain: String?): String? {
    val domains = status?.domainsList.orEmpty().filter { isAuthenticated(it) && it.settings.autoConnect }

    if (status?.domainsList.orEmpty().any { isConnectionActive(it) }) {
        return null
    }

    domains.find { it.domain == primaryDomain }?.let { return it.domain }

    return domains.map { it.domain }.sorted().firstOrNull()
}
