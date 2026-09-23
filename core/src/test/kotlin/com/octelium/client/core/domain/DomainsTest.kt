package com.octelium.client.core.domain

import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.daemon.v1.Daemonv1.AuthenticationStatus
import octelium.api.client.daemon.v1.Daemonv1.ConnectionOptions
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import octelium.api.client.daemon.v1.Daemonv1.DomainState
import octelium.api.client.daemon.v1.Daemonv1.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun getTestDomain(
    domain: String = "example.com",
    auth: AuthenticationStatus.State = AuthenticationStatus.State.LOGGED_OUT,
    conn: ConnectionStatus.State = ConnectionStatus.State.DISCONNECTED,
    op: Operation? = null,
    autoConnect: Boolean = false,
): DomainState {
    val ret = DomainState.newBuilder()
        .setDomain(domain)
        .setAuthentication(AuthenticationStatus.newBuilder().setState(auth))
        .setConnection(ConnectionStatus.newBuilder().setState(conn))
        .setSettings(Daemonv1.DomainSettings.newBuilder().setDomain(domain).setAutoConnect(autoConnect))

    if (op != null) {
        ret.setLastOperation(op)
    }

    return ret.build()
}

fun getTestOperation(
    type: Operation.Type = Operation.Type.CONNECT,
    state: Operation.State = Operation.State.RUNNING,
    id: String = "op",
    url: String? = null,
): Operation {
    val ret = Operation.newBuilder()
        .setId(id)
        .setType(type)
        .setState(state)

    if (url != null) {
        ret.setAction(Daemonv1.Action.newBuilder().setOpenURL(Daemonv1.Action.OpenURL.newBuilder().setUrl(url)))
    }

    return ret.build()
}

fun getTestStatus(vararg domains: DomainState, revision: Long = 1, instanceID: String = "instance"): Daemonv1.GetStatusResponse =
    Daemonv1.GetStatusResponse.newBuilder()
        .setInstanceID(instanceID)
        .setRevision(revision)
        .addAllDomains(domains.toList())
        .build()

class DomainsTest {

    @Test
    fun testIsOperationActive() {
        run {
            assertFalse(isOperationActive(null))
        }
        run {
            assertTrue(isOperationActive(getTestOperation(state = Operation.State.PENDING)))
        }
        run {
            assertTrue(isOperationActive(getTestOperation(state = Operation.State.RUNNING)))
        }
        run {
            assertTrue(isOperationActive(getTestOperation(state = Operation.State.WAITING_FOR_USER)))
        }
        run {
            assertFalse(isOperationActive(getTestOperation(state = Operation.State.SUCCEEDED)))
        }
        run {
            assertFalse(isOperationActive(getTestOperation(state = Operation.State.FAILED)))
        }
        run {
            assertFalse(isOperationActive(getTestOperation(state = Operation.State.CANCELED)))
        }
    }

    @Test
    fun testGetActiveOperation() {
        run {
            assertNull(getActiveOperation(null))
        }
        run {
            assertNull(getActiveOperation(getTestDomain()))
        }
        run {
            val op = getTestOperation(state = Operation.State.RUNNING)
            assertEquals(op, getActiveOperation(getTestDomain(op = op)))
        }
        run {
            assertNull(getActiveOperation(getTestDomain(op = getTestOperation(state = Operation.State.SUCCEEDED))))
        }
    }

    @Test
    fun testGetPendingOpenURL() {
        run {
            val ret = getPendingOpenURL(
                getTestDomain(
                    op = getTestOperation(
                        type = Operation.Type.AUTHENTICATE,
                        state = Operation.State.WAITING_FOR_USER,
                        url = "https://example.com/login",
                    ),
                ),
            )
            assertEquals("https://example.com/login", ret)
        }
        run {
            val ret = getPendingOpenURL(
                getTestDomain(
                    op = getTestOperation(
                        type = Operation.Type.AUTHENTICATE,
                        state = Operation.State.RUNNING,
                        url = "https://example.com/login",
                    ),
                ),
            )
            assertNull(ret)
        }
        run {
            val ret = getPendingOpenURL(
                getTestDomain(
                    op = getTestOperation(type = Operation.Type.AUTHENTICATE, state = Operation.State.WAITING_FOR_USER),
                ),
            )
            assertNull(ret)
        }
        run {
            assertNull(getPendingOpenURL(null))
        }
    }

    @Test
    fun testIsConnectionBusy() {
        run {
            assertFalse(isConnectionBusy(null))
        }
        run {
            assertTrue(isConnectionBusy(getTestDomain(conn = ConnectionStatus.State.CONNECTING)))
        }
        run {
            assertTrue(isConnectionBusy(getTestDomain(conn = ConnectionStatus.State.RECONNECTING)))
        }
        run {
            assertTrue(isConnectionBusy(getTestDomain(conn = ConnectionStatus.State.DISCONNECTING)))
        }
        run {
            assertFalse(isConnectionBusy(getTestDomain(conn = ConnectionStatus.State.CONNECTED)))
        }
        run {
            assertFalse(isConnectionBusy(getTestDomain(conn = ConnectionStatus.State.DISCONNECTED)))
        }
    }

    @Test
    fun testIsConnectionActive() {
        run {
            assertFalse(isConnectionActive(null))
        }
        run {
            assertFalse(isConnectionActive(getTestDomain(conn = ConnectionStatus.State.DISCONNECTED)))
        }
        for (state in listOf(
            ConnectionStatus.State.CONNECTING,
            ConnectionStatus.State.CONNECTED,
            ConnectionStatus.State.RECONNECTING,
            ConnectionStatus.State.DISCONNECTING,
        )) {
            assertTrue(isConnectionActive(getTestDomain(conn = state)))
        }
    }

    @Test
    fun testCanConnect() {
        run {
            assertFalse(canConnect(null))
        }
        run {
            assertFalse(canConnect(getTestDomain()))
        }
        run {
            assertTrue(canConnect(getTestDomain(auth = AuthenticationStatus.State.AUTHENTICATED)))
        }
        run {
            assertFalse(
                canConnect(
                    getTestDomain(
                        auth = AuthenticationStatus.State.AUTHENTICATED,
                        conn = ConnectionStatus.State.CONNECTED,
                    ),
                ),
            )
        }
        run {
            assertFalse(
                canConnect(
                    getTestDomain(
                        auth = AuthenticationStatus.State.AUTHENTICATED,
                        conn = ConnectionStatus.State.DISCONNECTING,
                    ),
                ),
            )
        }
        run {
            assertFalse(
                canConnect(
                    getTestDomain(
                        auth = AuthenticationStatus.State.AUTHENTICATED,
                        op = getTestOperation(type = Operation.Type.LOGOUT, state = Operation.State.RUNNING),
                    ),
                ),
            )
        }
        run {
            assertTrue(
                canConnect(
                    getTestDomain(
                        auth = AuthenticationStatus.State.AUTHENTICATED,
                        op = getTestOperation(type = Operation.Type.CONNECT, state = Operation.State.FAILED),
                    ),
                ),
            )
        }
    }

    @Test
    fun testCanDisconnect() {
        run {
            assertFalse(canDisconnect(null))
        }
        run {
            assertFalse(canDisconnect(getTestDomain()))
        }
        run {
            assertTrue(canDisconnect(getTestDomain(conn = ConnectionStatus.State.CONNECTED)))
        }
        run {
            assertTrue(canDisconnect(getTestDomain(conn = ConnectionStatus.State.CONNECTING)))
        }
        run {
            assertTrue(canDisconnect(getTestDomain(conn = ConnectionStatus.State.RECONNECTING)))
        }
        run {
            assertFalse(canDisconnect(getTestDomain(conn = ConnectionStatus.State.DISCONNECTING)))
        }
        run {
            assertFalse(
                canDisconnect(
                    getTestDomain(
                        conn = ConnectionStatus.State.CONNECTED,
                        op = getTestOperation(type = Operation.Type.DISCONNECT, state = Operation.State.RUNNING),
                    ),
                ),
            )
        }
        run {
            assertTrue(
                canDisconnect(
                    getTestDomain(op = getTestOperation(type = Operation.Type.CONNECT, state = Operation.State.RUNNING)),
                ),
            )
        }
    }

    @Test
    fun testIsTeardownOperation() {
        assertFalse(isTeardownOperation(null))
        assertFalse(isTeardownOperation(getTestOperation(type = Operation.Type.CONNECT)))
        assertFalse(isTeardownOperation(getTestOperation(type = Operation.Type.AUTHENTICATE)))
        assertTrue(isTeardownOperation(getTestOperation(type = Operation.Type.DISCONNECT)))
        assertTrue(isTeardownOperation(getTestOperation(type = Operation.Type.LOGOUT)))
        assertTrue(isTeardownOperation(getTestOperation(type = Operation.Type.DELETE)))
    }

    @Test
    fun testLabels() {
        assertEquals("Connected", getConnectionStateLabel(ConnectionStatus.State.CONNECTED))
        assertEquals("Reconnecting", getConnectionStateLabel(ConnectionStatus.State.RECONNECTING))
        assertEquals("Disconnected", getConnectionStateLabel(null))
        assertEquals(ConnectivityTone.CONNECTED, getConnectionStateTone(ConnectionStatus.State.CONNECTED))
        assertEquals(ConnectivityTone.PENDING, getConnectionStateTone(ConnectionStatus.State.CONNECTING))
        assertEquals(ConnectivityTone.IDLE, getConnectionStateTone(ConnectionStatus.State.DISCONNECTED))

        assertEquals("Signed in", getAuthenticationStateLabel(AuthenticationStatus.State.AUTHENTICATED))
        assertEquals("Signed out", getAuthenticationStateLabel(AuthenticationStatus.State.LOGGED_OUT))
        assertEquals(LabelTone.EMERALD, getAuthenticationStateTone(AuthenticationStatus.State.AUTHENTICATED))
        assertEquals(LabelTone.AMBER, getAuthenticationStateTone(AuthenticationStatus.State.LOGGING_OUT))
        assertEquals(LabelTone.SLATE, getAuthenticationStateTone(null))

        assertEquals("Signing in", getOperationTypeLabel(Operation.Type.AUTHENTICATE))
        assertEquals("Removing", getOperationTypeLabel(Operation.Type.DELETE))
        assertEquals("Working", getOperationTypeLabel(null))

        assertEquals("WireGuard", getTunnelModeLabel(ConnectionOptions.TunnelMode.WIREGUARD))
        assertEquals("QUIC", getTunnelModeLabel(ConnectionOptions.TunnelMode.QUICV0))
        assertEquals("Automatic", getTunnelModeLabel(ConnectionOptions.TunnelMode.TUNNEL_MODE_UNSPECIFIED))

        assertEquals("TUN", getImplementationModeLabel(ConnectionOptions.ImplementationMode.TUN))
        assertEquals("Automatic", getImplementationModeLabel(null))

        assertEquals("Dual stack", getL3ModeLabel(ConnectionOptions.L3Mode.BOTH))
        assertEquals("IPv6 only", getL3ModeLabel(ConnectionOptions.L3Mode.V6))

        assertEquals("Split", getDNSModeLabel(ConnectionOptions.DNS.Mode.DEFAULT))
        assertEquals("Split", getDNSModeLabel(ConnectionOptions.DNS.Mode.MODE_UNSPECIFIED))
        assertEquals("Full", getDNSModeLabel(ConnectionOptions.DNS.Mode.FULL))
        assertEquals("Disabled", getDNSModeLabel(ConnectionOptions.DNS.Mode.DISABLED))
    }

    @Test
    fun testGetErrorTitle() {
        run {
            assertEquals("Something went wrong", getErrorTitle(null))
        }
        run {
            val err = Daemonv1.Error.newBuilder().setCode(Daemonv1.Error.Code.CLUSTER_UNREACHABLE).build()
            assertEquals("Cluster unreachable", getErrorTitle(err))
            assertEquals(
                "Check your Internet connection and that the Cluster domain is correct.",
                getErrorHint(err),
            )
        }
        run {
            val err = Daemonv1.Error.newBuilder().setCode(Daemonv1.Error.Code.AUTHENTICATION_REQUIRED).build()
            assertEquals("Sign in required", getErrorTitle(err))
        }
        run {
            assertNull(getErrorHint(Daemonv1.Error.newBuilder().setCode(Daemonv1.Error.Code.INTERNAL).build()))
        }
    }

    @Test
    fun testIsErrorRetryable() {
        run {
            assertFalse(isErrorRetryable(null))
        }
        run {
            val err = Daemonv1.Error.newBuilder()
                .setCode(Daemonv1.Error.Code.CLUSTER_UNREACHABLE)
                .setRetryable(true)
                .build()
            assertTrue(isErrorRetryable(err))
        }
        run {
            val err = Daemonv1.Error.newBuilder()
                .setCode(Daemonv1.Error.Code.OPERATION_CANCELED)
                .setRetryable(true)
                .build()
            assertFalse(isErrorRetryable(err))
        }
        run {
            val err = Daemonv1.Error.newBuilder().setCode(Daemonv1.Error.Code.AUTHENTICATION_FAILED).build()
            assertFalse(isErrorRetryable(err))
        }
    }

    @Test
    fun testGetDomainState() {
        val status = getTestStatus(getTestDomain("a.example.com"), getTestDomain("b.example.com"))

        assertNull(getDomainState(null, "a.example.com"))
        assertNull(getDomainState(status, null))
        assertNull(getDomainState(status, ""))
        assertNull(getDomainState(status, "c.example.com"))
        assertEquals("b.example.com", getDomainState(status, "b.example.com")?.domain)
        assertEquals(listOf("a.example.com", "b.example.com"), getDomains(status))
    }

    @Test
    fun testSelectDomain() {
        run {
            assertNull(selectDomain(null, null))
        }
        run {
            assertNull(selectDomain(getTestStatus(), "example.com"))
        }
        run {
            val status = getTestStatus(getTestDomain("b.example.com"), getTestDomain("a.example.com"))
            assertEquals("a.example.com", selectDomain(status, null))
            assertEquals("b.example.com", selectDomain(status, "b.example.com"))
            assertEquals("a.example.com", selectDomain(status, "c.example.com"))
        }
        run {
            val status = getTestStatus(
                getTestDomain("a.example.com"),
                getTestDomain("b.example.com", auth = AuthenticationStatus.State.AUTHENTICATED),
            )
            assertEquals("b.example.com", selectDomain(status, null))
        }
        run {
            val status = getTestStatus(
                getTestDomain("a.example.com", auth = AuthenticationStatus.State.AUTHENTICATED),
                getTestDomain("b.example.com", conn = ConnectionStatus.State.CONNECTED),
            )
            assertEquals("b.example.com", selectDomain(status, null))
        }
    }

    @Test
    fun testResolveSelectedDomain() {
        val status = getTestStatus(getTestDomain("a.example.com"), getTestDomain("b.example.com"))

        assertEquals("c.example.com", resolveSelectedDomain("c.example.com", status, "a.example.com"))
        assertEquals("b.example.com", resolveSelectedDomain(null, status, "b.example.com"))
        assertEquals("a.example.com", resolveSelectedDomain(null, status, null))
        assertNull(resolveSelectedDomain(null, null, "a.example.com"))
    }

    @Test
    fun testGetTunnelDomainState() {
        run {
            assertNull(getTunnelDomainState(null))
        }
        run {
            assertNull(getTunnelDomainState(getTestStatus(getTestDomain("a.example.com"))))
        }
        run {
            val status = getTestStatus(
                getTestDomain("a.example.com"),
                getTestDomain("b.example.com", conn = ConnectionStatus.State.RECONNECTING),
            )
            assertEquals("b.example.com", getTunnelDomainState(status)?.domain)
        }
    }

    @Test
    fun testValidateDomain() {
        assertEquals("The Cluster domain is required", validateDomain(""))
        assertEquals("The Cluster domain is required", validateDomain("   "))
        assertEquals("Invalid Cluster domain", validateDomain("example"))
        assertEquals("Invalid Cluster domain", validateDomain("-example.com"))
        assertEquals("Invalid Cluster domain", validateDomain("exa mple.com"))
        assertEquals("Invalid Cluster domain", validateDomain("example..com"))
        assertEquals("The Cluster domain is too long", validateDomain("a".repeat(250) + ".com"))
        assertEquals("A Cluster domain label is too long", validateDomain("a".repeat(64) + ".com"))
        assertNull(validateDomain("example.com"))
        assertNull(validateDomain("Example.COM"))
        assertNull(validateDomain("sub-1.example.com"))
    }

    @Test
    fun testNormalizeDomain() {
        assertEquals("example.com", normalizeDomain("example.com"))
        assertEquals("example.com", normalizeDomain(" Example.COM "))
        assertEquals("example.com", normalizeDomain("https://example.com/"))
        assertEquals("example.com", normalizeDomain("https://example.com/login?x=1#y"))
        assertEquals("example.com", normalizeDomain("example.com."))
        assertEquals("example.com", normalizeDomain("user@example.com"))
        assertEquals("example.com", normalizeDomain("example.com:443"))
        assertEquals("xn--bcher-kva.example", normalizeDomain("bücher.example"))
        assertEquals("", normalizeDomain("  "))
    }
}
