package com.octelium.client.auth

import com.octelium.client.core.local.LocalClient
import com.octelium.client.core.local.StatusStore
import com.octelium.client.core.local.getStatusException
import com.octelium.client.core.network.HostResolver
import com.octelium.client.core.network.getHostCheck
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.test.runTest
import octelium.api.client.daemon.v1.Daemonv1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuthControllerTest {

    private val callbackURL = "com.octelium.client:/callback/success"

    private class FakeDaemon : LocalClient {
        val calls = mutableListOf<String>()
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

        private fun authenticate(domain: String): Daemonv1.Operation {
            calls.add("Authenticate")
            val id = "op-${nextID++}"
            waiting[id] = domain
            return getOperation(id, domain, Daemonv1.Operation.State.WAITING_FOR_USER)
        }

        private fun unimplemented(method: String): Nothing {
            calls.add(method)
            throw getStatusException(Status.Code.UNIMPLEMENTED.value(), method)
        }

        override suspend fun getStatus(): Daemonv1.GetStatusResponse {
            calls.add("GetStatus")

            return Daemonv1.GetStatusResponse.newBuilder()
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
        }

        override suspend fun authenticateBrowser(domain: String): Daemonv1.Operation = authenticate(domain)

        override suspend fun authenticateToken(domain: String, authenticationToken: String): Daemonv1.Operation =
            authenticate(domain)

        override suspend fun completeAuthentication(operationID: String, callbackURL: String): Daemonv1.Operation {
            calls.add("CompleteAuthentication")
            completed.add(operationID to callbackURL)

            if (!validOperationID.contains(operationID)) {
                throw getStatusException(Status.Code.INVALID_ARGUMENT.value(), "Invalid authentication callback URL")
            }

            val domain = waiting.remove(operationID)!!
            return getOperation(operationID, domain, Daemonv1.Operation.State.RUNNING)
        }

        override suspend fun connect(domain: String): Daemonv1.Operation = unimplemented("Connect")

        override suspend fun disconnect(domain: String): Daemonv1.Operation = unimplemented("Disconnect")

        override suspend fun logout(domain: String): Daemonv1.Operation = unimplemented("Logout")

        override suspend fun deleteDomain(domain: String): Daemonv1.Operation = unimplemented("DeleteDomain")

        override suspend fun getOperation(id: String): Daemonv1.Operation = unimplemented("GetOperation")

        override suspend fun cancelOperation(id: String): Daemonv1.Operation = unimplemented("CancelOperation")

        override suspend fun getAPICredential(domain: String): Daemonv1.GetAPICredentialResponse =
            unimplemented("GetAPICredential")

        override suspend fun updateDomainSettings(
            domain: String,
            settings: Daemonv1.DomainSettings,
        ): Daemonv1.DomainSettings = unimplemented("UpdateDomainSettings")

        override suspend fun setNetworkState(isAvailable: Boolean, id: String) {
            unimplemented("SetNetworkState")
        }
    }

    private fun getController(
        daemon: FakeDaemon,
        statusStore: StatusStore = StatusStore(),
        hosts: HostResolver? = null,
    ): AuthController = AuthController(
        getClient = { daemon },
        statusStore = statusStore,
        hosts = hosts,
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
    fun testCheckClusterAPIHost() = runTest {
        run {
            val daemon = FakeDaemon()
            val checked = mutableListOf<String>()
            val c = getController(daemon, hosts = { checked.add(it); getHostCheck(it, listOf("192.0.2.1"), null) })

            c.authenticateBrowser("a.example.com")
            c.authenticateToken("b.example.com", "token")
            assertEquals(listOf("octelium-api.a.example.com", "octelium-api.b.example.com"), checked)
            assertEquals(2, daemon.calls.count { it == "Authenticate" })
        }

        run {
            val daemon = FakeDaemon()
            val c = getController(daemon, hosts = { getHostCheck(it, emptyList(), listOf("192.0.2.1")) })

            try {
                c.authenticateBrowser("example.com")
                fail()
            } catch (err: IllegalStateException) {
                assertTrue(err.message!!.startsWith("octelium-api.example.com resolves to 192.0.2.1 but Android refuses"))
            }

            try {
                c.authenticateToken("example.com", "token")
                fail()
            } catch (err: IllegalStateException) {
                assertTrue(err.message!!.startsWith("octelium-api.example.com resolves to 192.0.2.1"))
            }

            assertTrue(daemon.calls.isEmpty())
        }

        run {
            val daemon = FakeDaemon()
            val c = getController(daemon, hosts = { getHostCheck(it, emptyList(), emptyList()) })

            try {
                c.authenticateBrowser("example.com")
                fail()
            } catch (err: IllegalStateException) {
                assertTrue(err.message!!.startsWith("octelium-api.example.com could not be found."))
            }
            assertTrue(daemon.calls.isEmpty())
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
