package com.octelium.client.ui

import com.google.protobuf.Timestamp
import com.octelium.client.core.local.LocalClient
import com.octelium.client.core.local.LocalTransport
import com.octelium.client.core.local.StatusStore
import com.octelium.client.core.local.getStatusException
import com.octelium.client.runtime.ClientRuntime
import com.octelium.client.runtime.RuntimeState
import io.grpc.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.daemon.v1.Daemonv1.AuthenticationStatus
import octelium.api.client.daemon.v1.Daemonv1.ConnectionOptions
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import octelium.api.client.mobile.v1.Mobilev1
import octelium.api.main.meta.v1.Metav1
import octelium.api.main.user.v1.MainServiceGrpcKt
import octelium.api.main.user.v1.Userv1
import java.time.Instant
import java.util.Collections

const val TEST_CALLBACK_URL = "com.octelium.client:/callback/success"

fun getTimestamp(arg: Instant): Timestamp = Timestamp.newBuilder().setSeconds(arg.epochSecond).build()

fun getAuthenticatedDomain(
    domain: String,
    conn: ConnectionStatus.State = ConnectionStatus.State.DISCONNECTED,
): Daemonv1.DomainState {
    val connection = ConnectionStatus.newBuilder().setState(conn)

    if (conn == ConnectionStatus.State.CONNECTED) {
        connection
            .setTunnelMode(ConnectionOptions.TunnelMode.WIREGUARD)
            .setImplementationMode(ConnectionOptions.ImplementationMode.TUN)
            .setMtu(1280)
            .setConnectedAt(getTimestamp(Instant.now().minusSeconds(3725)))
            .addAddresses(Metav1.DualStackNetwork.newBuilder().setV4("100.64.0.5/32").setV6("fdee:1::5/128"))
            .setDns(
                ConnectionStatus.DNS.newBuilder()
                    .setMode(ConnectionOptions.DNS.Mode.DEFAULT)
                    .setIsConfigured(true)
                    .addServers("fdee:1::53")
            )
    }

    return Daemonv1.DomainState.newBuilder()
        .setDomain(domain)
        .setAuthentication(
            AuthenticationStatus.newBuilder()
                .setState(AuthenticationStatus.State.AUTHENTICATED)
                .setAuthenticatedAt(getTimestamp(Instant.now().minusSeconds(7200)))
        )
        .setConnection(connection)
        .setSettings(Daemonv1.DomainSettings.newBuilder().setDomain(domain))
        .build()
}

fun getLoggedOutDomain(domain: String): Daemonv1.DomainState = Daemonv1.DomainState.newBuilder()
    .setDomain(domain)
    .setAuthentication(AuthenticationStatus.newBuilder().setState(AuthenticationStatus.State.LOGGED_OUT))
    .setConnection(ConnectionStatus.newBuilder().setState(ConnectionStatus.State.DISCONNECTED))
    .setSettings(Daemonv1.DomainSettings.newBuilder().setDomain(domain))
    .build()

class FakeDaemon(
    private val statusStore: StatusStore,
    domains: List<Daemonv1.DomainState> = emptyList(),
) : LocalTransport {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val domains = LinkedHashMap(domains.associateBy { it.domain })
    private var revision = 1L

    @Synchronized
    fun getStatus(): Daemonv1.GetStatusResponse = Daemonv1.GetStatusResponse.newBuilder()
        .setInstanceID("instance")
        .setRevision(revision)
        .addAllDomains(domains.values.sortedBy { it.domain })
        .build()

    @Synchronized
    fun setDomain(arg: Daemonv1.DomainState) {
        domains[arg.domain] = arg
        revision++
        statusStore.update(getStatus())
    }

    private fun setConnection(domain: String, state: ConnectionStatus.State): ByteArray {
        val cur = domains[domain] ?: throw getStatusException(Status.Code.NOT_FOUND.value(), "Unknown Cluster domain: $domain")
        setDomain(cur.toBuilder().setConnection(ConnectionStatus.newBuilder().setState(state)).build())
        return getOperation(domain, Daemonv1.Operation.Type.CONNECT).toByteArray()
    }

    private fun getOperation(domain: String, type: Daemonv1.Operation.Type): Daemonv1.Operation =
        Daemonv1.Operation.newBuilder()
            .setId("op-$revision")
            .setDomain(domain)
            .setType(type)
            .setState(Daemonv1.Operation.State.SUCCEEDED)
            .build()

    override suspend fun call(method: String, request: ByteArray): ByteArray {
        calls.add(method)

        return when (method) {
            "GetStatus" -> getStatus().toByteArray()
            "Connect" -> setConnection(Daemonv1.ConnectRequest.parseFrom(request).domain, ConnectionStatus.State.CONNECTED)
            "Disconnect" -> setConnection(
                Daemonv1.DisconnectRequest.parseFrom(request).domain,
                ConnectionStatus.State.DISCONNECTED,
            )

            "Logout" -> {
                val domain = Daemonv1.LogoutRequest.parseFrom(request).domain
                setDomain(getLoggedOutDomain(domain))
                getOperation(domain, Daemonv1.Operation.Type.LOGOUT).toByteArray()
            }

            "UpdateDomainSettings" -> {
                val req = Daemonv1.UpdateDomainSettingsRequest.parseFrom(request)
                val settings = req.settings.toBuilder().setDomain(req.domain).build()
                val cur = domains[req.domain] ?: getLoggedOutDomain(req.domain)
                setDomain(cur.toBuilder().setSettings(settings).build())
                settings.toByteArray()
            }

            "GetAPICredential" -> Daemonv1.GetAPICredentialResponse.newBuilder()
                .setAccessToken("token")
                .build()
                .toByteArray()

            "SetNetworkState" -> Mobilev1.SetNetworkStateResponse.getDefaultInstance().toByteArray()
            else -> throw getStatusException(Status.Code.UNIMPLEMENTED.value(), "Unimplemented: $method")
        }
    }
}

class FakeRuntime(initial: RuntimeState) : ClientRuntime {
    override val state: StateFlow<RuntimeState> = MutableStateFlow(initial)

    override fun start() {}

    override fun reset() {}
}

fun getReadyState(transport: LocalTransport): RuntimeState.Ready = RuntimeState.Ready(
    client = LocalClient(transport),
    info = Mobilev1.GetInfoResponse.newBuilder()
        .setVersion("v0.44.0")
        .setApiMajorVersion(1)
        .setInstanceID("instance")
        .setAuthenticationCallbackURL(TEST_CALLBACK_URL)
        .build(),
)

val TEST_SERVICES: List<Userv1.Service> = listOf(
    Triple("portal.default", Userv1.Service.Spec.Type.WEB, true),
    Triple("api.production", Userv1.Service.Spec.Type.HTTP, false),
    Triple("ssh.infra", Userv1.Service.Spec.Type.SSH, false),
    Triple("postgres.production", Userv1.Service.Spec.Type.POSTGRES, false),
    Triple("k8s.infra", Userv1.Service.Spec.Type.KUBERNETES, false),
).mapIndexed { idx, (name, type, isPublic) ->
    Userv1.Service.newBuilder()
        .setMetadata(
            Metav1.Metadata.newBuilder()
                .setUid("uid-$idx")
                .setName(name)
                .setDisplayName(if (idx == 0) "Company Portal" else "")
                .setDescription(if (idx == 1) "The main HTTP API" else "")
        )
        .setSpec(Userv1.Service.Spec.newBuilder().setType(type).setPort(8080 + idx).setIsTLS(idx % 2 == 0).setIsPublic(isPublic))
        .setStatus(
            Userv1.Service.Status.newBuilder()
                .setNamespace(name.substringAfter("."))
                .setPrimaryHostname(name.substringBefore(".") + if (name.endsWith(".default")) "" else "-" + name.substringAfter("."))
                .addAddresses("fdee:1::${idx + 10}")
        )
        .build()
}

class FakeUserService : MainServiceGrpcKt.MainServiceCoroutineImplBase() {
    override suspend fun listService(request: Userv1.ListServiceOptions): Userv1.ServiceList =
        Userv1.ServiceList.newBuilder()
            .addAllItems(
                TEST_SERVICES.filter {
                    request.namespace.isEmpty() || it.status.namespace == request.namespace
                },
            )
            .setListResponseMeta(Metav1.ListResponseMeta.newBuilder().setHasMore(false))
            .build()

    override suspend fun listNamespace(request: Userv1.ListNamespaceOptions): Userv1.NamespaceList =
        Userv1.NamespaceList.newBuilder()
            .addAllItems(
                TEST_SERVICES.map { it.status.namespace }.distinct().map {
                    Userv1.Namespace.newBuilder().setMetadata(Metav1.Metadata.newBuilder().setName(it)).build()
                },
            )
            .build()

    override suspend fun getStatus(request: Userv1.GetStatusRequest): Userv1.GetStatusResponse =
        Userv1.GetStatusResponse.newBuilder()
            .setDomain("example.com")
            .setUser(
                Userv1.GetStatusResponse.User.newBuilder()
                    .setMetadata(Metav1.Metadata.newBuilder().setName("alice"))
                    .setSpec(Userv1.GetStatusResponse.User.Spec.newBuilder().setEmail("alice@example.com"))
            )
            .setSession(
                Userv1.GetStatusResponse.Session.newBuilder()
                    .setMetadata(Metav1.Metadata.newBuilder().setName("alice-7c1d2a"))
                    .setStatus(Userv1.GetStatusResponse.Session.Status.newBuilder().setIsConnected(true))
            )
            .setCluster(Userv1.GetStatusResponse.Cluster.newBuilder().setMetadata(Metav1.Metadata.newBuilder().setName("default")))
            .build()
}
