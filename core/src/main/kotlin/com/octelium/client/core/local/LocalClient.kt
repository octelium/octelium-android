package com.octelium.client.core.local

import octelium.api.client.daemon.v1.Daemonv1

interface LocalClient {
    suspend fun getStatus(): Daemonv1.GetStatusResponse

    suspend fun authenticateBrowser(domain: String): Daemonv1.Operation

    suspend fun authenticateToken(domain: String, authenticationToken: String): Daemonv1.Operation

    suspend fun completeAuthentication(operationID: String, callbackURL: String): Daemonv1.Operation

    suspend fun connect(domain: String): Daemonv1.Operation

    suspend fun disconnect(domain: String): Daemonv1.Operation

    suspend fun logout(domain: String): Daemonv1.Operation

    suspend fun deleteDomain(domain: String): Daemonv1.Operation

    suspend fun getOperation(id: String): Daemonv1.Operation

    suspend fun cancelOperation(id: String): Daemonv1.Operation

    suspend fun getAPICredential(domain: String): Daemonv1.GetAPICredentialResponse

    suspend fun updateDomainSettings(domain: String, settings: Daemonv1.DomainSettings): Daemonv1.DomainSettings

    suspend fun setNetworkState(isAvailable: Boolean, id: String)
}
