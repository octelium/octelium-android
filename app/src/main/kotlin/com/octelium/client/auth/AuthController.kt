package com.octelium.client.auth

import com.octelium.client.core.auth.AUTH_CALLBACK_URL
import com.octelium.client.core.auth.getAuthCallbackCandidates
import com.octelium.client.core.auth.isAuthCallbackURL
import com.octelium.client.core.cluster.getClusterAPIHost
import com.octelium.client.core.local.LocalClient
import com.octelium.client.core.local.StatusStore
import com.octelium.client.core.network.HostResolver
import com.octelium.client.core.network.getHostCheckError
import io.grpc.Status
import io.grpc.StatusException
import octelium.api.client.daemon.v1.Daemonv1.Operation

class AuthController(
    private val getClient: suspend () -> LocalClient,
    private val statusStore: StatusStore,
    private val hosts: HostResolver? = null,
) {
    @Volatile
    private var pendingOperationID: String? = null

    suspend fun authenticateBrowser(domain: String): Operation {
        checkClusterAPIHost(domain)

        val client = getClient()
        val ret = client.authenticateBrowser(domain)
        pendingOperationID = ret.id
        statusStore.update(client.getStatus())
        return ret
    }

    suspend fun authenticateToken(domain: String, authenticationToken: String): Operation {
        checkClusterAPIHost(domain)

        val client = getClient()
        val ret = client.authenticateToken(domain, authenticationToken)
        statusStore.update(client.getStatus())
        return ret
    }

    fun isCallbackURL(url: String): Boolean = isAuthCallbackURL(url, AUTH_CALLBACK_URL)

    suspend fun completeAuthentication(url: String): Operation {
        if (!isCallbackURL(url)) {
            throw IllegalArgumentException("The authentication callback is invalid")
        }

        val client = getClient()
        val status = client.getStatus()
        statusStore.update(status)

        val candidates = getAuthCallbackCandidates(status, pendingOperationID)
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "There is no sign in waiting for the browser. It might have expired or Octelium was restarted in the meantime. Please sign in again.",
            )
        }

        var lastErr: StatusException? = null

        for (id in candidates) {
            try {
                val ret = client.completeAuthentication(id, url)
                pendingOperationID = null
                statusStore.update(client.getStatus())
                return ret
            } catch (err: StatusException) {
                when (err.status.code) {
                    Status.Code.INVALID_ARGUMENT,
                    Status.Code.FAILED_PRECONDITION,
                    Status.Code.NOT_FOUND -> lastErr = err

                    else -> throw err
                }
            }
        }

        throw lastErr ?: IllegalStateException("Could not complete the authentication")
    }

    private suspend fun checkClusterAPIHost(domain: String) {
        val ret = hosts?.check(getClusterAPIHost(domain)) ?: return
        getHostCheckError(ret)?.let { throw IllegalStateException(it) }
    }
}
