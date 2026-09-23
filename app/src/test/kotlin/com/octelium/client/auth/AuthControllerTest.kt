package com.octelium.client.auth

import com.octelium.client.core.local.LocalClient
import com.octelium.client.core.local.LocalTransport
import com.octelium.client.core.local.StatusStore
import com.octelium.client.core.local.getStatusException
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.test.runTest
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.mobile.v1.Mobilev1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuthControllerTest {

    private val callbackURL = "com.octelium.client:/callback/success"

    private class FakeDaemon : LocalTransport {
        val waiting = linkedMapOf<String, String>()
        val validOperationID = mutableSetOf<String>()
        val completed = mutableListOf<Pair<String, String>>()
        var nextID = 0

        private fun getOperation(id: String, domain: String, state: Daemonv1.Operation.State) =
            Daemonv1.Operation.newBuilder()
                .setId(id)
                .setDomain(domain)
                .setType(Daemonv1.Operation.Type.AUTHENTICATE)
                .setState(state)
                .build()

        override suspend fun call(method: String, request: ByteArray): ByteArray = when (method) {
            "Authenticate" -> {
                val req = Daemonv1.AuthenticateRequest.parseFrom(request)
                val id = "op-${nextID++}"
                waiting[id] = req.domain
                getOperation(id, req.domain, Daemonv1.Operation.State.WAITING_FOR_USER).toByteArray()
            }

            "GetStatus" -> Daemonv1.GetStatusResponse.newBuilder()
                .setRevision(nextID.toLong())
                .addAllDomains(
                    waiting.map { (id, domain) ->
                        Daemonv1.DomainState.newBuilder()
                            .setDomain(domain)
                            .setLastOperation(getOperation(id, domain, Daemonv1.Operation.State.WAITING_FOR_USER))
                            .build()
                    },
                )
                .build()
                .toByteArray()

            "CompleteAuthentication" -> {
                val req = Mobilev1.CompleteAuthenticationRequest.parseFrom(request)
                completed.add(req.operationID to req.callbackURL)

                if (!validOperationID.contains(req.operationID)) {
                    throw getStatusException(Status.Code.INVALID_ARGUMENT.value(), "Invalid authentication callback URL")
                }

                val domain = waiting.remove(req.operationID)!!
                getOperation(req.operationID, domain, Daemonv1.Operation.State.RUNNING).toByteArray()
            }

            else -> throw getStatusException(Status.Code.UNIMPLEMENTED.value(), method)
        }
    }

    private fun getController(daemon: FakeDaemon, statusStore: StatusStore = StatusStore()): AuthController =
        AuthController(
            getClient = { LocalClient(daemon) },
            getInfo = { Mobilev1.GetInfoResponse.newBuilder().setAuthenticationCallbackURL(callbackURL).build() },
            statusStore = statusStore,
        )

    @Test
    fun testCompleteAuthentication() = runTest {
        val daemon = FakeDaemon()
        val statusStore = StatusStore()
        val c = getController(daemon, statusStore)

        val a = c.authenticateBrowser("a.example.com")
        val b = c.authenticateBrowser("b.example.com")
        assertEquals(2, statusStore.status.value?.domainsCount)

        daemon.validOperationID.add(a.id)

        val ret = c.completeAuthentication("$callbackURL?octelium_response=abc")
        assertEquals(a.id, ret.id)
        assertEquals(Daemonv1.Operation.State.RUNNING, ret.state)
        assertEquals(listOf(b.id, a.id), daemon.completed.map { it.first })
        assertEquals("$callbackURL?octelium_response=abc", daemon.completed.last().second)
        assertEquals(1, statusStore.status.value?.domainsCount)
    }

    @Test
    fun testCompleteAuthenticationErrors() = runTest {
        run {
            val daemon = FakeDaemon()
            val c = getController(daemon)

            try {
                c.completeAuthentication("https://example.com/callback/success")
                fail()
            } catch (err: IllegalArgumentException) {
                assertEquals("The authentication callback is invalid", err.message)
            }
            assertTrue(daemon.completed.isEmpty())
        }

        run {
            val c = getController(FakeDaemon())

            try {
                c.completeAuthentication("$callbackURL?octelium_response=abc")
                fail()
            } catch (err: IllegalStateException) {
                assertTrue(err.message!!.startsWith("There is no sign in waiting for the browser"))
            }
        }

        run {
            val daemon = FakeDaemon()
            val c = getController(daemon)
            c.authenticateBrowser("a.example.com")

            try {
                c.completeAuthentication("$callbackURL?octelium_response=abc")
                fail()
            } catch (err: StatusException) {
                assertEquals(Status.Code.INVALID_ARGUMENT, err.status.code)
            }
        }
    }

    @Test
    fun testIsCallbackURL() = runTest {
        val c = getController(FakeDaemon())

        assertTrue(c.isCallbackURL("$callbackURL?octelium_response=abc"))
        assertFalse(c.isCallbackURL("com.octelium.client:/other"))
        assertFalse(c.isCallbackURL("https://example.com"))
    }
}
