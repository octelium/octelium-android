package com.octelium.client.core.client

import com.octelium.client.core.auth.Authenticator
import com.octelium.client.core.auth.DeviceInfo
import com.octelium.client.core.cluster.ChannelFactory
import com.octelium.client.core.db.DB
import com.octelium.client.core.domain.toInstant
import com.octelium.client.core.domain.toTimestamp
import com.octelium.client.core.local.LocalClient
import com.octelium.client.core.local.Logger
import com.octelium.client.core.network.NetworkWatcher
import com.octelium.client.core.tunnel.TunnelFactory
import com.octelium.client.core.tunnel.TunnelHost
import io.grpc.ManagedChannel
import io.grpc.Status
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import octelium.api.client.daemon.v1.Daemonv1
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val OPERATION_RETENTION: Duration = Duration.ofMinutes(10)

class OcteliumClient(
    internal val db: DB,
    device: DeviceInfo,
    channels: ChannelFactory,
    internal val tunnels: TunnelFactory,
    internal val host: TunnelHost,
    private val onStatus: (Daemonv1.GetStatusResponse) -> Unit = {},
    internal val logger: Logger = Logger(),
    internal val network: NetworkWatcher = NetworkWatcher(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalClient {
    val instanceID: String = UUID.randomUUID().toString()

    internal val lock = Any()
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)
    internal val channels = ClusterChannels(channels)
    internal val authenticator = Authenticator(db, this.channels::get, device, logger)

    private var revision = 0L
    private val domains = HashMap<String, DomainController>()
    internal val ops = HashMap<String, Operation>()
    private var tunnelDomain: String? = null
    private var isClosed = false

    init {
        db.migrate()
        loadDomains()
    }

    override suspend fun getStatus(): Daemonv1.GetStatusResponse = call {
        synchronized(lock) { getStatusLocked() }
    }

    override suspend fun authenticateBrowser(domain: String): Daemonv1.Operation = call {
        getDomain(canonicalizeDomain(domain)).startAuthenticateBrowser(emptyList())
    }

    override suspend fun authenticateToken(domain: String, authenticationToken: String): Daemonv1.Operation = call {
        val canonical = canonicalizeDomain(domain)

        if (authenticationToken.isEmpty()) {
            throw invalidArgument("The authentication Token is not set")
        }

        getDomain(canonical).startAuthenticateToken(authenticationToken, emptyList())
    }

    override suspend fun completeAuthentication(operationID: String, callbackURL: String): Daemonv1.Operation =
        call {
            if (operationID.isEmpty()) {
                throw invalidArgument("The Operation ID is not set")
            }

            if (callbackURL.isEmpty()) {
                throw invalidArgument("The callback URL is not set")
            }

            val (op, d) = synchronized(lock) {
                val op = ops[operationID]
                    ?: throw Status.NOT_FOUND.withDescription("Unknown Operation: $operationID").asException()
                op to domains[op.domain]
            }

            if (d == null) {
                throw Status.NOT_FOUND.withDescription("Unknown Cluster domain: ${op.domain}").asException()
            }

            d.completeAuthentication(op, callbackURL)
        }

    override suspend fun connect(domain: String): Daemonv1.Operation = call {
        findDomain(domain).startConnect(null)
    }

    override suspend fun disconnect(domain: String): Daemonv1.Operation = call {
        findDomain(domain).startDisconnect()
    }

    override suspend fun logout(domain: String): Daemonv1.Operation = call {
        findDomain(domain).startLogout()
    }

    override suspend fun deleteDomain(domain: String): Daemonv1.Operation = call {
        findDomain(domain).startDelete()
    }

    override suspend fun getOperation(id: String): Daemonv1.Operation = call {
        if (id.isEmpty()) {
            throw invalidArgument("The Operation ID is not set")
        }

        synchronized(lock) {
            pruneOperations()

            ops[id]?.toPB() ?: throw Status.NOT_FOUND.withDescription("Unknown Operation: $id").asException()
        }
    }

    override suspend fun cancelOperation(id: String): Daemonv1.Operation = call {
        if (id.isEmpty()) {
            throw invalidArgument("The Operation ID is not set")
        }

        val (ret, cancelFn) = synchronized(lock) {
            val op = ops[id] ?: throw Status.NOT_FOUND.withDescription("Unknown Operation: $id").asException()

            when {
                op.isDone() -> op.toPB() to null

                !op.isCancellable() -> throw Status.FAILED_PRECONDITION
                    .withDescription("The ${op.type.name} Operation cannot be canceled")
                    .asException()

                else -> {
                    val cancelFn = op.cancelFn
                    op.setCanceled("The Operation was canceled")
                    notifyLocked()

                    op.toPB() to cancelFn
                }
            }
        }

        cancelFn?.invoke()

        ret
    }

    override suspend fun getAPICredential(domain: String): Daemonv1.GetAPICredentialResponse = call {
        findDomain(domain).getAPICredential()
    }

    override suspend fun updateDomainSettings(
        domain: String,
        settings: Daemonv1.DomainSettings,
    ): Daemonv1.DomainSettings = call {
        val canonical = canonicalizeDomain(domain)
        getConnectOptions(settings.connectionOptions)
        getDomain(canonical).updateSettings(settings)
    }

    override suspend fun setNetworkState(isAvailable: Boolean, id: String) {
        network.set(isAvailable, id)
    }

    suspend fun close() {
        val domains = synchronized(lock) {
            isClosed = true
            domains.values.toList()
        }

        withContext(dispatcher) {
            domains.forEach { it.close() }
            withTimeoutOrNull(DISCONNECT_TIMEOUT.toMillis()) {
                scope.coroutineContext[Job]?.cancelAndJoin()
            }
            channels.close()
        }
    }

    private suspend fun <T> call(fn: suspend () -> T): T = withContext(dispatcher) {
        synchronized(lock) {
            if (isClosed) {
                throw Status.UNAVAILABLE.withDescription("The client is closed").asException()
            }
        }

        fn()
    }

    internal fun update(fn: () -> Unit) {
        synchronized(lock) {
            fn()
            notifyLocked()
        }
    }

    internal fun updateIf(fn: () -> Boolean) {
        synchronized(lock) {
            if (fn()) {
                notifyLocked()
            }
        }
    }

    internal fun notifyLocked() {
        revision++
        onStatus(getStatusLocked())
    }

    private fun getStatusLocked(): Daemonv1.GetStatusResponse {
        pruneOperations()

        return Daemonv1.GetStatusResponse.newBuilder()
            .setInstanceID(instanceID)
            .setRevision(revision)
            .setUpdatedAt(toTimestamp(Instant.now()))
            .addAllDomains(domains.values.map { it.toPB() }.sortedBy { it.domain })
            .build()
    }

    private fun loadDomains() {
        for ((domain, itm) in db.list()) {
            val canonical = try {
                canonicalizeDomain(domain)
            } catch (err: Exception) {
                logger.warn("Skipping an invalid stored domain: $domain")
                continue
            }

            if (domains.containsKey(canonical)) {
                logger.warn("Skipping a duplicate stored domain: $domain")
                continue
            }

            val d = DomainController(this, canonical)
            d.settings = if (itm.hasSettings()) itm.settings else null
            d.setAuthenticationFromState(itm)

            domains[canonical] = d
        }
    }

    private fun getDomain(domain: String): DomainController {
        synchronized(lock) {
            domains[domain]?.let {
                if (it.isDeleting) {
                    throw Status.FAILED_PRECONDITION.withDescription("The domain $domain is being deleted").asException()
                }
                return it
            }

            val ret = DomainController(this, domain)

            db.get(domain)?.let {
                ret.settings = if (it.hasSettings()) it.settings else null
                ret.setAuthenticationFromState(it)
            }

            domains[domain] = ret

            return ret
        }
    }

    private fun findDomain(arg: String): DomainController {
        val domain = canonicalizeDomain(arg)

        synchronized(lock) {
            val ret = domains[domain]
                ?: throw Status.NOT_FOUND.withDescription("Unknown Cluster domain: $domain").asException()

            if (ret.isDeleting) {
                throw Status.FAILED_PRECONDITION.withDescription("The domain $domain is being deleted").asException()
            }

            return ret
        }
    }

    internal fun removeDomainLocked(domain: String) {
        domains.remove(domain)
    }

    internal fun pruneOperations() {
        val now = Instant.now()

        ops.values.removeAll { op ->
            val completedAt = toInstant(op.completedAt)
            op.isDone() && completedAt != null && Duration.between(completedAt, now) > OPERATION_RETENTION
        }
    }

    internal fun acquireTunnel(domain: String) {
        synchronized(lock) {
            if (isClosed) {
                throw Status.UNAVAILABLE.withDescription("The client is closed").asException()
            }

            tunnelDomain?.let {
                throw Status.FAILED_PRECONDITION
                    .withDescription(
                        "The domain $it is already connected. Only a single domain can be connected at a time",
                    )
                    .asException()
            }

            tunnelDomain = domain
        }
    }

    internal fun releaseTunnelLocked(domain: String) {
        if (tunnelDomain == domain) {
            tunnelDomain = null
        }
    }
}

internal class ClusterChannels(private val factory: ChannelFactory) {
    private val channels = ConcurrentHashMap<String, ManagedChannel>()

    fun get(domain: String): ManagedChannel = channels.computeIfAbsent(domain) { factory.create(it) }

    fun close() {
        channels.values.forEach { it.shutdown() }
        channels.clear()
    }
}
