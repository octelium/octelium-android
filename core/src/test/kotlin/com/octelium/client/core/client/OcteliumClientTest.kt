package com.octelium.client.core.client

import com.google.protobuf.ByteString
import com.octelium.client.core.auth.AUTH_CALLBACK_URL
import com.octelium.client.core.auth.DeviceInfo
import com.octelium.client.core.auth.REFRESH_TOKEN_METADATA_KEY
import com.octelium.client.core.auth.getDeviceID
import com.octelium.client.core.cluster.ChannelFactory
import com.octelium.client.core.db.DB
import com.octelium.client.core.domain.toTimestamp
import com.octelium.client.core.local.StatusStore
import com.octelium.client.core.network.NetworkWatcher
import com.octelium.client.core.tunnel.DNSMode
import com.octelium.client.core.tunnel.TunnelError
import com.octelium.client.core.tunnel.TunnelMode
import com.octelium.client.core.tunnel.TunnelResponse
import com.octelium.client.core.tunnel.TunnelState
import com.octelium.client.core.tunnel.TunnelStatus
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.daemon.v1.Daemonv1.AuthenticationStatus
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import octelium.api.main.auth.v1.Authv1
import octelium.api.main.user.v1.Userv1
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Collections

class OcteliumClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cluster = FakeCluster()
    private val statusStore = StatusStore()
    private val tunnels: MutableList<FakeTunnel> = Collections.synchronizedList(mutableListOf())
    private val host = FakeHost()
    private val key = ByteArray(32) { it.toByte() }
    private val device = DeviceInfo(installationID = "8a3b1f0e-6d4c-4b1a-9e2f-0c7d5e3a9b11", name = "Pixel")

    private lateinit var stateDir: File

    private fun getClient(dir: File = tmp.newFolder().also { stateDir = it }): OcteliumClient = OcteliumClient(
        db = DB(dir, key),
        device = device,
        channels = ChannelFactory { InProcessChannelBuilder.forName(cluster.serverName).directExecutor().build() },
        tunnels = { h -> FakeTunnel(h).also { tunnels.add(it) } },
        host = host,
        onStatus = statusStore::update,
        network = NetworkWatcher { Duration.ofMillis(20) },
    )

    @After
    fun tearDown() {
        cluster.close()
    }

    private fun awaitDomain(domain: String = "example.com", fn: (Daemonv1.DomainState) -> Boolean): Daemonv1.DomainState =
        runBlocking {
            withTimeout(10_000) {
                statusStore.status.filterNotNull().first { st ->
                    st.domainsList.find { it.domain == domain }?.let(fn) == true
                }.domainsList.first { it.domain == domain }
            }
        }

    private fun awaitCondition(fn: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!fn()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for the condition")
            }
            Thread.sleep(10)
        }
    }

    private suspend fun assertCode(code: Status.Code, fn: suspend () -> Unit) {
        try {
            fn()
            fail()
        } catch (err: StatusException) {
            assertEquals(code, err.status.code)
        }
    }

    private fun authenticate(c: OcteliumClient, domain: String = "example.com"): Daemonv1.DomainState = runBlocking {
        val op = c.authenticateToken(domain, TEST_AUTHENTICATION_TOKEN)
        assertEquals(Daemonv1.Operation.Type.AUTHENTICATE, op.type)
        awaitDomain(op.domain) { it.lastOperation.state == Daemonv1.Operation.State.SUCCEEDED }
    }

    private fun connect(c: OcteliumClient, domain: String = "example.com"): Daemonv1.DomainState = runBlocking {
        c.connect(domain)
        awaitDomain(domain) { it.connection.state == ConnectionStatus.State.CONNECTED }
    }

    @Test
    fun testAuthenticateToken() = runBlocking {
        val c = getClient()

        assertTrue(c.getStatus().domainsList.isEmpty())

        val st = authenticate(c, "Example.COM.")

        assertEquals("example.com", st.domain)
        assertEquals(AuthenticationStatus.State.AUTHENTICATED, st.authentication.state)
        assertTrue(st.authentication.hasAuthenticatedAt())
        assertTrue(st.authentication.hasExpiresAt())
        assertFalse(st.hasLastError())

        run {
            val req = cluster.authenticateRequests.single()
            assertEquals(TEST_AUTHENTICATION_TOKEN, req.authenticationToken)
            assertTrue(req.codeVerifier.isEmpty)
        }

        run {
            val req = cluster.registerRequests.single()
            assertEquals(Authv1.RegisterDeviceBeginRequest.Info.OSType.ANDROID, req.info.osType)
            assertEquals(getDeviceID(device.installationID), req.info.id)
            assertEquals("Pixel", req.info.hostname)
            awaitCondition { cluster.getCalls("RegisterDeviceFinish").isNotEmpty() }
        }

        run {
            val ret = c.getAPICredential("example.com")
            assertEquals("access-1", ret.accessToken)
            assertTrue(ret.hasExpiresAt())
            assertTrue(cluster.getCalls("AuthenticateWithRefreshToken").isEmpty())
        }

        run {
            val db = DB(stateDir, key)
            assertEquals("refresh-1", db.getSessionToken("example.com")?.refreshToken)
        }

        c.close()
    }

    @Test
    fun testAuthenticateTokenFailed() = runBlocking {
        val c = getClient()

        c.authenticateToken("example.com", "invalid")
        val st = awaitDomain { it.lastOperation.state == Daemonv1.Operation.State.FAILED }

        assertEquals(AuthenticationStatus.State.LOGGED_OUT, st.authentication.state)
        assertEquals(Daemonv1.Error.Code.AUTHENTICATION_REQUIRED, st.lastError.code)
        assertEquals("Invalid authentication Token", st.lastError.message)
        assertEquals(st.lastError, st.lastOperation.error)

        assertCode(Status.Code.INVALID_ARGUMENT) { c.authenticateToken("example.com", "") }
        assertCode(Status.Code.INVALID_ARGUMENT) { c.authenticateToken("not a domain", TEST_AUTHENTICATION_TOKEN) }
        assertCode(Status.Code.INVALID_ARGUMENT) { c.authenticateToken("192.168.1.1", TEST_AUTHENTICATION_TOKEN) }
        assertCode(Status.Code.UNAUTHENTICATED) { c.getAPICredential("example.com") }
        assertCode(Status.Code.UNAUTHENTICATED) { c.connect("example.com") }
        assertCode(Status.Code.NOT_FOUND) { c.connect("unknown.example.com") }

        c.close()
    }

    @Test
    fun testAuthenticateBrowser() = runBlocking {
        val c = getClient()

        val op = c.authenticateBrowser("example.com")
        assertEquals(Daemonv1.Operation.State.WAITING_FOR_USER, op.state)
        assertTrue(op.cancellable)

        val loginURL = URI(op.action.openURL.url)
        assertEquals("https", loginURL.scheme)
        assertEquals("example.com", loginURL.host)
        assertEquals("/login", loginURL.path)

        val req = Authv1.ClientLoginRequest.parseFrom(
            Base64.getUrlDecoder().decode(loginURL.rawQuery.removePrefix("octelium_req=")),
        )
        assertEquals(Authv1.ClientLoginRequest.CallbackType.APP, req.callbackType)
        assertEquals(Authv1.ClientLoginRequest.APIVersion.V1, req.apiVersion)
        assertEquals(32, req.codeChallenge.size())

        fun getCallbackURL(token: String, challenge: ByteString): String {
            val resp = Authv1.ClientLoginResponse.newBuilder()
                .setAuthenticationToken(token)
                .setCodeChallenge(challenge)
                .build()

            return "$AUTH_CALLBACK_URL?octelium_response=" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(resp.toByteArray())
        }

        assertEquals(
            AuthenticationStatus.State.AUTHENTICATING,
            c.getStatus().domainsList.single().authentication.state,
        )

        assertCode(Status.Code.INVALID_ARGUMENT) {
            c.completeAuthentication(op.id, getCallbackURL(TEST_AUTHENTICATION_TOKEN, ByteString.copyFrom(ByteArray(32))))
        }
        assertCode(Status.Code.INVALID_ARGUMENT) {
            c.completeAuthentication(op.id, "https://example.com/callback/success")
        }
        assertCode(Status.Code.INVALID_ARGUMENT) { c.completeAuthentication(op.id, getCallbackURL("", req.codeChallenge)) }
        assertCode(Status.Code.NOT_FOUND) { c.completeAuthentication("unknown", getCallbackURL("x", req.codeChallenge)) }

        val ret = c.completeAuthentication(op.id, getCallbackURL(TEST_AUTHENTICATION_TOKEN, req.codeChallenge))
        assertEquals(Daemonv1.Operation.State.RUNNING, ret.state)

        val st = awaitDomain { it.lastOperation.state == Daemonv1.Operation.State.SUCCEEDED }
        assertEquals(AuthenticationStatus.State.AUTHENTICATED, st.authentication.state)

        val verifier = cluster.authenticateRequests.single().codeVerifier.toByteArray()
        assertArrayEquals(req.codeChallenge.toByteArray(), MessageDigest.getInstance("SHA-256").digest(verifier))

        assertCode(Status.Code.FAILED_PRECONDITION) {
            c.completeAuthentication(op.id, getCallbackURL(TEST_AUTHENTICATION_TOKEN, req.codeChallenge))
        }

        c.close()
    }

    @Test
    fun testCancelAuthentication() = runBlocking {
        val c = getClient()

        val op = c.authenticateBrowser("example.com")
        assertCode(Status.Code.FAILED_PRECONDITION) { c.authenticateBrowser("example.com") }

        val ret = c.cancelOperation(op.id)
        assertEquals(Daemonv1.Operation.State.CANCELED, ret.state)
        assertEquals(Daemonv1.Error.Code.OPERATION_CANCELED, ret.error.code)

        val st = awaitDomain { it.authentication.state == AuthenticationStatus.State.LOGGED_OUT }
        assertFalse(st.hasLastError())
        assertEquals(Daemonv1.Operation.State.CANCELED, c.getOperation(op.id).state)
        assertEquals(Daemonv1.Operation.State.CANCELED, c.cancelOperation(op.id).state)

        assertCode(Status.Code.NOT_FOUND) { c.getOperation("unknown") }
        assertCode(Status.Code.INVALID_ARGUMENT) { c.getOperation("") }

        val next = c.authenticateBrowser("example.com")
        c.logout("example.com")
        assertEquals(Daemonv1.Operation.State.CANCELED, c.getOperation(next.id).state)

        c.close()
    }

    @Test
    fun testConnect() = runBlocking {
        val c = getClient()
        authenticate(c)

        c.setNetworkState(true, "100")

        val st = connect(c)

        run {
            val tunnel = tunnels.single()
            val cfg = tunnel.configs.single()
            assertEquals("example.com", cfg.domain)
            assertEquals(cluster.state, cfg.state)
            assertEquals(TunnelMode.WIREGUARD, cfg.preferences.tunnelMode)
            assertEquals(DNSMode.DEFAULT, cfg.preferences.dnsMode)
            assertTrue(tunnel.networkStates.contains(true to "100"))

            val init = cluster.initRequests.first().initialize
            assertEquals(Userv1.ConnectRequest.Initialize.L3Mode.V6, init.l3Mode)
            assertEquals(Userv1.ConnectRequest.Initialize.ConnectionType.UNSET, init.connectionType)
            assertFalse(init.ignoreDNS)

            val (domain, spec) = host.specs.single()
            assertEquals("example.com", domain)
            assertEquals(listOf("100.64.0.5/32", "fdee:1:0:0:0:0:0:5/128"), spec.addresses.map { it.toString() })
        }

        run {
            assertEquals(Daemonv1.Operation.Type.CONNECT, st.lastOperation.type)
            assertEquals(Daemonv1.Operation.State.SUCCEEDED, st.lastOperation.state)
            assertTrue(st.connection.hasConnectedAt())
            assertEquals(1280, st.connection.mtu)
            assertEquals(Daemonv1.ConnectionOptions.TunnelMode.WIREGUARD, st.connection.tunnelMode)
            assertEquals(Daemonv1.ConnectionOptions.ImplementationMode.TUN, st.connection.implementationMode)
            assertEquals("fdee:1::5/128", st.connection.addressesList.single().v6)
            assertEquals(listOf("fdee:1::53"), st.connection.dns.serversList)
            assertTrue(st.connection.dns.isConfigured)
            assertEquals(Daemonv1.ConnectionOptions.DNS.Mode.DEFAULT, st.connection.options.dns.mode)
        }

        assertCode(Status.Code.FAILED_PRECONDITION) { c.connect("example.com") }

        run {
            cluster.send(
                Userv1.ConnectResponse.newBuilder()
                    .setAddGateway(Userv1.ConnectResponse.AddGateway.newBuilder().setGateway(getGateway("gw-2")))
                    .setCreatedAt(toTimestamp(Instant.now()))
                    .build(),
            )
            awaitCondition { tunnels.single().configs.size == 2 }
            assertEquals(listOf("gw-1", "gw-2"), tunnels.single().configs.last().state.gatewaysList.map { it.id })
        }

        run {
            cluster.send(
                Userv1.ConnectResponse.newBuilder()
                    .setUpdateDNS(
                        Userv1.ConnectResponse.UpdateDNS.newBuilder()
                            .setDns(Userv1.DNS.newBuilder().addServers("fdee:1::54"))
                    )
                    .build(),
            )
            awaitDomain { it.connection.dns.serversList == listOf("fdee:1::54") }
            awaitCondition { host.specs.size == 2 }
        }

        run {
            cluster.send(
                Userv1.ConnectResponse.newBuilder()
                    .setDeleteGateway(Userv1.ConnectResponse.DeleteGateway.newBuilder().setId("gw-1"))
                    .setCreatedAt(toTimestamp(Instant.now().minusSeconds(3600)))
                    .build(),
            )
            cluster.send(
                Userv1.ConnectResponse.newBuilder()
                    .setDeleteGateway(Userv1.ConnectResponse.DeleteGateway.newBuilder().setId("gw-2"))
                    .build(),
            )
            awaitCondition { tunnels.single().configs.size == 4 }
            assertEquals(listOf("gw-1"), tunnels.single().configs.last().state.gatewaysList.map { it.id })
        }

        run {
            val id = tunnels.single().requestAccessToken()
            awaitCondition { tunnels.single().responses.any { it.first == id } }
            assertEquals(
                TunnelResponse.GetAccessToken("access-1"),
                tunnels.single().responses.first { it.first == id }.second,
            )
        }

        run {
            c.setNetworkState(false, "")
            awaitCondition { tunnels.single().networkStates.last() == (false to "") }
        }

        run {
            val op = c.disconnect("example.com")
            assertEquals(Daemonv1.Operation.Type.DISCONNECT, op.type)

            val ret = awaitDomain {
                it.connection.state == ConnectionStatus.State.DISCONNECTED &&
                    it.lastOperation.state == Daemonv1.Operation.State.SUCCEEDED
            }
            assertFalse(ret.hasLastError())
            assertFalse(ret.connection.hasConnectedAt())
            assertTrue(ret.connection.addressesList.isEmpty())
            assertTrue(tunnels.single().isClosed)
            awaitCondition { cluster.getCalls("Disconnect").isNotEmpty() }
        }

        run {
            val op = c.disconnect("example.com")
            assertEquals(Daemonv1.Operation.State.SUCCEEDED, op.state)
        }

        c.close()
    }

    @Test
    fun testConnectOptions() = runBlocking {
        val c = getClient()
        authenticate(c)

        c.updateDomainSettings(
            "example.com",
            Daemonv1.DomainSettings.newBuilder()
                .setConnectionOptions(
                    Daemonv1.ConnectionOptions.newBuilder()
                        .setTunnelMode(Daemonv1.ConnectionOptions.TunnelMode.QUICV0)
                        .setL3Mode(Daemonv1.ConnectionOptions.L3Mode.BOTH)
                        .setDns(Daemonv1.ConnectionOptions.DNS.newBuilder().setMode(Daemonv1.ConnectionOptions.DNS.Mode.DISABLED))
                        .setMtu(1400)
                )
                .build(),
        )

        val st = connect(c)

        val init = cluster.initRequests.first().initialize
        assertEquals(Userv1.ConnectRequest.Initialize.L3Mode.BOTH, init.l3Mode)
        assertEquals(Userv1.ConnectRequest.Initialize.ConnectionType.QUICV0, init.connectionType)
        assertTrue(init.ignoreDNS)

        val prefs = tunnels.single().configs.single().preferences
        assertEquals(TunnelMode.QUICV0, prefs.tunnelMode)
        assertEquals(DNSMode.DISABLED, prefs.dnsMode)
        assertEquals(1400, prefs.mtu)

        assertEquals(Daemonv1.ConnectionOptions.TunnelMode.QUICV0, st.connection.tunnelMode)
        assertEquals(Daemonv1.ConnectionOptions.DNS.Mode.DISABLED, st.connection.dns.mode)
        assertFalse(st.connection.dns.isConfigured)

        c.close()
    }

    @Test
    fun testReconnect() = runBlocking {
        val c = getClient()
        authenticate(c)
        connect(c)

        cluster.sessions.last().close(StatusException(Status.UNAVAILABLE.withDescription("Stream reset")))

        awaitCondition { cluster.sessions.size == 2 }
        val st = awaitDomain { it.connection.state == ConnectionStatus.State.CONNECTED }

        assertEquals(1, tunnels.size)
        assertEquals(2, tunnels.single().configs.size)
        assertEquals(2, cluster.initRequests.count { it.hasInitialize() })
        assertEquals(Daemonv1.Operation.State.SUCCEEDED, st.lastOperation.state)

        run {
            cluster.send(
                Userv1.ConnectResponse.newBuilder()
                    .setDisconnect(Userv1.ConnectResponse.Disconnect.getDefaultInstance())
                    .build(),
            )

            val ret = awaitDomain { it.connection.state == ConnectionStatus.State.DISCONNECTED }
            assertFalse(ret.hasLastError())
            assertTrue(tunnels.single().isClosed)
        }

        run {
            connect(c)
            assertEquals(2, tunnels.size)
        }

        c.close()
    }

    @Test
    fun testConnectFailures() = runBlocking {
        val c = getClient()
        authenticate(c)

        run {
            host.err = IllegalStateException("The VPN permission is not granted")
            c.connect("example.com")

            val st = awaitDomain { it.hasLastError() }
            assertEquals(ConnectionStatus.State.CONNECTING, st.connection.state)
            assertEquals(Daemonv1.Error.Code.NETWORK_CONFIGURATION_FAILED, st.lastError.code)
            assertEquals("The VPN permission is not granted", st.lastError.message)
            assertTrue(st.lastError.retryable)

            host.err = null
            awaitDomain { it.connection.state == ConnectionStatus.State.CONNECTED && !it.hasLastError() }
        }

        run {
            tunnels.single().setStatus(TunnelStatus(TunnelState.RECONNECTING, TunnelError.TRANSPORT, "Timed out"))

            val st = awaitDomain { it.connection.state == ConnectionStatus.State.RECONNECTING }
            assertEquals(Daemonv1.Error.Code.CONNECTION_FAILED, st.lastError.code)
            assertEquals("Timed out", st.lastError.message)
            assertEquals(1, st.connection.addressesCount)

            tunnels.single().setStatus(TunnelStatus(TunnelState.CONNECTED))
            awaitDomain { it.connection.state == ConnectionStatus.State.CONNECTED && !it.hasLastError() }
        }

        run {
            tunnels.single().setStatus(
                TunnelStatus(TunnelState.RECONNECTING, TunnelError.UNAUTHENTICATED, "The access token is invalid"),
            )

            val st = awaitDomain { it.connection.state == ConnectionStatus.State.DISCONNECTED }
            assertEquals(Daemonv1.Error.Code.AUTHENTICATION_REQUIRED, st.lastError.code)
            assertEquals(Daemonv1.Operation.State.SUCCEEDED, st.lastOperation.state)
            assertTrue(tunnels.single().isClosed)
        }

        c.close()
    }

    @Test
    fun testSingleConnection() = runBlocking {
        val c = getClient()
        authenticate(c, "example.com")
        authenticate(c, "example.org")

        connect(c, "example.com")

        assertCode(Status.Code.FAILED_PRECONDITION) { c.connect("example.org") }

        c.disconnect("example.com")
        awaitDomain { it.connection.state == ConnectionStatus.State.DISCONNECTED }

        connect(c, "example.org")

        c.close()
    }

    @Test
    fun testRefresh() = runBlocking {
        cluster.expiresIn = 60

        val c = getClient()
        authenticate(c)

        val ret = c.getAPICredential("example.com")
        assertEquals("access-2", ret.accessToken)
        assertEquals(1, cluster.getCalls("AuthenticateWithRefreshToken").size)
        assertEquals("refresh-2", DB(stateDir, key).getSessionToken("example.com")?.refreshToken)

        run {
            cluster.refreshTokens.clear()
            assertCode(Status.Code.UNAUTHENTICATED) { c.getAPICredential("example.com") }

            val st = awaitDomain { it.authentication.state == AuthenticationStatus.State.LOGGED_OUT }
            assertEquals(Daemonv1.Error.Code.AUTHENTICATION_REQUIRED, st.lastError.code)
            assertNull(DB(stateDir, key).getSessionToken("example.com"))
        }

        c.close()
    }

    @Test
    fun testNetworkUnavailable() = runBlocking {
        cluster.expiresIn = 60

        val c = getClient()
        authenticate(c)

        c.setNetworkState(false, "")
        assertCode(Status.Code.UNAVAILABLE) { c.getAPICredential("example.com") }

        c.setNetworkState(true, "100")
        assertEquals("access-2", c.getAPICredential("example.com").accessToken)

        c.close()
    }

    @Test
    fun testLogout() = runBlocking {
        val c = getClient()
        authenticate(c)
        connect(c)

        val op = c.logout("example.com")
        assertEquals(Daemonv1.Operation.Type.LOGOUT, op.type)

        val st = awaitDomain {
            it.lastOperation.state == Daemonv1.Operation.State.SUCCEEDED &&
                it.authentication.state == AuthenticationStatus.State.LOGGED_OUT
        }
        assertEquals(ConnectionStatus.State.DISCONNECTED, st.connection.state)
        assertTrue(tunnels.single().isClosed)

        val logout = cluster.getCalls("Logout").single()
        assertEquals("refresh-1", logout.get(Metadata.Key.of(REFRESH_TOKEN_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)))
        assertNull(DB(stateDir, key).getSessionToken("example.com"))

        c.close()
    }

    @Test
    fun testDeleteDomain() = runBlocking {
        val c = getClient()
        authenticate(c)
        authenticate(c, "example.org")

        val op = c.deleteDomain("example.com")
        assertEquals(Daemonv1.Operation.Type.DELETE, op.type)

        awaitCondition { runBlocking { c.getStatus().domainsList.map { it.domain } } == listOf("example.org") }

        assertNull(DB(stateDir, key).get("example.com"))
        assertCode(Status.Code.NOT_FOUND) { c.getAPICredential("example.com") }
        assertEquals(Daemonv1.Operation.State.SUCCEEDED, c.getOperation(op.id).state)

        c.close()
    }

    @Test
    fun testSettings() = runBlocking {
        val dir = tmp.newFolder()

        run {
            val c = getClient(dir)

            val ret = c.updateDomainSettings(
                "Example.COM",
                Daemonv1.DomainSettings.newBuilder().setDomain("ignored").setAutoConnect(true).build(),
            )
            assertEquals("example.com", ret.domain)
            assertTrue(ret.autoConnect)

            val st = awaitDomain { it.settings.autoConnect }
            assertEquals(AuthenticationStatus.State.LOGGED_OUT, st.authentication.state)

            assertCode(Status.Code.INVALID_ARGUMENT) {
                c.updateDomainSettings(
                    "example.com",
                    Daemonv1.DomainSettings.newBuilder()
                        .setConnectionOptions(
                            Daemonv1.ConnectionOptions.newBuilder()
                                .setImplementationMode(Daemonv1.ConnectionOptions.ImplementationMode.KERNEL)
                        )
                        .build(),
                )
            }

            c.close()

            assertCode(Status.Code.UNAVAILABLE) { c.getStatus() }
        }

        run {
            val c = getClient(dir)
            val st = c.getStatus().domainsList.single()
            assertEquals("example.com", st.domain)
            assertTrue(st.settings.autoConnect)
            c.close()
        }
    }
}
