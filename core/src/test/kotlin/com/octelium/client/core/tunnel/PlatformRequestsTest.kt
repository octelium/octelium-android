package com.octelium.client.core.tunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        val responses = mutableListOf<Pair<Long, TunnelResponse>>()

        override fun complete(requestID: Long, response: TunnelResponse): Int {
            responses.add(requestID to response)
            return code
        }
    }

    private fun getConfig(
        generation: Long = 1,
        addresses: List<String> = listOf("10.1.2.3/32"),
        routes: List<String> = listOf("10.1.0.0/16"),
    ): NetworkConfig = NetworkConfig(
        generation = generation,
        addresses = addresses,
        routes = routes,
        dns = null,
        mtu = 1280,
    )

    private fun getErrorMessage(arg: TunnelResponse): String {
        assertTrue(arg is TunnelResponse.Error)
        assertEquals(TunnelError.PLATFORM, (arg as TunnelResponse.Error).error)
        return arg.message
    }

    @Test
    fun testApplyNetworkConfig() = runTest {
        val host = FakeHost()
        val completer = FakeCompleter()
        val h = PlatformRequestHandler(host, completer)

        h.applyNetworkConfig(7, "example.com", getConfig(generation = 3))

        assertEquals(1, completer.responses.size)
        val (id, resp) = completer.responses.single()
        assertEquals(7L, id)
        assertEquals(TunnelResponse.ApplyNetworkConfig(100), resp)

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
        val h = PlatformRequestHandler(host, FakeCompleter(code = 3))

        h.applyNetworkConfig(7, "example.com", getConfig())

        assertFalse(host.tunnels.single().isCommitted)
        assertTrue(host.tunnels.single().isAborted)
    }

    @Test
    fun testStaleGeneration() = runTest {
        val host = FakeHost()
        val completer = FakeCompleter()
        val h = PlatformRequestHandler(host, completer)

        h.applyNetworkConfig(1, "example.com", getConfig(generation = 5))
        h.applyNetworkConfig(2, "example.com", getConfig(generation = 3))
        h.applyNetworkConfig(3, "example.com", getConfig(generation = 6))

        assertEquals(listOf(5L, 6L), host.specs.map { it.second })
        assertEquals(listOf(1L, 2L, 3L), completer.responses.map { it.first })
        assertTrue(completer.responses[0].second is TunnelResponse.ApplyNetworkConfig)
        assertEquals("The tunnel configuration is stale", getErrorMessage(completer.responses[1].second))
        assertTrue(completer.responses[2].second is TunnelResponse.ApplyNetworkConfig)
    }

    @Test
    fun testStaleGenerationWhileEstablishing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = FakeHost(gate = gate)
        val completer = FakeCompleter()
        val h = PlatformRequestHandler(host, completer)

        for (generation in 1L..3L) {
            launch { h.applyNetworkConfig(generation, "example.com", getConfig(generation = generation)) }
        }
        advanceUntilIdle()

        assertTrue(host.specs.isEmpty())
        assertTrue(completer.responses.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1L, 3L), host.specs.map { it.second })
        assertEquals(listOf(1L, 2L, 3L), completer.responses.map { it.first })
        assertEquals("The tunnel configuration is stale", getErrorMessage(completer.responses[1].second))
        assertTrue(completer.responses[2].second is TunnelResponse.ApplyNetworkConfig)
    }

    @Test
    fun testErrors() = runTest {
        run {
            val host = FakeHost()
            val completer = FakeCompleter()
            PlatformRequestHandler(host, completer).applyNetworkConfig(
                2,
                "example.com",
                getConfig(routes = listOf("0.0.0.0/0")),
            )
            assertEquals(2L, completer.responses.single().first)
            assertEquals("Default routes are not supported: 0.0.0.0/0", getErrorMessage(completer.responses.single().second))
            assertTrue(host.tunnels.isEmpty())
        }

        run {
            val completer = FakeCompleter()
            PlatformRequestHandler(FakeHost(), completer).applyNetworkConfig(3, "", getConfig())
            assertEquals("The domain is not set", getErrorMessage(completer.responses.single().second))
        }

        run {
            val completer = FakeCompleter()
            PlatformRequestHandler(FakeHost(IllegalStateException("The VPN permission is not granted")), completer)
                .applyNetworkConfig(4, "example.com", getConfig())
            assertEquals("The VPN permission is not granted", getErrorMessage(completer.responses.single().second))
        }
    }
}
