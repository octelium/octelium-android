package com.octelium.client.core.tunnel

import com.google.protobuf.InvalidProtocolBufferException
import octelium.api.client.mobile.v1.Mobilev1

interface EstablishedTunnel {
    val fd: Int

    fun commit()

    fun abort()
}

interface TunnelHost {
    suspend fun establish(domain: String, generation: Long, spec: TunnelSpec): EstablishedTunnel
}

fun interface RequestCompleter {
    fun complete(requestID: Long, response: ByteArray): Int
}

class PlatformRequestHandler(
    private val host: TunnelHost,
    private val completer: RequestCompleter,
) {
    suspend fun handle(requestID: Long, data: ByteArray) {
        val req = try {
            Mobilev1.PlatformRequest.parseFrom(data)
        } catch (err: InvalidProtocolBufferException) {
            completeError(requestID, "Could not unmarshal the platform request: ${err.message}")
            return
        }

        when (req.typeCase) {
            Mobilev1.PlatformRequest.TypeCase.APPLYTUNNELCONFIGURATION ->
                applyTunnelConfiguration(requestID, req.applyTunnelConfiguration)

            else -> completeError(requestID, "Unsupported platform request: ${req.typeCase}")
        }
    }

    private suspend fun applyTunnelConfiguration(
        requestID: Long,
        req: Mobilev1.PlatformRequest.ApplyTunnelConfiguration,
    ) {
        if (req.domain.isEmpty()) {
            completeError(requestID, "The domain is not set")
            return
        }

        val spec = try {
            getTunnelSpec(req.configuration)
        } catch (err: InvalidTunnelConfigurationException) {
            completeError(requestID, err.message ?: "Invalid tunnel configuration")
            return
        }

        val tun = try {
            host.establish(req.domain, req.generation, spec)
        } catch (err: Exception) {
            completeError(requestID, err.message ?: "Could not establish the tunnel")
            return
        }

        val resp = Mobilev1.PlatformResponse.newBuilder()
            .setApplyTunnelConfiguration(
                Mobilev1.PlatformResponse.ApplyTunnelConfiguration.newBuilder()
                    .setTunFD(tun.fd)
            )
            .build()

        if (completer.complete(requestID, resp.toByteArray()) == 0) {
            tun.commit()
        } else {
            tun.abort()
        }
    }

    private fun completeError(requestID: Long, message: String) {
        completer.complete(requestID, getPlatformErrorResponse(message).toByteArray())
    }
}

fun getPlatformErrorResponse(message: String): Mobilev1.PlatformResponse =
    Mobilev1.PlatformResponse.newBuilder()
        .setError(Mobilev1.PlatformResponse.Error.newBuilder().setMessage(message))
        .build()
