package com.octelium.client.core.client

import com.octelium.client.core.auth.AuthenticationRequiredException
import com.octelium.client.core.auth.AuthenticationTimedOutException
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.core.local.getStatusCode
import com.octelium.client.core.tunnel.TunnelError
import com.octelium.client.core.tunnel.TunnelException
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import octelium.api.client.daemon.v1.Daemonv1

fun getError(err: Throwable, code: Daemonv1.Error.Code): Daemonv1.Error {
    var retCode = code
    var isRetryable = false

    val chain = generateSequence(err) { it.cause }.take(8).toList()
    val statusCode = chain.firstNotNullOfOrNull { getStatusCode(it) }
    val tunnelErr = chain.filterIsInstance<TunnelException>().firstOrNull()

    when {
        chain.any { it is AuthenticationTimedOutException } -> retCode = Daemonv1.Error.Code.AUTHENTICATION_TIMED_OUT
        chain.any { it is AuthenticationRequiredException } -> retCode = Daemonv1.Error.Code.AUTHENTICATION_REQUIRED
        chain.any { it is TimeoutCancellationException } -> isRetryable = true
        chain.any { it is CancellationException } -> retCode = Daemonv1.Error.Code.OPERATION_CANCELED
        statusCode == Status.Code.UNAUTHENTICATED -> retCode = Daemonv1.Error.Code.AUTHENTICATION_REQUIRED
        statusCode == Status.Code.PERMISSION_DENIED -> retCode = Daemonv1.Error.Code.PERMISSION_DENIED
        statusCode == Status.Code.DEADLINE_EXCEEDED -> isRetryable = true
        statusCode == Status.Code.UNAVAILABLE -> retCode = Daemonv1.Error.Code.CLUSTER_UNREACHABLE
        tunnelErr?.error == TunnelError.UNAUTHENTICATED -> retCode = Daemonv1.Error.Code.AUTHENTICATION_REQUIRED
        tunnelErr?.error == TunnelError.PLATFORM -> retCode = Daemonv1.Error.Code.NETWORK_CONFIGURATION_FAILED
    }

    when (retCode) {
        Daemonv1.Error.Code.CLUSTER_UNREACHABLE,
        Daemonv1.Error.Code.CONNECTION_FAILED,
        Daemonv1.Error.Code.NETWORK_CONFIGURATION_FAILED,
        Daemonv1.Error.Code.DNS_CONFIGURATION_FAILED -> isRetryable = true

        else -> {}
    }

    return Daemonv1.Error.newBuilder()
        .setCode(retCode)
        .setMessage(getErrorMessage(err))
        .setRetryable(isRetryable)
        .build()
}
