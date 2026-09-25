package com.octelium.client.lib

import com.google.protobuf.ByteString
import com.octelium.client.core.local.LogEntry
import com.octelium.client.core.local.LogLevel
import com.octelium.client.core.tunnel.DNSMode
import com.octelium.client.core.tunnel.NetworkConfig
import com.octelium.client.core.tunnel.TunnelConfig
import com.octelium.client.core.tunnel.TunnelError
import com.octelium.client.core.tunnel.TunnelException
import com.octelium.client.core.tunnel.TunnelHandler
import com.octelium.client.core.tunnel.TunnelMode
import com.octelium.client.core.tunnel.TunnelPreferences
import com.octelium.client.core.tunnel.TunnelRequest
import com.octelium.client.core.tunnel.TunnelResponse
import com.octelium.client.core.tunnel.TunnelState
import com.octelium.client.core.tunnel.TunnelStatus
import octelium.api.main.meta.v1.Metav1
import octelium.api.main.user.v1.Userv1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LibOcteliumTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val libDir = File(System.getProperty("octelium.hostLibDir").orEmpty())

    private class Handler : TunnelHandler {
        val statuses = LinkedBlockingQueue<TunnelStatus>()
        val logs = LinkedBlockingQueue<LogEntry>()
        val requests = LinkedBlockingQueue<Pair<Long, TunnelRequest>>()

        override fun onStatus(status: TunnelStatus) {
            statuses.add(status)
        }

        override fun onLog(log: LogEntry) {
            logs.add(log)
        }

        override fun onRequest(requestID: Long, request: TunnelRequest) {
            requests.add(requestID to request)
        }

        fun awaitStatus(fn: (TunnelStatus) -> Boolean): TunnelStatus {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val ret = statuses.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (fn(ret)) {
                    return ret
                }
            }

            throw AssertionError("Timed out waiting for the status")
        }

        fun awaitRequest(): Pair<Long, TunnelRequest> =
            requests.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("Timed out waiting for the request")
    }

    @Before
    fun setUp() {
        val lib = File(libDir, "liboctelium.so")
        val jni = File(libDir, "liboctelium_jni.so")
        assumeTrue("The host libraries are not built", lib.exists() && jni.exists())

        LibOctelium.load(lib.path) {
            System.load(jni.path)
        }
    }

    private fun getGateway(id: String, tunnelMode: TunnelMode): Userv1.Gateway {
        val ret = Userv1.Gateway.newBuilder()
            .setId(id)
            .addAddresses("127.0.0.1")
            .addCIDRs("10.100.0.0/16")
            .addCIDRs("fdee:100::/64")

        if (tunnelMode == TunnelMode.QUICV0) {
            ret.setQuicv0(Userv1.Gateway.QUICV0.newBuilder().setPort(1))
        } else {
            ret.setWireguard(
                Userv1.Gateway.WireGuard.newBuilder()
                    .setPort(51820)
                    .setPublicKey("AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=")
            )
        }

        return ret.build()
    }

    private fun getConfig(
        tunnelMode: TunnelMode = TunnelMode.WIREGUARD,
        key: ByteArray? = ByteArray(32) { 1 },
    ): TunnelConfig {
        val state = Userv1.ConnectionState.newBuilder()
            .setMtu(1400)
            .setL3Mode(Userv1.ConnectionState.L3Mode.BOTH)
            .addAddresses(Metav1.DualStackNetwork.newBuilder().setV4("10.200.0.2/32").setV6("fdee:200::2/128"))
            .addGateways(getGateway("gw-1", tunnelMode))
            .setDns(Userv1.DNS.newBuilder().addServers("fdee:100::53"))
            .setCidr(Metav1.DualStackNetwork.newBuilder().setV4("10.100.0.0/16").setV6("fdee:100::/64"))

        key?.let { state.setX25519Key(ByteString.copyFrom(it)) }

        return TunnelConfig(
            domain = "example.com",
            state = state.build(),
            preferences = TunnelPreferences(tunnelMode = tunnelMode, dnsMode = DNSMode.DEFAULT),
        )
    }

    private fun getFD(file: RandomAccessFile): Int {
        val field = FileDescriptor::class.java.getDeclaredField("fd")
        field.isAccessible = true
        return field.getInt(file.fd)
    }

    private fun openFIFO(): RandomAccessFile {
        val path = File(tmp.root, "tun")
        assertEquals(0, ProcessBuilder("mkfifo", path.path).start().waitFor())
        return RandomAccessFile(path, "rw")
    }

    @Test
    fun testABI() {
        assertEquals(ABI_VERSION_MAJOR, Native.abiVersion() ushr 16)
        assertEquals(Native.hostABIVersion(), Native.abiVersion())
        assertTrue(LibOctelium.getVersion().isNotEmpty())
        assertEquals("1.0", formatABIVersion(Native.abiVersion()))
    }

    @Test
    fun testWireGuard() {
        val handler = Handler()
        val lib = LibOctelium.create(handler, LogLevel.DEBUG)

        try {
            lib.setConfig(getConfig(key = null))
            fail()
        } catch (err: TunnelException) {
            assertEquals(TunnelError.INVALID_ARGUMENT, err.error)
            assertTrue(err.message!!.isNotEmpty())
        }

        lib.setConfig(getConfig())

        val (id, req) = handler.awaitRequest()
        assertTrue(req is TunnelRequest.ApplyNetworkConfig)

        val cfg: NetworkConfig = (req as TunnelRequest.ApplyNetworkConfig).config
        assertEquals(listOf("10.200.0.2/32", "fdee:200::2/128"), cfg.addresses)
        assertEquals(listOf("10.100.0.0/16", "fdee:100::/64"), cfg.routes)
        assertEquals(1400, cfg.mtu)
        assertEquals(listOf("fdee:100::53"), cfg.dns?.servers)
        assertTrue(cfg.dns!!.searchDomains.contains("local.example.com"))
        assertTrue(cfg.generation > 0)

        assertEquals(TunnelError.NOT_FOUND.code, lib.complete(id + 1000, TunnelResponse.ApplyNetworkConfig(-1)))

        openFIFO().use { fifo ->
            assertEquals(0, lib.complete(id, TunnelResponse.ApplyNetworkConfig(getFD(fifo))))
        }

        handler.awaitStatus { it.state == TunnelState.CONNECTED }

        lib.setNetworkState(false, "")
        handler.awaitStatus { it.state == TunnelState.RECONNECTING }

        lib.setNetworkState(true, "100")
        handler.awaitStatus { it.state == TunnelState.CONNECTED }

        assertTrue(handler.logs.any { it.level == LogLevel.DEBUG })

        lib.close()
        lib.close()

        assertEquals(TunnelError.NOT_FOUND.code, lib.complete(id, TunnelResponse.ApplyNetworkConfig(-1)))

        try {
            lib.setConfig(getConfig())
            fail()
        } catch (err: TunnelException) {
            assertEquals(TunnelError.INVALID_STATE, err.error)
        }
    }

    @Test
    fun testQUICV0() {
        val handler = Handler()
        val lib = LibOctelium.create(handler, LogLevel.DEBUG)

        lib.setConfig(getConfig(tunnelMode = TunnelMode.QUICV0, key = null))

        val (applyID, apply) = handler.awaitRequest()
        assertTrue(apply is TunnelRequest.ApplyNetworkConfig)

        openFIFO().use { fifo ->
            assertEquals(0, lib.complete(applyID, TunnelResponse.ApplyNetworkConfig(getFD(fifo))))
        }

        val (tokenID, token) = handler.awaitRequest()
        assertEquals(TunnelRequest.GetAccessToken, token)

        assertEquals(
            0,
            lib.complete(tokenID, TunnelResponse.Error(TunnelError.UNAUTHENTICATED, "Authentication is required")),
        )

        val st = handler.awaitStatus { it.error == TunnelError.UNAUTHENTICATED }
        assertEquals(TunnelState.CONNECTING, st.state)

        lib.close()
    }

    @Test
    fun testPlatformError() {
        val handler = Handler()
        val lib = LibOctelium.create(handler)

        lib.setConfig(getConfig())

        val (id, _) = handler.awaitRequest()
        assertEquals(0, lib.complete(id, TunnelResponse.Error(TunnelError.PLATFORM, "The VPN permission is not granted")))

        val st = handler.awaitStatus { it.state == TunnelState.FAILED }
        assertEquals(TunnelError.PLATFORM, st.error)
        assertTrue(st.message.contains("The VPN permission is not granted"))

        lib.close()
    }
}
