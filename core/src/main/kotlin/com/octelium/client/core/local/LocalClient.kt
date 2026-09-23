package com.octelium.client.core.local

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import io.grpc.Status
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.mobile.v1.Mobilev1

const val API_MAJOR_VERSION = 1

class LocalClient(private val transport: LocalTransport) {

    suspend fun getInfo(): Mobilev1.GetInfoResponse = call(
        "GetInfo",
        Mobilev1.GetInfoRequest.getDefaultInstance(),
        Mobilev1.GetInfoResponse.parser(),
    )

    suspend fun getStatus(): Daemonv1.GetStatusResponse = call(
        "GetStatus",
        Daemonv1.GetStatusRequest.getDefaultInstance(),
        Daemonv1.GetStatusResponse.parser(),
    )

    suspend fun authenticateBrowser(domain: String): Daemonv1.Operation = call(
        "Authenticate",
        Daemonv1.AuthenticateRequest.newBuilder()
            .setDomain(domain)
            .setBrowser(Daemonv1.AuthenticateRequest.Browser.getDefaultInstance())
            .build(),
        Daemonv1.Operation.parser(),
    )

    suspend fun authenticateToken(domain: String, authenticationToken: String): Daemonv1.Operation = call(
        "Authenticate",
        Daemonv1.AuthenticateRequest.newBuilder()
            .setDomain(domain)
            .setAuthenticationToken(
                Daemonv1.AuthenticateRequest.AuthenticationToken.newBuilder()
                    .setAuthenticationToken(authenticationToken)
            )
            .build(),
        Daemonv1.Operation.parser(),
    )

    suspend fun completeAuthentication(operationID: String, callbackURL: String): Daemonv1.Operation = call(
        "CompleteAuthentication",
        Mobilev1.CompleteAuthenticationRequest.newBuilder()
            .setOperationID(operationID)
            .setCallbackURL(callbackURL)
            .build(),
        Daemonv1.Operation.parser(),
    )

    suspend fun connect(domain: String): Daemonv1.Operation = call(
        "Connect",
        Daemonv1.ConnectRequest.newBuilder().setDomain(domain).build(),
        Daemonv1.Operation.parser(),
    )

    suspend fun disconnect(domain: String): Daemonv1.Operation = call(
        "Disconnect",
        Daemonv1.DisconnectRequest.newBuilder().setDomain(domain).build(),
        Daemonv1.Operation.parser(),
    )

    suspend fun logout(domain: String): Daemonv1.Operation = call(
        "Logout",
        Daemonv1.LogoutRequest.newBuilder().setDomain(domain).build(),
        Daemonv1.Operation.parser(),
    )

    suspend fun deleteDomain(domain: String): Daemonv1.Operation = call(
        "DeleteDomain",
        Daemonv1.DeleteDomainRequest.newBuilder().setDomain(domain).build(),
        Daemonv1.Operation.parser(),
    )

    suspend fun getOperation(id: String): Daemonv1.Operation = call(
        "GetOperation",
        Daemonv1.GetOperationRequest.newBuilder().setId(id).build(),
        Daemonv1.Operation.parser(),
    )

    suspend fun cancelOperation(id: String): Daemonv1.Operation = call(
        "CancelOperation",
        Daemonv1.CancelOperationRequest.newBuilder().setId(id).build(),
        Daemonv1.Operation.parser(),
    )

    suspend fun getAPICredential(domain: String): Daemonv1.GetAPICredentialResponse = call(
        "GetAPICredential",
        Daemonv1.GetAPICredentialRequest.newBuilder().setDomain(domain).build(),
        Daemonv1.GetAPICredentialResponse.parser(),
    )

    suspend fun updateDomainSettings(
        domain: String,
        settings: Daemonv1.DomainSettings,
    ): Daemonv1.DomainSettings = call(
        "UpdateDomainSettings",
        Daemonv1.UpdateDomainSettingsRequest.newBuilder()
            .setDomain(domain)
            .setSettings(settings)
            .build(),
        Daemonv1.DomainSettings.parser(),
    )

    suspend fun setNetworkState(isAvailable: Boolean, id: String): Mobilev1.SetNetworkStateResponse = call(
        "SetNetworkState",
        Mobilev1.SetNetworkStateRequest.newBuilder()
            .setIsAvailable(isAvailable)
            .setId(id)
            .build(),
        Mobilev1.SetNetworkStateResponse.parser(),
    )

    private suspend fun <T : MessageLite> call(method: String, req: MessageLite, parser: Parser<T>): T {
        val resp = transport.call(method, req.toByteArray())

        return try {
            parser.parseFrom(resp)
        } catch (err: InvalidProtocolBufferException) {
            throw Status.INTERNAL
                .withDescription("Could not unmarshal the $method response: ${err.message}")
                .asException()
        }
    }
}

fun checkInfo(info: Mobilev1.GetInfoResponse): String? {
    if (info.apiMajorVersion != API_MAJOR_VERSION) {
        return "liboctelium implements the local API version ${info.apiMajorVersion} while this application requires the version $API_MAJOR_VERSION"
    }

    if (info.authenticationCallbackURL.isEmpty()) {
        return "liboctelium does not provide an authentication callback URL"
    }

    return null
}
