package com.octelium.client.core.auth

import com.octelium.client.core.domain.getTestDomain
import com.octelium.client.core.domain.getTestOperation
import com.octelium.client.core.domain.getTestStatus
import octelium.api.client.daemon.v1.Daemonv1.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCallbackTest {

    private val expected = "com.octelium.client:/callback/success"

    @Test
    fun testIsAuthCallbackURL() {
        assertTrue(isAuthCallbackURL("com.octelium.client:/callback/success?octelium_response=abc", expected))
        assertTrue(isAuthCallbackURL("COM.OCTELIUM.CLIENT:/callback/success?octelium_response=abc", expected))
        assertTrue(isAuthCallbackURL("com.octelium.client:/callback/success", expected))

        assertFalse(isAuthCallbackURL("", expected))
        assertFalse(isAuthCallbackURL("com.octelium.client:/callback/failure?x=1", expected))
        assertFalse(isAuthCallbackURL("com.octelium.client://callback/success?x=1", expected))
        assertFalse(isAuthCallbackURL("com.octelium.client://evil.com/callback/success", expected))
        assertFalse(isAuthCallbackURL("com.octelium.client:callback/success", expected))
        assertFalse(isAuthCallbackURL("https://example.com/callback/success", expected))
        assertFalse(isAuthCallbackURL("com.octelium.client:/callback/success#frag", expected))
        assertFalse(isAuthCallbackURL("com.octelium.client:/callback/success?x=%%", expected))
        assertFalse(isAuthCallbackURL("/callback/success", expected))
        assertFalse(isAuthCallbackURL("com.octelium.client:/callback/success?x=${"a".repeat(MAX_CALLBACK_URL_LENGTH)}", expected))
        assertFalse(isAuthCallbackURL("com.octelium.client:/callback/success", "%%"))
    }

    @Test
    fun testGetAuthCallbackCandidates() {
        val status = getTestStatus(
            getTestDomain(
                "a.example.com",
                op = getTestOperation(type = Operation.Type.AUTHENTICATE, state = Operation.State.WAITING_FOR_USER, id = "op-a"),
            ),
            getTestDomain(
                "b.example.com",
                op = getTestOperation(type = Operation.Type.AUTHENTICATE, state = Operation.State.RUNNING, id = "op-b"),
            ),
            getTestDomain(
                "c.example.com",
                op = getTestOperation(type = Operation.Type.CONNECT, state = Operation.State.WAITING_FOR_USER, id = "op-c"),
            ),
            getTestDomain(
                "d.example.com",
                op = getTestOperation(type = Operation.Type.AUTHENTICATE, state = Operation.State.WAITING_FOR_USER, id = "op-d"),
            ),
            getTestDomain("e.example.com"),
        )

        assertEquals(listOf("op-a", "op-d"), getWaitingAuthentications(status).map { it.id })
        assertEquals(listOf("op-a", "op-d"), getAuthCallbackCandidates(status, null))
        assertEquals(listOf("op-a", "op-d"), getAuthCallbackCandidates(status, ""))
        assertEquals(listOf("op-d", "op-a"), getAuthCallbackCandidates(status, "op-d"))
        assertEquals(listOf("op-x", "op-a", "op-d"), getAuthCallbackCandidates(status, "op-x"))
        assertEquals(listOf("op-x"), getAuthCallbackCandidates(null, "op-x"))
        assertTrue(getAuthCallbackCandidates(null, null).isEmpty())
    }

    @Test
    fun testIsLoginURLAllowed() {
        assertTrue(isLoginURLAllowed("https://example.com/login?octelium_req=abc"))
        assertTrue(isLoginURLAllowed("HTTPS://example.com/login"))
        assertFalse(isLoginURLAllowed("http://example.com/login"))
        assertFalse(isLoginURLAllowed("javascript:alert(1)"))
        assertFalse(isLoginURLAllowed("intent://example.com/#Intent;end"))
        assertFalse(isLoginURLAllowed("https:///login"))
        assertFalse(isLoginURLAllowed("https://user@example.com/login"))
        assertFalse(isLoginURLAllowed("file:///etc/passwd"))
        assertFalse(isLoginURLAllowed(""))
        assertFalse(isLoginURLAllowed("https://exa mple.com"))
    }
}
