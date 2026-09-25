package com.octelium.client.core.tunnel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

interface EstablishedTunnel {
    val fd: Int

    fun commit()

    fun abort()
}

interface TunnelHost {
    suspend fun establish(domain: String, generation: Long, spec: TunnelSpec): EstablishedTunnel
}

fun interface RequestCompleter {
    fun complete(requestID: Long, response: TunnelResponse): Int
}

class PlatformRequestHandler(
    private val host: TunnelHost,
    private val completer: RequestCompleter,
) {
    private val mutex = Mutex()
    private val latestGeneration = AtomicLong(0)

    suspend fun applyNetworkConfig(requestID: Long, domain: String, cfg: NetworkConfig) {
        latestGeneration.updateAndGet { maxOf(it, cfg.generation) }

        if (domain.isEmpty()) {
            completeError(requestID, "The domain is not set")
            return
        }

        val spec = try {
            getTunnelSpec(cfg)
        } catch (err: InvalidTunnelConfigurationException) {
            completeError(requestID, err.message ?: "Invalid tunnel configuration")
            return
        }

        mutex.withLock {
            establish(requestID, domain, cfg.generation, spec)
        }
    }

    private suspend fun establish(requestID: Long, domain: String, generation: Long, spec: TunnelSpec) {
        if (generation < latestGeneration.get()) {
            completeError(requestID, "The tunnel configuration is stale")
            return
        }

        val tun = try {
            host.establish(domain, generation, spec)
        } catch (err: Exception) {
            completeError(requestID, err.message ?: "Could not establish the tunnel")
            return
        }

        if (completer.complete(requestID, TunnelResponse.ApplyNetworkConfig(tun.fd)) == 0) {
            tun.commit()
        } else {
            tun.abort()
        }
    }

    private fun completeError(requestID: Long, message: String) {
        completer.complete(requestID, TunnelResponse.Error(TunnelError.PLATFORM, message))
    }
}
