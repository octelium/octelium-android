package com.octelium.client.core.tunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import octelium.api.client.mobile.v1.Mobilev1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformRequestsTest {

    private class FakeTunnel(override val fd: Int) : EstablishedTunnel {
        var isCommitted = false
        var isAborted = false

        override fun commit() {
            isCommitted = true
        }

        override fun abort() {
            isAborted = true
        }
    }

    private class FakeHost(
        private val err: Exception? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : TunnelHost {
        val tunnels = mutableListOf<FakeTunnel>()
        val specs = mutableListOf<Triple<String, Long, TunnelSpec>>()

        override suspend fun establish(domain: String, generation: Long, spec: TunnelSpec): EstablishedTunnel {
            gate?.await()
            err?.let { throw it }
            specs.add(Triple(domain, generation, spec))
            return FakeTunnel(100 + tunnels.size).also { tunnels.add(it) }
        }
    }

    private class FakeCompleter(private val code: Int = 0) : RequestCompleter {
        val responses = mutableListOf<Pair<Long, Mobilev1.PlatformResponse>>()

        override fun complete(requestID: Long, response: ByteArray): Int {
            responses.add(requestID to Mobilev1.PlatformResponse.parseFrom(response))
            return code
        }
    }

    private fun getRequest(
        domain: String = "example.com",
        generation: Long = 1,
        cfg: Mobilev1.TunnelConfiguration = Mobilev1.TunnelConfiguration.newBuilder()
            .addAddresses("10.1.2.3/32")
            .addRoutes("10.1.0.0/16")
            .setMtu(1280)
            .build(),
    ): ByteArray = Mobilev1.PlatformRequest.newBuilder()
        .setApplyTunnelConfiguration(
            Mobilev1.PlatformRequest.ApplyTunnelConfiguration.newBuilder()
                .setDomain(domain)
                .setGeneration(generation)
                .setConfiguration(cfg)
        )
        .build()
        .toByteArray()

    @Test
    fun testApplyTunnelConfiguration() = runTest {
        val host = FakeHost()
        val completer = FakeCompleter()
        val h = PlatformRequestHandler(host, completer)

        h.handle(7, getRequest(generation = 3))

        assertEquals(1, completer.responses.size)
        val (id, resp) = completer.responses.single()
        assertEquals(7L, id)
        assertTrue(resp.hasApplyTunnelConfiguration())
        assertTrue(resp.applyTunnelConfiguration.hasTunFD())
        assertEquals(100, resp.applyTunnelConfiguration.tunFD)

        val (domain, generation, spec) = host.specs.single()
        assertEquals("example.com", domain)
        assertEquals(3L, generation)
        assertEquals(1280, spec.mtu)
        assertEquals(listOf("10.1.0.0/16"), spec.routes.map { it.toString() })

        assertTrue(host.tunnels.single().isCommitted)
        assertFalse(host.tunnels.single().isAborted)
    }

    @Test
    fun testCompleteFailed() = runTest {
        val host = FakeHost()
        val h = PlatformRequestHandler(host, FakeCompleter(code = 5))

        h.handle(7, getRequest())

        assertFalse(host.tunnels.single().isCommitted)
        assertTrue(host.tunnels.single().isAborted)
    }

    @Test
    fun testStaleGeneration() = runTest {
        val host = FakeHost()
        val completer = FakeCompleter()
        val h = PlatformRequestHandler(host, completer)

        h.handle(1, getRequest(generation = 5))
        h.handle(2, getRequest(generation = 3))
        h.handle(3, getRequest(generation = 6))

        assertEquals(listOf(5L, 6L), host.specs.map { it.second })
        assertEquals(listOf(1L, 2L, 3L), completer.responses.map { it.first })
        assertTrue(completer.responses[0].second.hasApplyTunnelConfiguration())
        assertEquals("The tunnel configuration is stale", completer.responses[1].second.error.message)
        assertTrue(completer.responses[2].second.hasApplyTunnelConfiguration())
    }

    @Test
    fun testStaleGenerationWhileEstablishing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = FakeHost(gate = gate)
        val completer = FakeCompleter()
        val h = PlatformRequestHandler(host, completer)

        for (generation in 1L..3L) {
            launch { h.handle(generation, getRequest(generation = generation)) }
        }
        advanceUntilIdle()

        assertTrue(host.specs.isEmpty())
        assertTrue(completer.responses.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1L, 3L), host.specs.map { it.second })
        assertEquals(listOf(1L, 2L, 3L), completer.responses.map { it.first })
        assertEquals("The tunnel configuration is stale", completer.responses[1].second.error.message)
        assertTrue(completer.responses[2].second.hasApplyTunnelConfiguration())
    }

    @Test
    fun testErrors() = runTest {
        run {
            val completer = FakeCompleter()
            PlatformRequestHandler(FakeHost(), completer).handle(1, byteArrayOf(0xff.toByte(), 0xff.toByte()))
            assertTrue(completer.responses.single().second.hasError())
            assertTrue(
                completer.responses.single().second.error.message.startsWith("Could not unmarshal the platform request"),
            )
        }

        run {
            val completer = FakeCompleter()
            PlatformRequestHandler(FakeHost(), completer).handle(1, ByteArray(0))
            assertEquals("Unsupported platform request: TYPE_NOT_SET", completer.responses.single().second.error.message)
        }

        run {
            val host = FakeHost()
            val completer = FakeCompleter()
            PlatformRequestHandler(host, completer).handle(
                2,
                getRequest(cfg = Mobilev1.TunnelConfiguration.newBuilder().addAddresses("10.1.2.3/32").addRoutes("0.0.0.0/0").build()),
            )
            assertEquals(2L, completer.responses.single().first)
            assertEquals("Default routes are not supported: 0.0.0.0/0", completer.responses.single().second.error.message)
            assertTrue(host.tunnels.isEmpty())
        }

        run {
            val completer = FakeCompleter()
            PlatformRequestHandler(FakeHost(), completer).handle(3, getRequest(domain = ""))
            assertEquals("The domain is not set", completer.responses.single().second.error.message)
        }

        run {
            val completer = FakeCompleter()
            PlatformRequestHandler(FakeHost(IllegalStateException("The VPN permission is not granted")), completer)
                .handle(4, getRequest())
            assertEquals("The VPN permission is not granted", completer.responses.single().second.error.message)
        }
    }

    @Test
    fun testGetPlatformErrorResponse() {
        val ret = getPlatformErrorResponse("failed")
        assertTrue(ret.hasError())
        assertEquals("failed", ret.error.message)
        assertEquals(Mobilev1.PlatformResponse.TypeCase.ERROR, ret.typeCase)
    }
}
