package com.octelium.client.core.auth

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import octelium.api.main.auth.v1.Authv1
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

const val CODE_VERIFIER_LEN = 32

val WEB_AUTHENTICATION_TIMEOUT: Duration = Duration.ofMinutes(5)

class AuthenticationTimedOutException : Exception(
    "You have not authenticated yourself after 5 minutes. Please authenticate yourself again.",
)

class AppAuthenticator(
    val domain: String,
    val scopes: List<String> = emptyList(),
    random: SecureRandom = SecureRandom(),
) {
    val codeVerifier: ByteArray = ByteArray(CODE_VERIFIER_LEN).also { random.nextBytes(it) }

    private val codeChallenge = MessageDigest.getInstance("SHA-256").digest(codeVerifier)
    private val response = CompletableDeferred<Authv1.ClientLoginResponse>()

    fun getLoginURL(): String {
        val req = Authv1.ClientLoginRequest.newBuilder()
            .setApiVersion(Authv1.ClientLoginRequest.APIVersion.V1)
            .setCodeChallenge(ByteString.copyFrom(codeChallenge))
            .setCallbackType(Authv1.ClientLoginRequest.CallbackType.APP)
            .build()

        return "https://$domain/login?octelium_req=" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(req.toByteArray())
    }

    fun getLoginResponse(callbackURL: String): Authv1.ClientLoginResponse {
        val u = try {
            URI(callbackURL)
        } catch (err: URISyntaxException) {
            throw IllegalArgumentException("Invalid callback URL")
        }

        if (!u.scheme.equals(AUTH_CALLBACK_SCHEME, ignoreCase = true) || u.isOpaque || u.rawAuthority != null ||
            u.path != AUTH_CALLBACK_PATH
        ) {
            throw IllegalArgumentException("Invalid callback URL")
        }

        val encoded = getQueryParam(u.rawQuery, "octelium_response")
            ?: throw IllegalArgumentException("No login response is set")

        val ret = try {
            Authv1.ClientLoginResponse.parseFrom(Base64.getUrlDecoder().decode(encoded))
        } catch (err: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid login response encoding")
        } catch (err: InvalidProtocolBufferException) {
            throw IllegalArgumentException("Could not unmarshal the login response")
        }

        if (ret.authenticationToken.isEmpty()) {
            throw IllegalArgumentException("No authentication token is set")
        }

        if (!MessageDigest.isEqual(ret.codeChallenge.toByteArray(), codeChallenge)) {
            throw IllegalArgumentException("The callback does not belong to this authentication")
        }

        return ret
    }

    fun complete(arg: Authv1.ClientLoginResponse) {
        if (!response.complete(arg)) {
            throw IllegalStateException("The authentication is already completed")
        }
    }

    suspend fun wait(): Authv1.ClientLoginResponse =
        withTimeoutOrNull(WEB_AUTHENTICATION_TIMEOUT.toMillis()) { response.await() }
            ?: throw AuthenticationTimedOutException()
}

private fun getQueryParam(query: String?, name: String): String? {
    for (itm in query.orEmpty().split("&")) {
        val parts = itm.split("=", limit = 2)
        if (URLDecoder.decode(parts[0], "UTF-8") == name) {
            return URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
    }

    return null
}
