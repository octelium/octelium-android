package com.octelium.client.core.tunnel

import com.octelium.client.core.domain.getTestDomain
import com.octelium.client.core.domain.getTestOperation
import com.octelium.client.core.domain.getTestStatus
import octelium.api.client.daemon.v1.Daemonv1.AuthenticationStatus
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import octelium.api.client.daemon.v1.Daemonv1.Operation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleTest {

    private val authenticated = AuthenticationStatus.State.AUTHENTICATED

    @Test
    fun testShouldKeepTunnelService() {
        run {
            assertFalse(shouldKeepTunnelService(null, 0))
            assertTrue(shouldKeepTunnelService(null, 1))
        }
        run {
            assertFalse(shouldKeepTunnelService(getTestStatus(getTestDomain()), 0))
        }
        run {
            val status = getTestStatus(getTestDomain(conn = ConnectionStatus.State.CONNECTED))
            assertTrue(shouldKeepTunnelService(status, 0))
        }
        run {
            val status = getTestStatus(getTestDomain(conn = ConnectionStatus.State.DISCONNECTING))
            assertTrue(shouldKeepTunnelService(status, 0))
        }
        run {
            val status = getTestStatus(
                getTestDomain(op = getTestOperation(type = Operation.Type.CONNECT, state = Operation.State.RUNNING)),
            )
            assertTrue(shouldKeepTunnelService(status, 0))
        }
        run {
            val status = getTestStatus(
                getTestDomain(op = getTestOperation(type = Operation.Type.CONNECT, state = Operation.State.FAILED)),
            )
            assertFalse(shouldKeepTunnelService(status, 0))
        }
        run {
            val status = getTestStatus(
                getTestDomain(op = getTestOperation(type = Operation.Type.AUTHENTICATE, state = Operation.State.RUNNING)),
            )
            assertFalse(shouldKeepTunnelService(status, 0))
        }
    }

    @Test
    fun testIsTunnelReleased() {
        run {
            assertFalse(isTunnelReleased(null, "example.com"))
            assertFalse(isTunnelReleased(getTestStatus(), null))
        }
        run {
            assertTrue(isTunnelReleased(getTestStatus(), "example.com"))
        }
        run {
            val status = getTestStatus(getTestDomain(conn = ConnectionStatus.State.DISCONNECTED))
            assertTrue(isTunnelReleased(status, "example.com"))
        }
        run {
            val status = getTestStatus(getTestDomain(conn = ConnectionStatus.State.RECONNECTING))
            assertFalse(isTunnelReleased(status, "example.com"))
        }
        run {
            val status = getTestStatus(getTestDomain(conn = ConnectionStatus.State.DISCONNECTING))
            assertFalse(isTunnelReleased(status, "example.com"))
        }
    }

    @Test
    fun testGetAlwaysOnDomain() {
        run {
            assertNull(getAlwaysOnDomain(null, null))
            assertNull(getAlwaysOnDomain(getTestStatus(getTestDomain()), "example.com"))
        }
        run {
            val status = getTestStatus(
                getTestDomain("b.example.com", auth = authenticated),
                getTestDomain("a.example.com", auth = authenticated),
                getTestDomain("c.example.com", auth = authenticated, autoConnect = true),
            )
            assertEquals("b.example.com", getAlwaysOnDomain(status, "b.example.com"))
            assertEquals("c.example.com", getAlwaysOnDomain(status, null))
            assertEquals("c.example.com", getAlwaysOnDomain(status, "d.example.com"))
        }
        run {
            val status = getTestStatus(
                getTestDomain("b.example.com", auth = authenticated),
                getTestDomain("a.example.com", auth = authenticated),
                getTestDomain("c.example.com"),
            )
            assertEquals("a.example.com", getAlwaysOnDomain(status, "c.example.com"))
        }
    }

    @Test
    fun testGetAutoConnectDomain() {
        run {
            assertNull(getAutoConnectDomain(null, null))
        }
        run {
            val status = getTestStatus(getTestDomain("a.example.com", auth = authenticated))
            assertNull(getAutoConnectDomain(status, "a.example.com"))
        }
        run {
            val status = getTestStatus(
                getTestDomain("a.example.com", auth = authenticated, autoConnect = true),
                getTestDomain("b.example.com", auth = authenticated, autoConnect = true),
                getTestDomain("c.example.com", autoConnect = true),
            )
            assertEquals("a.example.com", getAutoConnectDomain(status, null))
            assertEquals("b.example.com", getAutoConnectDomain(status, "b.example.com"))
            assertEquals("a.example.com", getAutoConnectDomain(status, "c.example.com"))
        }
        run {
            val status = getTestStatus(
                getTestDomain("a.example.com", auth = authenticated, autoConnect = true),
                getTestDomain("b.example.com", auth = authenticated, conn = ConnectionStatus.State.CONNECTED),
            )
            assertNull(getAutoConnectDomain(status, "a.example.com"))
        }
    }
}
