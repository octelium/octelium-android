package com.octelium.client.core.client

import com.octelium.client.core.auth.AuthenticationRequiredException
import com.octelium.client.core.tunnel.DNSMode
import com.octelium.client.core.tunnel.TunnelError
import com.octelium.client.core.tunnel.TunnelException
import com.octelium.client.core.tunnel.TunnelMode
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CancellationException
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.daemon.v1.Daemonv1.ConnectionOptions
import octelium.api.main.user.v1.Userv1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OptionsTest {

    private fun assertInvalid(msg: String, fn: () -> Unit) {
        try {
            fn()
            fail()
        } catch (err: StatusException) {
            assertEquals(Status.Code.INVALID_ARGUMENT, err.status.code)
            assertEquals(msg, err.status.description)
        }
    }

    @Test
    fun testCanonicalizeDomain() {
        assertEquals("example.com", canonicalizeDomain("example.com"))
        assertEquals("example.com", canonicalizeDomain(" Example.COM. "))
        assertEquals("xn--mnchen-3ya.de", canonicalizeDomain("münchen.de"))

        assertInvalid("The Cluster domain is not set") { canonicalizeDomain(" ") }
        assertInvalid("Invalid Cluster domain: example") { canonicalizeDomain("example") }
        assertInvalid("Invalid Cluster domain: 192.168.1.1") { canonicalizeDomain("192.168.1.1") }
        assertInvalid("Invalid Cluster domain: ::1") { canonicalizeDomain("::1") }
        assertInvalid("Invalid Cluster domain: not a domain") { canonicalizeDomain("not a domain") }
        assertInvalid("Invalid Cluster domain: -a.example.com") { canonicalizeDomain("-a.example.com") }
        assertInvalid("Invalid Cluster domain: ${"a".repeat(64)}.com") { canonicalizeDomain("${"a".repeat(64)}.com") }
    }

    @Test
    fun testGetConnectOptions() {
        run {
            val ret = getConnectOptions(null)
            assertEquals(Userv1.ConnectRequest.Initialize.L3Mode.V6, ret.l3Mode)
            assertEquals(TunnelMode.WIREGUARD, ret.tunnelMode)
            assertEquals(DNSMode.DEFAULT, ret.dnsMode)
            assertEquals(0, ret.mtu)
        }

        run {
            val ret = getConnectOptions(
                ConnectionOptions.newBuilder()
                    .setL3Mode(ConnectionOptions.L3Mode.BOTH)
                    .setTunnelMode(ConnectionOptions.TunnelMode.QUICV0)
                    .setImplementationMode(ConnectionOptions.ImplementationMode.TUN)
                    .setDns(ConnectionOptions.DNS.newBuilder().setMode(ConnectionOptions.DNS.Mode.FULL))
                    .setMtu(1400)
                    .build(),
            )
            assertEquals(Userv1.ConnectRequest.Initialize.L3Mode.BOTH, ret.l3Mode)
            assertEquals(TunnelMode.QUICV0, ret.tunnelMode)
            assertEquals(DNSMode.FULL, ret.dnsMode)
            assertEquals(1400, ret.mtu)
        }

        assertInvalid("Unsupported implementationMode on this platform: 1") {
            getConnectOptions(
                ConnectionOptions.newBuilder().setImplementationMode(ConnectionOptions.ImplementationMode.KERNEL).build(),
            )
        }

        assertInvalid("The local DNS server is not supported on this platform") {
            getConnectOptions(
                ConnectionOptions.newBuilder().setDns(ConnectionOptions.DNS.newBuilder().setEnableLocalServer(true)).build(),
            )
        }

        assertInvalid("Serving and publishing Services are not supported on this platform") {
            getConnectOptions(
                ConnectionOptions.newBuilder()
                    .setServiceOptions(ConnectionOptions.ServiceOptions.newBuilder().setEnableEmbeddedSSH(true))
                    .build(),
            )
        }

        assertInvalid("The MTU must be between 576 and 1500") {
            getConnectOptions(ConnectionOptions.newBuilder().setMtu(9000).build())
        }

        assertInvalid("Unsupported tunnelMode: 7") {
            getConnectOptions(ConnectionOptions.newBuilder().setTunnelModeValue(7).build())
        }
    }

    @Test
    fun testNormalizeConnectionOptions() {
        assertEquals(ConnectionOptions.DNS.Mode.DEFAULT, normalizeConnectionOptions(null).dns.mode)
        assertEquals(
            ConnectionOptions.DNS.Mode.FULL,
            normalizeConnectionOptions(
                ConnectionOptions.newBuilder().setDns(ConnectionOptions.DNS.newBuilder().setMode(ConnectionOptions.DNS.Mode.FULL)).build(),
            ).dns.mode,
        )
    }

    @Test
    fun testGetError() {
        run {
            val ret = getError(AuthenticationRequiredException(), Daemonv1.Error.Code.CONNECTION_FAILED)
            assertEquals(Daemonv1.Error.Code.AUTHENTICATION_REQUIRED, ret.code)
            assertFalse(ret.retryable)
            assertEquals(
                "Interactive authentication is not available in this mode. Please authenticate yourself first",
                ret.message,
            )
        }

        run {
            val ret = getError(
                StatusException(Status.UNAVAILABLE.withDescription("unreachable")),
                Daemonv1.Error.Code.AUTHENTICATION_FAILED,
            )
            assertEquals(Daemonv1.Error.Code.CLUSTER_UNREACHABLE, ret.code)
            assertTrue(ret.retryable)
            assertEquals("unreachable", ret.message)
        }

        run {
            val ret = getError(
                IllegalStateException("wrapped", StatusException(Status.UNAUTHENTICATED)),
                Daemonv1.Error.Code.CONNECTION_FAILED,
            )
            assertEquals(Daemonv1.Error.Code.AUTHENTICATION_REQUIRED, ret.code)
            assertEquals("wrapped", ret.message)
        }

        run {
            val ret = getError(TunnelException(TunnelError.PLATFORM, "failed"), Daemonv1.Error.Code.CONNECTION_FAILED)
            assertEquals(Daemonv1.Error.Code.NETWORK_CONFIGURATION_FAILED, ret.code)
            assertTrue(ret.retryable)
        }

        run {
            val ret = getError(CancellationException("canceled"), Daemonv1.Error.Code.AUTHENTICATION_FAILED)
            assertEquals(Daemonv1.Error.Code.OPERATION_CANCELED, ret.code)
        }

        run {
            val ret = getError(IllegalStateException("failed"), Daemonv1.Error.Code.CONNECTION_FAILED)
            assertEquals(Daemonv1.Error.Code.CONNECTION_FAILED, ret.code)
            assertTrue(ret.retryable)
        }
    }
}
