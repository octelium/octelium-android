package com.octelium.client.core.cluster

import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.main.meta.v1.Metav1
import octelium.api.main.user.v1.MainServiceGrpcKt
import octelium.api.main.user.v1.Userv1
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val AUTH_METADATA_KEY = "x-octelium-auth"
const val CLUSTER_API_PORT = 443

val CREDENTIAL_EXPIRY_MARGIN: Duration = Duration.ofSeconds(30)

private val authMetadataKey: Metadata.Key<String> =
    Metadata.Key.of(AUTH_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

fun getClusterAPIHost(domain: String): String = "octelium-api.$domain"

fun interface CredentialSource {
    suspend fun getCredential(domain: String): Daemonv1.GetAPICredentialResponse
}

fun interface ChannelFactory {
    fun create(domain: String): ManagedChannel
}

class CredentialCache(private val now: () -> Instant = Instant::now) {
    private class Credential(val accessToken: String, val expiresAt: Instant?)

    private val credentials = HashMap<String, Credential>()

    @Synchronized
    fun get(domain: String): String? {
        val ret = credentials[domain] ?: return null
        val expiresAt = ret.expiresAt ?: return ret.accessToken

        if (now().plus(CREDENTIAL_EXPIRY_MARGIN).isBefore(expiresAt)) {
            return ret.accessToken
        }

        credentials.remove(domain)
        return null
    }

    @Synchronized
    fun set(domain: String, arg: Daemonv1.GetAPICredentialResponse) {
        val expiresAt = if (arg.hasExpiresAt()) {
            Instant.ofEpochSecond(arg.expiresAt.seconds, arg.expiresAt.nanos.toLong())
        } else {
            null
        }

        credentials[domain] = Credential(arg.accessToken, expiresAt)
    }

    @Synchronized
    fun remove(domain: String) {
        credentials.remove(domain)
    }

    @Synchronized
    fun clear() {
        credentials.clear()
    }
}

class ClusterClient(
    private val credentials: CredentialSource,
    private val channels: ChannelFactory,
    private val callTimeout: Duration = Duration.ofSeconds(20),
    now: () -> Instant = Instant::now,
) {
    private val cache = CredentialCache(now)
    private val channelMap = ConcurrentHashMap<String, ManagedChannel>()
    private val credentialMutex = Mutex()

    suspend fun getStatus(domain: String): Userv1.GetStatusResponse = call(domain) { stub, headers ->
        stub.getStatus(Userv1.GetStatusRequest.getDefaultInstance(), headers)
    }

    suspend fun listService(domain: String, options: Userv1.ListServiceOptions): Userv1.ServiceList =
        call(domain) { stub, headers ->
            stub.listService(options, headers)
        }

    suspend fun listNamespace(domain: String, options: Userv1.ListNamespaceOptions): Userv1.NamespaceList =
        call(domain) { stub, headers ->
            stub.listNamespace(options, headers)
        }

    suspend fun listAllServices(
        domain: String,
        namespace: String = "",
        type: Userv1.Service.Spec.Type = Userv1.Service.Spec.Type.UNSET,
    ): List<Userv1.Service> = listAll { page ->
        val resp = listService(
            domain,
            Userv1.ListServiceOptions.newBuilder()
                .setCommon(getCommonListOptions(page, ALL_ITEMS_PER_PAGE))
                .setNamespace(namespace)
                .setType(type)
                .build(),
        )
        resp.itemsList to resp.listResponseMeta
    }

    suspend fun listAllNamespaces(domain: String): List<Userv1.Namespace> = listAll { page ->
        val resp = listNamespace(
            domain,
            Userv1.ListNamespaceOptions.newBuilder()
                .setCommon(getCommonListOptions(page, ALL_ITEMS_PER_PAGE))
                .build(),
        )
        resp.itemsList to resp.listResponseMeta
    }

    fun invalidate(domain: String) {
        cache.remove(domain)
        channelMap.remove(domain)?.shutdown()
    }

    fun close() {
        cache.clear()
        channelMap.values.forEach { it.shutdown() }
        channelMap.clear()
    }

    private suspend fun <T> call(
        domain: String,
        fn: suspend (MainServiceGrpcKt.MainServiceCoroutineStub, Metadata) -> T,
    ): T {
        val channel = channelMap.computeIfAbsent(domain) { channels.create(it) }

        val getStub = {
            MainServiceGrpcKt.MainServiceCoroutineStub(channel)
                .withDeadlineAfter(callTimeout.toMillis(), TimeUnit.MILLISECONDS)
        }

        try {
            return fn(getStub(), getHeaders(domain, false))
        } catch (err: StatusException) {
            if (err.status.code != Status.Code.UNAUTHENTICATED) {
                throw err
            }
        }

        return fn(getStub(), getHeaders(domain, true))
    }

    private suspend fun getHeaders(domain: String, renew: Boolean): Metadata {
        val ret = Metadata()
        ret.put(authMetadataKey, getAccessToken(domain, renew))
        return ret
    }

    private suspend fun getAccessToken(domain: String, renew: Boolean): String {
        credentialMutex.withLock {
            if (!renew) {
                cache.get(domain)?.let { return it }
            }

            val resp = credentials.getCredential(domain)
            if (resp.accessToken.isEmpty()) {
                throw Status.UNAUTHENTICATED
                    .withDescription("You are not authenticated to the domain $domain")
                    .asException()
            }

            cache.set(domain, resp)

            return resp.accessToken
        }
    }

    companion object {
        const val ALL_ITEMS_PER_PAGE = 100
        const val MAX_PAGES = 1000
    }
}

fun getCommonListOptions(page: Int, itemsPerPage: Int): Metav1.CommonListOptions =
    Metav1.CommonListOptions.newBuilder()
        .setPage(page)
        .setItemsPerPage(itemsPerPage)
        .setOrderBy(
            Metav1.CommonListOptions.OrderBy.newBuilder()
                .setType(Metav1.CommonListOptions.OrderBy.Type.NAME)
                .setMode(Metav1.CommonListOptions.OrderBy.Mode.ASC)
        )
        .build()

suspend fun <T> listAll(fn: suspend (Int) -> Pair<List<T>, Metav1.ListResponseMeta?>): List<T> {
    val ret = ArrayList<T>()

    for (page in 0 until ClusterClient.MAX_PAGES) {
        val (items, meta) = fn(page)
        ret.addAll(items)

        if (meta == null || !meta.hasMore || items.isEmpty()) {
            return ret
        }
    }

    throw IllegalStateException("The list exceeded the supported pagination range")
}

fun getSessionKeys(status: Daemonv1.GetStatusResponse?): Map<String, String> =
    status?.domainsList.orEmpty().associate { itm ->
        val authenticatedAt = itm.authentication.authenticatedAt
        itm.domain to "${itm.authentication.stateValue}:${authenticatedAt.seconds}:${authenticatedAt.nanos}"
    }

fun getChangedSessions(previous: Map<String, String>, current: Map<String, String>): Set<String> {
    val ret = HashSet<String>()

    for ((domain, key) in current) {
        val oldKey = previous[domain]
        if (oldKey != null && oldKey != key) {
            ret.add(domain)
        }
    }

    for (domain in previous.keys) {
        if (!current.containsKey(domain)) {
            ret.add(domain)
        }
    }

    return ret
}
