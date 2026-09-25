package com.octelium.client.core.auth

import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import octelium.api.main.auth.v1.Authv1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

class AppAuthenticatorTest {

    private fun getCallbackURL(resp: Authv1.ClientLoginResponse, prefix: String = AUTH_CALLBACK_URL): String =
        "$prefix?octelium_response=" + Base64.getUrlEncoder().withoutPadding().encodeToString(resp.toByteArray())

    private fun assertInvalid(msg: String, fn: () -> Unit) {
        try {
            fn()
            fail()
        } catch (err: IllegalArgumentException) {
            assertEquals(msg, err.message)
        }
    }

    @Test
    fun testAppAuthenticator() = runBlocking {
        val a = AppAuthenticator("example.com")

        val u = URI(a.getLoginURL())
        assertEquals("https://example.com/login", "${u.scheme}://${u.host}${u.path}")

        val req = Authv1.ClientLoginRequest.parseFrom(Base64.getUrlDecoder().decode(u.rawQuery.removePrefix("octelium_req=")))
        assertEquals(Authv1.ClientLoginRequest.CallbackType.APP, req.callbackType)
        assertEquals(0, req.callbackPort)
        assertTrue(req.callbackSuffix.isEmpty())
        assertEquals(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(a.codeVerifier)), req.codeChallenge)

        val resp = Authv1.ClientLoginResponse.newBuilder()
            .setAuthenticationToken("auth-token")
            .setCodeChallenge(req.codeChallenge)
            .build()

        assertEquals(resp, a.getLoginResponse(getCallbackURL(resp)))
        assertEquals(resp, a.getLoginResponse(getCallbackURL(resp, "COM.OCTELIUM.CLIENT:/callback/success")))

        assertInvalid("Invalid callback URL") { a.getLoginResponse(getCallbackURL(resp, "https://example.com/callback/success")) }
        assertInvalid("Invalid callback URL") { a.getLoginResponse(getCallbackURL(resp, "com.octelium.client://host/callback/success")) }
        assertInvalid("Invalid callback URL") { a.getLoginResponse(getCallbackURL(resp, "com.octelium.client:/callback/other")) }
        assertInvalid("Invalid callback URL") { a.getLoginResponse("com.octelium.client:callback") }
        assertInvalid("No login response is set") { a.getLoginResponse(AUTH_CALLBACK_URL) }
        assertInvalid("Invalid login response encoding") { a.getLoginResponse("$AUTH_CALLBACK_URL?octelium_response=!!!") }
        assertInvalid("No authentication token is set") {
            a.getLoginResponse(getCallbackURL(resp.toBuilder().clearAuthenticationToken().build()))
        }
        assertInvalid("The callback does not belong to this authentication") {
            a.getLoginResponse(getCallbackURL(resp.toBuilder().setCodeChallenge(ByteString.copyFrom(ByteArray(32))).build()))
        }

        a.complete(resp)
        assertEquals(resp, a.wait())

        try {
            a.complete(resp)
            fail()
        } catch (err: IllegalStateException) {
            assertEquals("The authentication is already completed", err.message)
        }
    }
}
