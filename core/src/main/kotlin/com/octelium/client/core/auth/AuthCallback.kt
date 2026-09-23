package com.octelium.client.core.auth

import octelium.api.client.daemon.v1.Daemonv1.GetStatusResponse
import octelium.api.client.daemon.v1.Daemonv1.Operation
import java.net.URI
import java.net.URISyntaxException

const val MAX_CALLBACK_URL_LENGTH = 16 * 1024

private fun parseURI(arg: String): URI? = try {
    URI(arg)
} catch (err: URISyntaxException) {
    null
}

fun isAuthCallbackURL(url: String, expected: String): Boolean {
    if (url.isEmpty() || url.length > MAX_CALLBACK_URL_LENGTH) {
        return false
    }

    val u = parseURI(url) ?: return false
    val e = parseURI(expected) ?: return false

    if (u.isOpaque || u.scheme == null || e.scheme == null) {
        return false
    }

    return u.scheme.equals(e.scheme, ignoreCase = true) &&
        u.rawAuthority == null &&
        u.rawPath == e.rawPath &&
        u.rawFragment == null
}

fun getWaitingAuthentications(status: GetStatusResponse?): List<Operation> =
    status?.domainsList.orEmpty()
        .filter { it.hasLastOperation() }
        .map { it.lastOperation }
        .filter { it.type == Operation.Type.AUTHENTICATE && it.state == Operation.State.WAITING_FOR_USER }

fun getAuthCallbackCandidates(status: GetStatusResponse?, preferredOperationID: String?): List<String> =
    (listOfNotNull(preferredOperationID?.ifEmpty { null }) + getWaitingAuthentications(status).map { it.id })
        .distinct()

fun isLoginURLAllowed(url: String): Boolean {
    val u = parseURI(url) ?: return false

    return u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty() && u.rawUserInfo == null
}
