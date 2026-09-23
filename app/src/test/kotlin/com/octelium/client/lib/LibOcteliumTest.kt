package com.octelium.client.lib

import com.google.protobuf.ByteString
import com.octelium.client.core.local.LocalClient
import com.octelium.client.core.local.checkInfo
import com.octelium.client.core.tunnel.getPlatformErrorResponse
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.mobile.v1.Mobilev1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LibOcteliumTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val libDir = File(System.getProperty("octelium.hostLibDir").orEmpty())

    private class Callbacks : NativeCallbacks {
        val events = LinkedBlockingQueue<Mobilev1.Event>()
        val requests = LinkedBlockingQueue<Pair<Long, Mobilev1.PlatformRequest>>()

        override fun onEvent(data: ByteArray) {
            events.add(Mobilev1.Event.parseFrom(data))
        }

        override fun onRequest(requestID: Long, data: ByteArray) {
            requests.add(requestID to Mobilev1.PlatformRequest.parseFrom(data))
        }

        fun awaitStatus(fn: (Daemonv1.GetStatusResponse) -> Boolean): Daemonv1.GetStatusResponse {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val ev = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (ev.hasStatus() && fn(ev.status)) {
                    return ev.status
                }
            }

            throw AssertionError("Timed out waiting for the status")
        }
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

    private fun getConfig(
        stateDir: File = tmp.newFolder(),
        stateKey: ByteArray = ByteArray(32) { it.toByte() },
    ): Mobilev1.Config = Mobilev1.Config.newBuilder()
        .setPlatform(Mobilev1.Config.Platform.ANDROID)
        .setStateDir(stateDir.path)
        .setStateKey(ByteString.copyFrom(stateKey))
        .setDevice(Mobilev1.Config.Device.newBuilder().setId(UUID.randomUUID().toString()).setName("test"))
        .setLogLevel(Mobilev1.Log.Level.DEBUG)
        .build()

    private suspend fun assertCode(code: Status.Code, fn: suspend () -> Unit) {
        try {
            fn()
            fail()
        } catch (err: StatusException) {
            assertEquals(code, err.status.code)
        }
    }

    @Test
    fun testABI() {
        assertEquals(ABI_VERSION, Native.abiVersion())
    }

    @Test
    fun testClient() = runBlocking {
        val callbacks = Callbacks()
        val lib = LibOctelium.create(getConfig(), callbacks)
        val c = LocalClient(lib)

        run {
            val info = c.getInfo()
            assertNull(checkInfo(info))
            assertEquals(1, info.apiMajorVersion)
            assertEquals("com.octelium.client:/callback/success", info.authenticationCallbackURL)
            assertTrue(info.instanceID.isNotEmpty())
        }

        run {
            val status = c.getStatus()
            assertTrue(status.domainsList.isEmpty())
        }

        run {
            val ret = c.updateDomainSettings(
                "Example.COM",
                Daemonv1.DomainSettings.newBuilder()
                    .setAutoConnect(true)
                    .setConnectionOptions(
                        Daemonv1.ConnectionOptions.newBuilder()
                            .setTunnelMode(Daemonv1.ConnectionOptions.TunnelMode.QUICV0)
                    )
                    .build(),
            )
            assertEquals("example.com", ret.domain)
            assertTrue(ret.autoConnect)

            val status = callbacks.awaitStatus { it.domainsCount == 1 }
            assertEquals("example.com", status.domainsList.single().domain)
            assertTrue(status.domainsList.single().settings.autoConnect)
            assertEquals(
                Daemonv1.AuthenticationStatus.State.LOGGED_OUT,
                status.domainsList.single().authentication.state,
            )
        }

        run {
            assertCode(Status.Code.UNAUTHENTICATED) { c.connect("example.com") }
            assertCode(Status.Code.NOT_FOUND) { c.connect("unknown.example.com") }
            assertCode(Status.Code.INVALID_ARGUMENT) { c.connect("not a domain") }
            assertCode(Status.Code.UNAUTHENTICATED) { c.getAPICredential("example.com") }
            assertCode(Status.Code.NOT_FOUND) { c.getOperation(UUID.randomUUID().toString()) }
            assertCode(Status.Code.UNIMPLEMENTED) { lib.call("Unknown", ByteArray(0)) }
            assertCode(Status.Code.INVALID_ARGUMENT) { lib.call("GetOperation", byteArrayOf(0xff.toByte())) }
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
        }

        run {
            c.setNetworkState(false, "")
            c.setNetworkState(true, "100")
        }

        run {
            assertEquals(
                Status.Code.NOT_FOUND.value(),
                lib.complete(12345, getPlatformErrorResponse("failed").toByteArray()),
            )
            assertEquals(Status.Code.INVALID_ARGUMENT.value(), lib.complete(1, byteArrayOf(0xff.toByte())))
        }

        run {
            val op = c.deleteDomain("example.com")
            assertEquals(Daemonv1.Operation.Type.DELETE, op.type)
            callbacks.awaitStatus { it.domainsCount == 0 }
        }

        lib.close()
        lib.close()

        assertCode(Status.Code.UNAVAILABLE) { c.getInfo() }
        assertEquals(Status.Code.UNAVAILABLE.value(), lib.complete(1, ByteArray(0)))
    }

    @Test
    fun testPersistence() = runBlocking {
        val stateDir = tmp.newFolder()

        run {
            val lib = LibOctelium.create(getConfig(stateDir = stateDir), Callbacks())
            LocalClient(lib).updateDomainSettings(
                "example.com",
                Daemonv1.DomainSettings.newBuilder().setAutoConnect(true).build(),
            )
            lib.close()
        }

        run {
            val lib = LibOctelium.create(getConfig(stateDir = stateDir), Callbacks())
            val status = LocalClient(lib).getStatus()
            assertEquals(listOf("example.com"), status.domainsList.map { it.domain })
            assertTrue(status.domainsList.single().settings.autoConnect)
            lib.close()
        }

        run {
            try {
                LibOctelium.create(getConfig(stateDir = stateDir, stateKey = ByteArray(32) { 7 }), Callbacks())
                fail()
            } catch (err: StatusException) {
                assertEquals(Status.Code.INTERNAL, err.status.code)
            }
        }

        assertTrue(stateDir.listFiles()!!.isNotEmpty())
    }

    @Test
    fun testInvalidConfig() {
        run {
            try {
                LibOctelium.create(getConfig(stateKey = ByteArray(16)), Callbacks())
                fail()
            } catch (err: StatusException) {
                assertEquals(Status.Code.INVALID_ARGUMENT, err.status.code)
                assertEquals("The state key must be 32 bytes", err.status.description)
            }
        }

        run {
            try {
                LibOctelium.create(getConfig().toBuilder().clearDevice().build(), Callbacks())
                fail()
            } catch (err: StatusException) {
                assertEquals(Status.Code.INVALID_ARGUMENT, err.status.code)
            }
        }

        run {
            try {
                LibOctelium.create(
                    getConfig().toBuilder().setPlatform(Mobilev1.Config.Platform.PLATFORM_UNSPECIFIED).build(),
                    Callbacks(),
                )
                fail()
            } catch (err: StatusException) {
                assertEquals(Status.Code.INVALID_ARGUMENT, err.status.code)
            }
        }

        run {
            val ret = Native.newClient(byteArrayOf(0xff.toByte(), 0xff.toByte()), Callbacks())
            assertEquals(Status.Code.INVALID_ARGUMENT.value(), ret.code)
            assertEquals(0L, ret.handle)
            assertTrue(ret.message.startsWith("Could not unmarshal the config"))
        }

        run {
            val ret = Native.call(987654321, "GetInfo", ByteArray(0))
            assertEquals(Status.Code.NOT_FOUND.value(), ret.code)
            assertEquals("Unknown client", ret.message)
        }
    }
}
