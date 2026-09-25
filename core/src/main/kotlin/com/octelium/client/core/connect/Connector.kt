package com.octelium.client.core.connect

import com.octelium.client.core.auth.AuthenticationRequiredException
import com.octelium.client.core.cluster.AUTH_METADATA_KEY
import com.octelium.client.core.domain.toInstant
import com.octelium.client.core.domain.toTimestamp
import com.octelium.client.core.local.LogEntry
import com.octelium.client.core.local.Logger
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.core.local.getStatusCode
import com.octelium.client.core.network.NetworkWatcher
import com.octelium.client.core.tunnel.PlatformRequestHandler
import com.octelium.client.core.tunnel.Tunnel
import com.octelium.client.core.tunnel.TunnelConfig
import com.octelium.client.core.tunnel.TunnelError
import com.octelium.client.core.tunnel.TunnelException
import com.octelium.client.core.tunnel.TunnelFactory
import com.octelium.client.core.tunnel.TunnelHandler
import com.octelium.client.core.tunnel.TunnelHost
import com.octelium.client.core.tunnel.TunnelPreferences
import com.octelium.client.core.tunnel.TunnelRequest
import com.octelium.client.core.tunnel.TunnelResponse
import com.octelium.client.core.tunnel.TunnelState
import com.octelium.client.core.tunnel.TunnelStatus
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import octelium.api.main.user.v1.MainServiceGrpcKt
import octelium.api.main.user.v1.Userv1
import java.time.Duration
import java.time.Instant

private val authMetadataKey: Metadata.Key<String> =
    Metadata.Key.of(AUTH_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

sealed interface ConnectEvent {
    data class Connecting(val err: Throwable? = null) : ConnectEvent

    data class Connected(val connection: Connection) : ConnectEvent

    data class Reconnecting(val err: Throwable? = null, val connection: Connection? = null) : ConnectEvent
}

class ConnectionClosedException(message: String, cause: Throwable? = null) : Exception(message, cause)

private data class TryConnectResult(
    val err: Throwable? = null,
    val needsReconnect: Boolean = false,
    val isConnected: Boolean = false,
)

private sealed interface SessionEvent {
    data class Response(val msg: Userv1.ConnectResponse) : SessionEvent

    data class Closed(val err: Throwable?) : SessionEvent

    data class Status(val status: TunnelStatus) : SessionEvent
}

class Connector(
    private val domain: String,
    private val opts: ConnectOptions,
    private val channels: () -> ManagedChannel,
    private val credentials: suspend () -> String,
    private val tunnels: TunnelFactory,
    private val host: TunnelHost,
    private val network: NetworkWatcher,
    private val logger: Logger,
    private val onEvent: (ConnectEvent) -> Unit,
    private val stateTimeout: Duration = Duration.ofSeconds(20),
    private val keepAliveInterval: Duration = Duration.ofMinutes(5),
) {
    suspend fun run() = coroutineScope {
        onEvent(ConnectEvent.Connecting())

        val handler = Handler(this)
        val tunnel = tunnels.create(handler)
        handler.tunnel = tunnel

        val networkJob = launch {
            network.state.collect { tunnel.setNetworkState(it.isAvailable, it.id) }
        }

        try {
            doRun(tunnel, handler)
        } finally {
            networkJob.cancel()
            withContext(NonCancellable) {
                tunnel.close()
            }
            coroutineContext.cancelChildren()
        }
    }

    private suspend fun doRun(tunnel: Tunnel, handler: Handler) {
        var isConnected = false
        var attempt = 0

        while (true) {
            val ret = tryConnect(tunnel, handler, isConnected)
            if (!ret.needsReconnect) {
                ret.err?.let { throw it }
                return
            }

            if (ret.isConnected) {
                isConnected = true
                attempt = 0
            }

            onEvent(if (isConnected) ConnectEvent.Reconnecting(ret.err) else ConnectEvent.Connecting(ret.err))

            ret.err?.let {
                logger.warn("Could not connect to the domain $domain: ${getErrorMessage(it)}. Reconnecting...")
            }

            attempt++
            network.waitReconnect(attempt)
        }
    }

    private suspend fun tryConnect(tunnel: Tunnel, handler: Handler, wasConnected: Boolean): TryConnectResult {
        val token = try {
            credentials()
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            return TryConnectResult(err, needsReconnect(err))
        }

        return try {
            coroutineScope {
                doTryConnect(this, token, tunnel, handler, wasConnected).also { coroutineContext.cancelChildren() }
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            TryConnectResult(err, needsReconnect(err))
        }
    }

    private suspend fun doTryConnect(
        scope: CoroutineScope,
        token: String,
        tunnel: Tunnel,
        handler: Handler,
        wasConnected: Boolean,
    ): TryConnectResult {
        val events = Channel<SessionEvent>(Channel.UNLIMITED)
        val requests = Channel<Userv1.ConnectRequest>(Channel.UNLIMITED)

        val headers = Metadata()
        headers.put(authMetadataKey, token)

        logger.debug("Connecting to the Cluster API of the domain $domain")

        requests.trySend(getInitializeRequest(opts))
        handler.reset()

        scope.launch {
            try {
                MainServiceGrpcKt.MainServiceCoroutineStub(channels())
                    .connect(requests.consumeAsFlow(), headers)
                    .collect { events.send(SessionEvent.Response(it)) }
                events.send(SessionEvent.Closed(null))
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                events.send(SessionEvent.Closed(err))
            }
        }

        scope.launch {
            handler.status.collect { events.send(SessionEvent.Status(it)) }
        }

        var tunnelStatus = handler.status.value

        val initial = withTimeoutOrNull(stateTimeout.toMillis()) {
            for (ev in events) {
                when (ev) {
                    is SessionEvent.Response -> {
                        if (ev.msg.hasState()) {
                            return@withTimeoutOrNull ev.msg
                        }
                        logger.debug("Found an initial message that is not a state")
                    }

                    is SessionEvent.Closed -> throw ev.err ?: getClosedException(null)
                    is SessionEvent.Status -> tunnelStatus = ev.status
                }
            }
            null
        } ?: throw ConnectionClosedException("Could not get the initial state message after a timeout")

        val initAt = toInstant(initial.createdAt)
        var state = initial.state

        tunnel.setConfig(getTunnelConfig(state))

        scope.launch {
            while (true) {
                delay(keepAliveInterval.toMillis())
                requests.send(getKeepAliveRequest())
            }
        }

        var isConnected = false

        fun onStatus(arg: TunnelStatus): TryConnectResult? {
            tunnelStatus = arg

            val err = arg.error?.let { TunnelException(it, arg.message) }

            when (arg.state) {
                TunnelState.CONNECTED -> {
                    if (!isConnected) {
                        logger.info("Connected to the domain $domain")
                    }
                    isConnected = true
                    onEvent(ConnectEvent.Connected(getConnection(state, handler)))
                }

                TunnelState.CONNECTING, TunnelState.RECONNECTING -> when {
                    isConnected -> onEvent(ConnectEvent.Reconnecting(err, getConnection(state, handler)))
                    err == null -> {}
                    wasConnected -> onEvent(ConnectEvent.Reconnecting(err))
                    else -> onEvent(ConnectEvent.Connecting(err))
                }

                TunnelState.FAILED -> return TryConnectResult(
                    err ?: TunnelException(TunnelError.INTERNAL, "The tunnel could not be established"),
                    needsReconnect = true,
                    isConnected = isConnected,
                )

                TunnelState.IDLE -> {}
            }

            return null
        }

        onStatus(tunnelStatus)?.let { return it }

        for (ev in events) {
            when (ev) {
                is SessionEvent.Status -> onStatus(ev.status)?.let { return it }

                is SessionEvent.Closed -> return TryConnectResult(
                    getClosedException(ev.err),
                    needsReconnect = true,
                    isConnected = isConnected,
                )

                is SessionEvent.Response -> {
                    val msg = ev.msg

                    if (shouldSkipResponse(msg, initAt)) {
                        logger.debug("Skipping an old response message")
                        continue
                    }

                    if (msg.hasDisconnect()) {
                        logger.info("Disconnected by the Cluster of the domain $domain")
                        return TryConnectResult(isConnected = isConnected)
                    }

                    state = reduceConnectionState(state, msg) ?: continue

                    try {
                        tunnel.setConfig(getTunnelConfig(state))
                    } catch (err: TunnelException) {
                        logger.error("Could not handle the state: ${err.message}")
                    }

                    if (isConnected && tunnelStatus.state == TunnelState.CONNECTED) {
                        onEvent(ConnectEvent.Connected(getConnection(state, handler)))
                    }
                }
            }
        }

        return TryConnectResult(getClosedException(null), needsReconnect = true, isConnected = isConnected)
    }

    private fun getTunnelConfig(state: Userv1.ConnectionState): TunnelConfig = TunnelConfig(
        domain = domain,
        state = state,
        preferences = TunnelPreferences(
            tunnelMode = opts.tunnelMode,
            dnsMode = opts.dnsMode,
            mtu = opts.mtu,
        ),
    )

    private fun getConnection(state: Userv1.ConnectionState, handler: Handler): Connection = Connection(
        state = state,
        tunnelMode = opts.tunnelMode,
        dnsMode = opts.dnsMode,
        mtu = handler.mtu,
    )

    private suspend fun getAccessTokenResponse(): TunnelResponse = try {
        TunnelResponse.GetAccessToken(credentials())
    } catch (err: CancellationException) {
        throw err
    } catch (err: Exception) {
        val code = if (err is AuthenticationRequiredException) {
            TunnelError.UNAUTHENTICATED
        } else {
            when (getStatusCode(err)) {
                Status.Code.UNAUTHENTICATED -> TunnelError.UNAUTHENTICATED
                Status.Code.UNAVAILABLE -> TunnelError.UNAVAILABLE
                Status.Code.DEADLINE_EXCEEDED -> TunnelError.TIMEOUT
                else -> TunnelError.INTERNAL
            }
        }

        TunnelResponse.Error(code, getErrorMessage(err))
    }

    private inner class Handler(private val scope: CoroutineScope) : TunnelHandler {
        lateinit var tunnel: Tunnel

        val status = MutableStateFlow(TunnelStatus(TunnelState.IDLE))

        @Volatile
        var mtu = 0

        private val platform = PlatformRequestHandler(host) { requestID, resp -> tunnel.complete(requestID, resp) }

        fun reset() {
            status.update {
                if (it.state == TunnelState.IDLE || it.state == TunnelState.FAILED) TunnelStatus(TunnelState.CONNECTING) else it
            }
        }

        override fun onStatus(status: TunnelStatus) {
            this.status.value = status
        }

        override fun onLog(log: LogEntry) {
            logger.log(log)
        }

        override fun onRequest(requestID: Long, request: TunnelRequest) {
            scope.launch {
                when (request) {
                    is TunnelRequest.ApplyNetworkConfig -> {
                        mtu = request.config.mtu
                        platform.applyNetworkConfig(requestID, domain, request.config)
                    }

                    TunnelRequest.GetAccessToken -> tunnel.complete(requestID, getAccessTokenResponse())
                }
            }
        }
    }
}

private fun getClosedException(err: Throwable?): ConnectionClosedException = if (err != null) {
    ConnectionClosedException("Abruptly disconnected by the Cluster: ${getErrorMessage(err)}", err)
} else {
    ConnectionClosedException("The Connection was closed by the Cluster")
}

private fun getKeepAliveRequest(): Userv1.ConnectRequest = Userv1.ConnectRequest.newBuilder()
    .setKeepAlive(Userv1.ConnectRequest.KeepAlive.newBuilder().setSetAt(toTimestamp(Instant.now())))
    .build()

private fun shouldSkipResponse(msg: Userv1.ConnectResponse, initAt: Instant?): Boolean {
    val createdAt = toInstant(msg.createdAt) ?: return false
    return initAt != null && createdAt.isBefore(initAt)
}

private fun needsReconnect(err: Throwable): Boolean = when (getStatusCode(err)) {
    Status.Code.INVALID_ARGUMENT,
    Status.Code.PERMISSION_DENIED,
    Status.Code.NOT_FOUND -> false

    else -> true
}
