package com.octelium.client.core.client

import com.google.protobuf.ByteString
import com.octelium.client.core.auth.REFRESH_TOKEN_METADATA_KEY
import com.octelium.client.core.cluster.AUTH_METADATA_KEY
import com.octelium.client.core.domain.toTimestamp
import com.octelium.client.core.tunnel.DNSConfig
import com.octelium.client.core.tunnel.EstablishedTunnel
import com.octelium.client.core.tunnel.NetworkConfig
import com.octelium.client.core.tunnel.Tunnel
import com.octelium.client.core.tunnel.TunnelConfig
import com.octelium.client.core.tunnel.TunnelError
import com.octelium.client.core.tunnel.TunnelHandler
import com.octelium.client.core.tunnel.TunnelHost
import com.octelium.client.core.tunnel.TunnelRequest
import com.octelium.client.core.tunnel.TunnelResponse
import com.octelium.client.core.tunnel.TunnelSpec
import com.octelium.client.core.tunnel.TunnelState
import com.octelium.client.core.tunnel.TunnelStatus
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import octelium.api.main.auth.v1.Authv1
import octelium.api.main.meta.v1.Metav1
import octelium.api.main.user.v1.Userv1
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import octelium.api.main.auth.v1.MainServiceGrpcKt as AuthServiceGrpcKt
import octelium.api.main.user.v1.MainServiceGrpcKt as UserServiceGrpcKt

const val TEST_AUTHENTICATION_TOKEN = "auth-token"

private val authMetadataKey = Metadata.Key.of(AUTH_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)
private val refreshTokenMetadataKey = Metadata.Key.of(REFRESH_TOKEN_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

fun getGateway(id: String, cidr: String = "fdee:1::/64"): Userv1.Gateway = Userv1.Gateway.newBuilder()
    .setId(id)
    .setHostname("$id.example.com")
    .addAddresses("192.0.2.1")
    .addCIDRs(cidr)
    .setWireguard(
        Userv1.Gateway.WireGuard.newBuilder()
            .setPort(51820)
            .setPublicKey("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=")
    )
    .build()

fun getConnectionState(): Userv1.ConnectionState = Userv1.ConnectionState.newBuilder()
    .setMtu(1280)
    .setX25519Key(ByteString.copyFrom(ByteArray(32) { 1 }))
    .setL3Mode(Userv1.ConnectionState.L3Mode.V6)
    .addAddresses(Metav1.DualStackNetwork.newBuilder().setV4("100.64.0.5/32").setV6("fdee:1::5/128"))
    .addGateways(getGateway("gw-1"))
    .setDns(Userv1.DNS.newBuilder().addServers("fdee:1::53"))
    .setCidr(Metav1.DualStackNetwork.newBuilder().setV4("100.64.0.0/10").setV6("fdee:1::/64"))
    .build()

class FakeCluster {
    val serverName = "cluster-${java.util.UUID.randomUUID()}"

    val calls: MutableList<Pair<String, Metadata>> = Collections.synchronizedList(mutableListOf())
    val authenticateRequests: MutableList<Authv1.AuthenticateWithAuthenticationTokenRequest> =
        Collections.synchronizedList(mutableListOf())
    val registerRequests: MutableList<Authv1.RegisterDeviceBeginRequest> = Collections.synchronizedList(mutableListOf())
    val initRequests: MutableList<Userv1.ConnectRequest> = Collections.synchronizedList(mutableListOf())
    val sessions = CopyOnWriteArrayList<Channel<Userv1.ConnectResponse>>()

    val accessTokens: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val refreshTokens: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    var expiresIn = 3600L

    @Volatile
    var state: Userv1.ConnectionState = getConnectionState()

    @Volatile
    var userType = Userv1.GetStatusResponse.User.Spec.Type.HUMAN

    private val nextID = AtomicInteger()

    fun getCalls(method: String): List<Metadata> = synchronized(calls) {
        calls.filter { it.first == method }.map { it.second }
    }

    fun issueSessionToken(): Authv1.SessionToken {
        val id = nextID.incrementAndGet()
        accessTokens.add("access-$id")
        refreshTokens.add("refresh-$id")

        return Authv1.SessionToken.newBuilder()
            .setAccessToken("access-$id")
            .setRefreshToken("refresh-$id")
            .setExpiresIn(expiresIn)
            .setRefreshTokenExpiresIn(86400)
            .build()
    }

    private val authService = object : AuthServiceGrpcKt.MainServiceCoroutineImplBase() {
        override suspend fun authenticateWithAuthenticationToken(
            request: Authv1.AuthenticateWithAuthenticationTokenRequest,
        ): Authv1.SessionToken {
            authenticateRequests.add(request)

            if (request.authenticationToken != TEST_AUTHENTICATION_TOKEN) {
                throw StatusException(Status.UNAUTHENTICATED.withDescription("Invalid authentication Token"))
            }

            return issueSessionToken()
        }

        override suspend fun authenticateWithRefreshToken(
            request: Authv1.AuthenticateWithRefreshTokenRequest,
        ): Authv1.SessionToken = issueSessionToken()

        override suspend fun logout(request: Authv1.LogoutRequest): Authv1.LogoutResponse =
            Authv1.LogoutResponse.getDefaultInstance()

        override suspend fun registerDeviceBegin(
            request: Authv1.RegisterDeviceBeginRequest,
        ): Authv1.RegisterDeviceBeginResponse {
            registerRequests.add(request)
            return Authv1.RegisterDeviceBeginResponse.newBuilder().setUid("register-1").build()
        }

        override suspend fun registerDeviceFinish(
            request: Authv1.RegisterDeviceFinishRequest,
        ): Authv1.RegisterDeviceFinishResponse = Authv1.RegisterDeviceFinishResponse.getDefaultInstance()
    }

    private val userService = object : UserServiceGrpcKt.MainServiceCoroutineImplBase() {
        override suspend fun getStatus(request: Userv1.GetStatusRequest): Userv1.GetStatusResponse =
            Userv1.GetStatusResponse.newBuilder()
                .setUser(
                    Userv1.GetStatusResponse.User.newBuilder()
                        .setMetadata(Metav1.Metadata.newBuilder().setName("alice"))
                        .setSpec(Userv1.GetStatusResponse.User.Spec.newBuilder().setType(userType))
                )
                .build()

        override suspend fun disconnect(request: Userv1.DisconnectRequest): Userv1.DisconnectResponse =
            Userv1.DisconnectResponse.getDefaultInstance()

        override fun connect(requests: Flow<Userv1.ConnectRequest>): Flow<Userv1.ConnectResponse> = channelFlow {
            val events = Channel<Userv1.ConnectResponse>(Channel.UNLIMITED)
            sessions.add(events)

            launch {
                requests.collect { initRequests.add(it) }
            }

            send(
                Userv1.ConnectResponse.newBuilder()
                    .setState(state)
                    .setCreatedAt(toTimestamp(Instant.now()))
                    .build(),
            )

            for (ev in events) {
                send(ev)
            }
        }
    }

    private val interceptor = object : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            val method = call.methodDescriptor.bareMethodName ?: ""
            calls.add(method to headers)

            val isUserService = call.methodDescriptor.serviceName == "octelium.api.main.user.v1.MainService"

            val isValid = when {
                isUserService -> accessTokens.contains(headers.get(authMetadataKey))
                method == "AuthenticateWithRefreshToken" -> refreshTokens.contains(headers.get(refreshTokenMetadataKey))
                else -> true
            }

            if (!isValid) {
                call.close(Status.UNAUTHENTICATED.withDescription("Invalid credentials"), Metadata())
                return object : ServerCall.Listener<ReqT>() {}
            }

            return next.startCall(call, headers)
        }
    }

    val server = InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(ServerInterceptors.intercept(authService, interceptor))
        .addService(ServerInterceptors.intercept(userService, interceptor))
        .build()
        .start()

    fun send(arg: Userv1.ConnectResponse) {
        sessions.last().trySend(arg)
    }

    fun close() {
        server.shutdownNow()
    }
}

class FakeTunnel(private val handler: TunnelHandler) : Tunnel {
    val configs: MutableList<TunnelConfig> = Collections.synchronizedList(mutableListOf())
    val networkStates: MutableList<Pair<Boolean, String>> = Collections.synchronizedList(mutableListOf())
    val responses: MutableList<Pair<Long, TunnelResponse>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var isClosed = false

    private var nextID = 0L
    private var state = TunnelState.IDLE
    private var applied: NetworkConfig? = null

    @Synchronized
    override fun setConfig(config: TunnelConfig) {
        configs.add(config)

        if (state == TunnelState.IDLE || state == TunnelState.FAILED) {
            setStatus(TunnelStatus(TunnelState.CONNECTING))
        }

        val cfg = getNetworkConfig(config)
        if (cfg == applied?.copy(generation = cfg.generation)) {
            setStatus(TunnelStatus(TunnelState.CONNECTED))
            return
        }

        handler.onRequest(nextID, TunnelRequest.ApplyNetworkConfig(cfg))
    }

    override fun setNetworkState(isAvailable: Boolean, id: String) {
        networkStates.add(isAvailable to id)
    }

    @Synchronized
    override fun complete(requestID: Long, response: TunnelResponse): Int {
        if (isClosed) {
            return TunnelError.NOT_FOUND.code
        }

        responses.add(requestID to response)

        when (response) {
            is TunnelResponse.ApplyNetworkConfig -> {
                applied = configs.last().let { getNetworkConfig(it) }
                setStatus(TunnelStatus(TunnelState.CONNECTED))
            }

            is TunnelResponse.Error -> if (applied == null) {
                setStatus(TunnelStatus(TunnelState.FAILED, response.error, response.message))
            }

            is TunnelResponse.GetAccessToken -> {}
        }

        return 0
    }

    @Synchronized
    fun setStatus(arg: TunnelStatus) {
        state = arg.state
        handler.onStatus(arg)
    }

    @Synchronized
    fun requestAccessToken(): Long {
        val id = ++nextID
        handler.onRequest(id, TunnelRequest.GetAccessToken)
        return id
    }

    override fun close() {
        isClosed = true
    }

    private fun getNetworkConfig(config: TunnelConfig): NetworkConfig {
        val state = config.state

        return NetworkConfig(
            generation = ++nextID,
            addresses = state.addressesList.flatMap { listOf(it.v4, it.v6) }.filter { it.isNotEmpty() },
            routes = listOf(state.cidr.v4, state.cidr.v6).filter { it.isNotEmpty() },
            dns = DNSConfig(servers = state.dns.serversList),
            mtu = 1280,
        )
    }
}

class FakeHost(@Volatile var err: Exception? = null) : TunnelHost {
    val specs: MutableList<Pair<String, TunnelSpec>> = Collections.synchronizedList(mutableListOf())

    override suspend fun establish(domain: String, generation: Long, spec: TunnelSpec): EstablishedTunnel {
        err?.let { throw it }
        specs.add(domain to spec)

        return object : EstablishedTunnel {
            override val fd: Int = 100

            override fun commit() {}

            override fun abort() {}
        }
    }
}
