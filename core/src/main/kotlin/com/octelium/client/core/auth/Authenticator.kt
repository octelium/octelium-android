package com.octelium.client.core.auth

import com.google.protobuf.ByteString
import com.octelium.client.core.cluster.AUTH_METADATA_KEY
import com.octelium.client.core.db.DB
import com.octelium.client.core.domain.toInstant
import com.octelium.client.core.local.Logger
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import octelium.api.client.config.v1.Configv1
import octelium.api.main.auth.v1.Authv1
import octelium.api.main.user.v1.Userv1
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import octelium.api.main.auth.v1.MainServiceGrpcKt as AuthServiceGrpcKt
import octelium.api.main.user.v1.MainServiceGrpcKt as UserServiceGrpcKt

const val REFRESH_TOKEN_METADATA_KEY = "x-octelium-refresh-token"

const val MAX_DEVICE_HOSTNAME_LEN = 32

private val refreshTokenMetadataKey: Metadata.Key<String> =
    Metadata.Key.of(REFRESH_TOKEN_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

private val authMetadataKey: Metadata.Key<String> =
    Metadata.Key.of(AUTH_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

class AuthenticationRequiredException : Exception(
    "Interactive authentication is not available in this mode. Please authenticate yourself first",
)

data class DeviceInfo(
    val installationID: String,
    val name: String,
)

class Authenticator(
    private val db: DB,
    private val channels: (String) -> ManagedChannel,
    private val device: DeviceInfo,
    private val logger: Logger = Logger(),
    private val callTimeout: Duration = Duration.ofSeconds(20),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun getAccessToken(domain: String): String {
        val itm = db.get(domain)
        if (itm == null || !hasValidRefreshToken(itm, now())) {
            throw AuthenticationRequiredException()
        }

        if (!needsNewAccessToken(itm, now())) {
            return itm.sessionToken.accessToken
        }

        val refreshToken = itm.sessionToken.refreshToken

        val ret = try {
            getAuthStub(domain).authenticateWithRefreshToken(
                Authv1.AuthenticateWithRefreshTokenRequest.getDefaultInstance(),
                getRefreshTokenHeaders(refreshToken),
            )
        } catch (err: StatusException) {
            when (err.status.code) {
                Status.Code.ALREADY_EXISTS -> return itm.sessionToken.accessToken
                Status.Code.UNAUTHENTICATED -> {
                    db.deleteStaleSessionToken(domain, refreshToken)
                    throw AuthenticationRequiredException()
                }

                else -> throw err
            }
        }

        db.setSessionToken(domain, ret)

        return ret.accessToken
    }

    suspend fun authenticate(
        domain: String,
        authenticationToken: String,
        scopes: List<String>,
        codeVerifier: ByteArray? = null,
    ) {
        val refreshToken = db.getSessionToken(domain)?.refreshToken

        val req = Authv1.AuthenticateWithAuthenticationTokenRequest.newBuilder()
            .setAuthenticationToken(authenticationToken)
            .addAllScopes(scopes)

        codeVerifier?.let { req.setCodeVerifier(ByteString.copyFrom(it)) }

        val ret = getAuthStub(domain).authenticateWithAuthenticationToken(
            req.build(),
            getRefreshTokenHeaders(refreshToken),
        )

        db.setSessionToken(domain, ret)

        try {
            doPostAuth(domain, ret)
        } catch (err: Exception) {
            logger.debug("Could not doPostAuth for the domain $domain: ${err.message}")
        }
    }

    suspend fun logout(domain: String) {
        val token = db.getSessionToken(domain) ?: return

        try {
            getAuthStub(domain).logout(
                Authv1.LogoutRequest.getDefaultInstance(),
                getRefreshTokenHeaders(token.refreshToken),
            )
        } catch (err: StatusException) {
            if (err.status.code != Status.Code.UNAUTHENTICATED) {
                logger.debug("Could not log out at the Cluster of the domain $domain: ${err.message}")
            }
        }

        db.deleteSessionToken(domain)
    }

    private suspend fun doPostAuth(domain: String, token: Authv1.SessionToken) {
        val headers = Metadata()
        headers.put(authMetadataKey, token.accessToken)

        val st = UserServiceGrpcKt.MainServiceCoroutineStub(channels(domain))
            .withDeadlineAfter(callTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .getStatus(Userv1.GetStatusRequest.getDefaultInstance(), headers)

        logger.info("You are now authenticated to $domain as ${getUserName(st.user)}")

        if (st.user.spec.type != Userv1.GetStatusResponse.User.Spec.Type.HUMAN) {
            return
        }

        try {
            registerDevice(domain, token.refreshToken)
        } catch (err: Exception) {
            logger.debug("Could not register the Device to the domain $domain: ${err.message}")
        }
    }

    private suspend fun registerDevice(domain: String, refreshToken: String) {
        val stub = getAuthStub(domain)
        val headers = getRefreshTokenHeaders(refreshToken)

        val resp = try {
            stub.registerDeviceBegin(
                Authv1.RegisterDeviceBeginRequest.newBuilder()
                    .setInfo(
                        Authv1.RegisterDeviceBeginRequest.Info.newBuilder()
                            .setOsType(Authv1.RegisterDeviceBeginRequest.Info.OSType.ANDROID)
                            .setHostname(getDeviceHostname(device.name))
                            .setId(getDeviceID(device.installationID))
                    )
                    .build(),
                headers,
            )
        } catch (err: StatusException) {
            if (err.status.code == Status.Code.ALREADY_EXISTS) {
                logger.debug("The Device is already registered to the domain $domain")
                return
            }
            throw err
        }

        if (resp.requestsCount > 0) {
            throw IllegalStateException("The Device registration requests are not supported on this platform")
        }

        stub.registerDeviceFinish(
            Authv1.RegisterDeviceFinishRequest.newBuilder().setUid(resp.uid).build(),
            headers,
        )

        logger.info("The Device is successfully registered to the domain $domain")
    }

    private fun getAuthStub(domain: String): AuthServiceGrpcKt.MainServiceCoroutineStub =
        AuthServiceGrpcKt.MainServiceCoroutineStub(channels(domain))
            .withDeadlineAfter(callTimeout.toMillis(), TimeUnit.MILLISECONDS)

    private fun getRefreshTokenHeaders(refreshToken: String?): Metadata {
        val ret = Metadata()
        if (!refreshToken.isNullOrEmpty()) {
            ret.put(refreshTokenMetadataKey, refreshToken)
        }
        return ret
    }
}

private fun getUserName(arg: Userv1.GetStatusResponse.User): String =
    if (arg.metadata.displayName.isNotEmpty()) "${arg.metadata.name} (${arg.metadata.displayName})" else arg.metadata.name

fun getDeviceID(installationID: String): String =
    MessageDigest.getInstance("SHA-256").digest(installationID.toByteArray()).joinToString("") { "%02x".format(it) }

fun getDeviceHostname(arg: String): String {
    val ret = arg.trim()
    if (ret.toByteArray().size <= MAX_DEVICE_HOSTNAME_LEN) {
        return ret
    }

    var size = 0
    var end = 0

    while (end < ret.length) {
        val cp = ret.codePointAt(end)
        val n = String(Character.toChars(cp)).toByteArray().size
        if (size + n > MAX_DEVICE_HOSTNAME_LEN) {
            break
        }

        size += n
        end += Character.charCount(cp)
    }

    return ret.substring(0, end).trim()
}

fun getAccessTokenRenewAt(arg: Configv1.State.Domain?): Instant? {
    val expiresAt = getAccessTokenExpiresAt(arg) ?: return null
    return expiresAt.plus(getExpirationGap(arg!!.sessionToken.expiresIn))
}

fun getAccessTokenExpiresAt(arg: Configv1.State.Domain?): Instant? {
    if (arg == null || !arg.hasSessionToken() || !arg.hasSessionTokenSetAt() || arg.sessionToken.expiresIn == 0L) {
        return null
    }

    return toInstant(arg.sessionTokenSetAt)?.plusSeconds(arg.sessionToken.expiresIn)
}

fun getRefreshTokenExpiresAt(arg: Configv1.State.Domain?): Instant? {
    if (arg == null || !arg.hasSessionToken() || !arg.hasSessionTokenSetAt() ||
        arg.sessionToken.refreshTokenExpiresIn == 0L
    ) {
        return null
    }

    return toInstant(arg.sessionTokenSetAt)?.plusSeconds(arg.sessionToken.refreshTokenExpiresIn)
}

fun hasValidRefreshToken(arg: Configv1.State.Domain?, now: Instant = Instant.now()): Boolean {
    val expiresAt = getRefreshTokenExpiresAt(arg) ?: return false
    return now.isBefore(expiresAt)
}

fun needsNewAccessToken(arg: Configv1.State.Domain?, now: Instant = Instant.now()): Boolean {
    if (arg == null || !arg.hasSessionToken() || !arg.hasSessionTokenSetAt()) {
        return true
    }

    val renewAt = getAccessTokenRenewAt(arg) ?: return false
    return now.isAfter(renewAt)
}

private fun getExpirationGap(expiresIn: Long): Duration = if (expiresIn < 3600) {
    Duration.ofSeconds(-600)
} else {
    Duration.ofSeconds(-(expiresIn / 2))
}
