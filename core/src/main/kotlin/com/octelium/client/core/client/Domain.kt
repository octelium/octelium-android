package com.octelium.client.core.client

import com.google.protobuf.Timestamp
import com.octelium.client.core.auth.AppAuthenticator
import com.octelium.client.core.auth.AuthenticationRequiredException
import com.octelium.client.core.auth.WEB_AUTHENTICATION_TIMEOUT
import com.octelium.client.core.auth.getAccessTokenExpiresAt
import com.octelium.client.core.auth.getAccessTokenRenewAt
import com.octelium.client.core.auth.getRefreshTokenExpiresAt
import com.octelium.client.core.auth.hasValidRefreshToken
import com.octelium.client.core.auth.needsNewAccessToken
import com.octelium.client.core.cluster.AUTH_METADATA_KEY
import com.octelium.client.core.connect.ConnectEvent
import com.octelium.client.core.connect.Connection
import com.octelium.client.core.connect.Connector
import com.octelium.client.core.db.DBException
import com.octelium.client.core.domain.toTimestamp
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.core.local.getStatusCode
import io.grpc.Metadata
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import octelium.api.client.config.v1.Configv1
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.daemon.v1.Daemonv1.AuthenticationStatus
import octelium.api.client.daemon.v1.Daemonv1.ConnectionOptions
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import octelium.api.main.user.v1.MainServiceGrpcKt
import octelium.api.main.user.v1.Userv1
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

val CLUSTER_CALL_TIMEOUT: Duration = Duration.ofSeconds(10)
val DISCONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
val API_CREDENTIAL_TIMEOUT: Duration = Duration.ofSeconds(30)

val REFRESH_MIN_INTERVAL: Duration = Duration.ofMinutes(1)
val REFRESH_MAX_INTERVAL: Duration = Duration.ofHours(1)

private val authMetadataKey: Metadata.Key<String> =
    Metadata.Key.of(AUTH_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER)

internal class DomainController(private val c: OcteliumClient, val domain: String) {
    private var authState = AuthenticationStatus.State.LOGGED_OUT
    private var authenticatedAt: Timestamp? = null
    private var authExpiresAt: Timestamp? = null
    private var appAuth: AppAuthenticator? = null

    private var connState = ConnectionStatus.State.DISCONNECTED
    private var connectedAt: Timestamp? = null
    private var connection: Connection? = null
    private var connOpts: ConnectionOptions? = null

    var settings: Daemonv1.DomainSettings? = null
    private var op: Operation? = null
    private var lastErr: Daemonv1.Error? = null

    var isDeleting = false
        private set

    private var connGen = 0L
    private var connJob: Job? = null
    private var refreshJob: Job? = null

    private val credMutex = Mutex()

    fun setAuthenticationFromState(itm: Configv1.State.Domain?): Boolean {
        val isValid = hasValidRefreshToken(itm)

        val state = if (isValid) AuthenticationStatus.State.AUTHENTICATED else AuthenticationStatus.State.LOGGED_OUT
        val at = if (isValid) itm?.sessionTokenSetAt else null
        val expiresAt = if (isValid) getRefreshTokenExpiresAt(itm)?.let { toTimestamp(it) } else null

        if (authState == state && authenticatedAt == at && authExpiresAt == expiresAt) {
            return false
        }

        authState = state
        authenticatedAt = at
        authExpiresAt = expiresAt

        return true
    }

    private fun canReconcile(): Boolean {
        if (isDeleting) {
            return false
        }

        return when (authState) {
            AuthenticationStatus.State.AUTHENTICATING,
            AuthenticationStatus.State.LOGGING_OUT -> false

            else -> true
        }
    }

    private fun reloadAuthentication(): Boolean {
        val itm = try {
            c.db.get(domain)
        } catch (err: DBException) {
            c.logger.debug("Could not read the stored credentials of the domain $domain: ${err.message}")
            null
        }

        return setAuthenticationFromState(itm)
    }

    fun toPB(): Daemonv1.DomainState {
        val auth = AuthenticationStatus.newBuilder().setState(authState)
        authenticatedAt?.let { auth.setAuthenticatedAt(it) }
        authExpiresAt?.let { auth.setExpiresAt(it) }

        val conn = ConnectionStatus.newBuilder().setState(connState)
        connectedAt?.let { conn.setConnectedAt(it) }
        connOpts?.let { conn.setOptions(it) }
        setConnectionStatusFromConnection(conn, connection)

        val ret = Daemonv1.DomainState.newBuilder()
            .setDomain(domain)
            .setAuthentication(auth)
            .setConnection(conn)
            .setSettings(getDomainSettings())

        lastErr?.let { ret.setLastError(it) }
        op?.let { ret.setLastOperation(it.toPB()) }

        return ret.build()
    }

    private fun getDomainSettings(): Daemonv1.DomainSettings =
        settings ?: Daemonv1.DomainSettings.newBuilder().setDomain(domain).build()

    private fun beginOperation(
        type: Daemonv1.Operation.Type,
        cancelFn: (() -> Unit)?,
        canSupersede: Boolean = false,
        onBegin: (Operation) -> Unit = {},
    ): Operation {
        val (ret, cancelActiveFn) = synchronized(c.lock) {
            if (isDeleting && type != Daemonv1.Operation.Type.DELETE) {
                throw Status.FAILED_PRECONDITION.withDescription("The domain $domain is being deleted").asException()
            }

            var cancelActiveFn: (() -> Unit)? = null

            op?.takeIf { !it.isDone() }?.let {
                if (!canSupersede || !it.isCancellable()) {
                    throw Status.FAILED_PRECONDITION
                        .withDescription("There is already an active Operation for the domain $domain")
                        .asException()
                }

                cancelActiveFn = it.cancelFn
                it.setCanceled("The Operation was superseded")
            }

            c.pruneOperations()

            val ret = Operation(domain, type, cancelFn)
            ret.setState(Daemonv1.Operation.State.RUNNING)

            op = ret
            c.ops[ret.id] = ret

            onBegin(ret)

            c.notifyLocked()

            ret to cancelActiveFn
        }

        cancelActiveFn?.invoke()

        return ret
    }

    private fun getOperationPB(op: Operation): Daemonv1.Operation = synchronized(c.lock) { op.toPB() }

    fun startAuthenticateBrowser(scopes: List<String>): Daemonv1.Operation {
        val appAuth = AppAuthenticator(domain, scopes)

        var err: Throwable? = null
        val job = c.scope.launch(start = CoroutineStart.LAZY) {
            err = getErr {
                val resp = appAuth.wait()
                c.authenticator.authenticate(domain, resp.authenticationToken, scopes, appAuth.codeVerifier)
            }
        }

        val op = startOperation(job, Daemonv1.Operation.Type.AUTHENTICATE) { op ->
            op.action = Daemonv1.Action.newBuilder()
                .setOpenURL(Daemonv1.Action.OpenURL.newBuilder().setUrl(appAuth.getLoginURL()))
                .setExpiresAt(toTimestamp(Instant.now().plus(WEB_AUTHENTICATION_TIMEOUT)))
                .build()
            op.setState(Daemonv1.Operation.State.WAITING_FOR_USER)
            this.appAuth = appAuth
            authState = AuthenticationStatus.State.AUTHENTICATING
            lastErr = null
        }

        job.invokeOnCompletion { finishAuthenticate(op, err ?: it) }
        job.start()

        return getOperationPB(op)
    }

    fun completeAuthentication(op: Operation, callbackURL: String): Daemonv1.Operation {
        val appAuth = synchronized(c.lock) { getWaitingAppAuth(op) }

        val resp = try {
            appAuth.getLoginResponse(callbackURL)
        } catch (err: IllegalArgumentException) {
            throw invalidArgument("Invalid authentication callback URL: ${err.message}")
        }

        synchronized(c.lock) {
            if (getWaitingAppAuth(op) !== appAuth) {
                throw getNotWaitingException(op)
            }

            try {
                appAuth.complete(resp)
            } catch (err: IllegalStateException) {
                throw Status.FAILED_PRECONDITION.withDescription(err.message).asException()
            }

            this.appAuth = null
            op.action = null
            op.setState(Daemonv1.Operation.State.RUNNING)
            c.notifyLocked()

            return op.toPB()
        }
    }

    private fun getWaitingAppAuth(op: Operation): AppAuthenticator {
        val ret = appAuth
        if (this.op !== op || op.state != Daemonv1.Operation.State.WAITING_FOR_USER || ret == null) {
            throw getNotWaitingException(op)
        }

        return ret
    }

    private fun getNotWaitingException(op: Operation) = Status.FAILED_PRECONDITION
        .withDescription("The Operation ${op.id} is not waiting for an authentication callback")
        .asException()

    fun startAuthenticateToken(authenticationToken: String, scopes: List<String>): Daemonv1.Operation {
        var err: Throwable? = null
        val job = c.scope.launch(start = CoroutineStart.LAZY) {
            err = getErr { c.authenticator.authenticate(domain, authenticationToken, scopes) }
        }

        val op = startOperation(job, Daemonv1.Operation.Type.AUTHENTICATE) {
            authState = AuthenticationStatus.State.AUTHENTICATING
            lastErr = null
        }

        job.invokeOnCompletion { finishAuthenticate(op, err ?: it) }
        job.start()

        return getOperationPB(op)
    }

    private fun startOperation(job: Job, type: Daemonv1.Operation.Type, onBegin: (Operation) -> Unit): Operation =
        try {
            beginOperation(type, { job.cancel() }, onBegin = onBegin)
        } catch (err: Exception) {
            job.cancel()
            throw err
        }

    private fun finishAuthenticate(op: Operation, arg: Throwable?) {
        var err = arg

        c.update {
            val isCurrent = this.op === op
            if (isCurrent) {
                appAuth = null
            }

            if (isCurrent || isAuthenticationReleased()) {
                reloadAuthentication()
            }

            if (err == null && authState != AuthenticationStatus.State.AUTHENTICATED) {
                err = IllegalStateException("The Cluster did not provide usable credentials")
            }

            val e = err
            if (e != null) {
                val authErr = getError(e, Daemonv1.Error.Code.AUTHENTICATION_FAILED)
                if (isCurrent && authErr.code != Daemonv1.Error.Code.OPERATION_CANCELED) {
                    lastErr = authErr
                }
                op.setFailed(authErr)
                return@update
            }

            if (isCurrent) {
                lastErr = null
            }
            op.setState(Daemonv1.Operation.State.SUCCEEDED)
        }

        err?.let {
            c.logger.debug("Could not authenticate to the domain $domain: ${getErrorMessage(it)}")
            return
        }

        c.logger.debug("Successfully authenticated to the domain $domain")
    }

    private fun isAuthenticationReleased(): Boolean {
        if (authState != AuthenticationStatus.State.AUTHENTICATING) {
            return false
        }

        val op = this.op
        return op == null || op.isDone() || op.type != Daemonv1.Operation.Type.AUTHENTICATE
    }

    fun startConnect(arg: ConnectionOptions?): Daemonv1.Operation {
        val (curAuthState, curConnState, opts) = synchronized(c.lock) {
            if (canReconcile() && reloadAuthentication()) {
                c.notifyLocked()
            }

            Triple(authState, connState, arg ?: getDomainSettings().connectionOptions)
        }

        if (curAuthState != AuthenticationStatus.State.AUTHENTICATED) {
            throw getUnauthenticatedException()
        }

        when (curConnState) {
            ConnectionStatus.State.DISCONNECTED -> {}
            ConnectionStatus.State.DISCONNECTING -> throw Status.FAILED_PRECONDITION
                .withDescription("The domain $domain is still disconnecting")
                .asException()

            else -> throw Status.FAILED_PRECONDITION
                .withDescription("The domain $domain is already connected")
                .asException()
        }

        val connectOpts = getConnectOptions(opts)

        c.acquireTunnel(domain)

        var gen = 0L
        var runErr: Throwable? = null
        var stopErr: Throwable? = null
        lateinit var op: Operation
        lateinit var job: Job

        val connector = Connector(
            domain = domain,
            opts = connectOpts,
            channels = { c.channels.get(domain) },
            credentials = ::getConnectAccessToken,
            tunnels = c.tunnels,
            host = c.host,
            network = c.network,
            logger = c.logger,
            onEvent = { ev ->
                onConnectEvent(op, gen, ev) {
                    stopErr = it
                    job.cancel()
                }
            },
        )

        job = c.scope.launch(start = CoroutineStart.LAZY) {
            try {
                connector.run()
            } catch (err: CancellationException) {
                return@launch
            } catch (err: Exception) {
                runErr = err
            }
        }

        op = try {
            beginOperation(Daemonv1.Operation.Type.CONNECT, { job.cancel() }) {
                connGen++
                gen = connGen
                connState = ConnectionStatus.State.CONNECTING
                connOpts = normalizeConnectionOptions(opts)
                connJob = job
                lastErr = null
            }
        } catch (err: Exception) {
            job.cancel()
            synchronized(c.lock) { c.releaseTunnelLocked(domain) }
            throw err
        }

        job.invokeOnCompletion { finishConnect(op, gen, runErr ?: stopErr) }
        job.start()

        return getOperationPB(op)
    }

    private fun finishConnect(op: Operation, gen: Long, err: Throwable?) {
        stopRefreshLoop()

        c.update {
            c.releaseTunnelLocked(domain)

            if (connGen == gen) {
                connState = ConnectionStatus.State.DISCONNECTED
                connectedAt = null
                connection = null
                connOpts = null
                connJob = null

                if (err != null) {
                    lastErr = getError(err, Daemonv1.Error.Code.CONNECTION_FAILED)
                }
            }

            if (!op.isDone()) {
                if (err != null) {
                    op.setFailed(getError(err, Daemonv1.Error.Code.CONNECTION_FAILED))
                } else {
                    op.setCanceled("The Connection was closed")
                }
            }
        }

        err?.let { c.logger.warn("The Connection of the domain $domain failed: ${getErrorMessage(it)}") }
    }

    private fun onConnectEvent(op: Operation, gen: Long, ev: ConnectEvent, stop: (Throwable) -> Unit) {
        var startRefresh = false
        var stopErr: Throwable? = null

        c.update {
            if (connGen != gen) {
                return@update
            }

            when (ev) {
                is ConnectEvent.Connecting -> connState = ConnectionStatus.State.CONNECTING

                is ConnectEvent.Connected -> {
                    connState = ConnectionStatus.State.CONNECTED
                    connection = ev.connection
                    if (connectedAt == null) {
                        connectedAt = toTimestamp(Instant.now())
                    }
                    lastErr = null
                    op.setState(Daemonv1.Operation.State.SUCCEEDED)
                    startRefresh = true
                }

                is ConnectEvent.Reconnecting -> {
                    connState = ConnectionStatus.State.RECONNECTING
                    connection = ev.connection
                }
            }

            val err = when (ev) {
                is ConnectEvent.Connecting -> ev.err
                is ConnectEvent.Reconnecting -> ev.err
                is ConnectEvent.Connected -> null
            } ?: return@update

            val connErr = getError(err, Daemonv1.Error.Code.CONNECTION_FAILED)
            lastErr = connErr

            if (connErr.code == Daemonv1.Error.Code.AUTHENTICATION_REQUIRED) {
                stopErr = err
                if (canReconcile()) {
                    reloadAuthentication()
                }
            }
        }

        stopErr?.let {
            c.logger.debug("Stopping the Connection of the domain $domain since authentication is required")
            stop(it)
            return
        }

        if (startRefresh) {
            startRefreshLoop()
        }
    }

    fun startDisconnect(): Daemonv1.Operation {
        val op = beginOperation(Daemonv1.Operation.Type.DISCONNECT, null, canSupersede = true)

        val job = synchronized(c.lock) {
            val job = connJob
            if (job != null) {
                connState = ConnectionStatus.State.DISCONNECTING
            }
            c.notifyLocked()
            job
        }

        if (job == null) {
            c.update {
                lastErr = null
                op.setState(Daemonv1.Operation.State.SUCCEEDED)
            }
            return getOperationPB(op)
        }

        c.scope.launch {
            val err = getErr { doDisconnect(job) }

            c.update {
                if (err != null) {
                    val e = getError(err, Daemonv1.Error.Code.INTERNAL)
                    lastErr = e
                    op.setFailed(e)
                    return@update
                }

                lastErr = null
                op.setState(Daemonv1.Operation.State.SUCCEEDED)
            }
        }

        return getOperationPB(op)
    }

    private suspend fun doDisconnect(job: Job) {
        job.cancel()

        val ret = if (withTimeoutOrNull(DISCONNECT_TIMEOUT.toMillis()) { job.join() } == null) {
            c.logger.warn("Timed out waiting for the Connection of the domain $domain to be closed")
            IllegalStateException("Timed out waiting for the Connection of the domain $domain to be closed")
        } else {
            null
        }

        if (hasCredentials()) {
            try {
                withTimeoutOrNull(CLUSTER_CALL_TIMEOUT.toMillis()) {
                    val headers = Metadata()
                    headers.put(authMetadataKey, getAccessToken())

                    MainServiceGrpcKt.MainServiceCoroutineStub(c.channels.get(domain))
                        .withDeadlineAfter(CLUSTER_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .disconnect(Userv1.DisconnectRequest.getDefaultInstance(), headers)
                } ?: c.logger.debug("Timed out disconnecting at the Cluster of the domain $domain")
            } catch (err: Exception) {
                if (err is CancellationException) {
                    throw err
                }
                c.logger.debug("Could not disconnect at the Cluster of the domain $domain: ${getErrorMessage(err)}")
            }
        }

        ret?.let { throw it }
    }

    fun startLogout(): Daemonv1.Operation {
        val op = beginOperation(Daemonv1.Operation.Type.LOGOUT, null, canSupersede = true)

        val job = synchronized(c.lock) {
            val job = connJob
            if (job != null) {
                connState = ConnectionStatus.State.DISCONNECTING
            }
            authState = AuthenticationStatus.State.LOGGING_OUT
            c.notifyLocked()
            job
        }

        c.scope.launch {
            val err = getErr { doDisconnectAndLogout(job) }

            c.update {
                reloadAuthentication()

                if (err != null) {
                    val e = getError(err, Daemonv1.Error.Code.INTERNAL)
                    lastErr = e
                    op.setFailed(e)
                    return@update
                }

                lastErr = null
                op.setState(Daemonv1.Operation.State.SUCCEEDED)
            }
        }

        return getOperationPB(op)
    }

    private suspend fun doDisconnectAndLogout(job: Job?) {
        val ret = job?.let { getErr { doDisconnect(it) } }

        doLogout()

        ret?.let { throw it }
    }

    private suspend fun doLogout() {
        stopRefreshLoop()

        credMutex.withLock {
            if (!hasCredentials()) {
                return
            }

            c.authenticator.logout(domain)
        }
    }

    private fun hasCredentials(): Boolean = try {
        c.db.getSessionToken(domain) != null
    } catch (err: DBException) {
        false
    }

    fun startDelete(): Daemonv1.Operation {
        val op = beginOperation(Daemonv1.Operation.Type.DELETE, null, canSupersede = true)

        val job = synchronized(c.lock) {
            isDeleting = true
            val job = connJob
            if (job != null) {
                connState = ConnectionStatus.State.DISCONNECTING
            }
            c.notifyLocked()
            job
        }

        c.scope.launch {
            val err = getErr { doDelete(job) }

            c.update {
                if (err != null) {
                    isDeleting = false
                    reloadAuthentication()
                    val e = getError(err, Daemonv1.Error.Code.INTERNAL)
                    lastErr = e
                    op.setFailed(e)
                    return@update
                }

                c.removeDomainLocked(domain)
                op.setState(Daemonv1.Operation.State.SUCCEEDED)
            }
        }

        return getOperationPB(op)
    }

    private suspend fun doDelete(job: Job?) {
        doDisconnectAndLogout(job)

        try {
            c.db.delete(domain)
        } catch (err: DBException) {
            throw IllegalStateException("Could not delete the local state: ${err.message}", err)
        }
    }

    fun updateSettings(arg: Daemonv1.DomainSettings): Daemonv1.DomainSettings {
        val ret = Daemonv1.DomainSettings.newBuilder()
            .setDomain(domain)
            .setAutoConnect(arg.autoConnect)

        if (arg.hasConnectionOptions()) {
            ret.setConnectionOptions(arg.connectionOptions)
        }

        val settings = ret.build()

        try {
            c.db.setDomainSettings(domain, settings)
        } catch (err: DBException) {
            throw Status.INTERNAL.withDescription("Could not store the settings: ${err.message}").asException()
        }

        c.update {
            this.settings = settings
        }

        return settings
    }

    suspend fun getAPICredential(): Daemonv1.GetAPICredentialResponse {
        val curAuthState = synchronized(c.lock) { authState }

        when (curAuthState) {
            AuthenticationStatus.State.AUTHENTICATED,
            AuthenticationStatus.State.AUTHENTICATING -> {}

            else -> throw getUnauthenticatedException()
        }

        checkNetwork()

        val accessToken = try {
            getAccessTokenWithTimeout()
        } catch (err: Exception) {
            if (err is CancellationException) {
                throw err
            }

            c.update {
                reloadAuthentication()
                lastErr = getError(err, Daemonv1.Error.Code.AUTHENTICATION_FAILED)
            }

            val code = getStatusCode(err)

            throw when {
                err is AuthenticationRequiredException || code == Status.Code.UNAUTHENTICATED ->
                    getUnauthenticatedException()

                code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED -> err

                else -> Status.INTERNAL.withDescription("Could not get an access token: ${getErrorMessage(err)}").asException()
            }
        }

        if (accessToken.isEmpty()) {
            throw getUnauthenticatedException()
        }

        val ret = Daemonv1.GetAPICredentialResponse.newBuilder().setAccessToken(accessToken)

        val itm = try {
            c.db.get(domain)
        } catch (err: DBException) {
            null
        }

        if (itm != null) {
            getAccessTokenExpiresAt(itm)?.let { ret.setExpiresAt(toTimestamp(it)) }

            c.updateIf {
                var isChanged = setAuthenticationFromState(itm)
                if (lastErr != null) {
                    lastErr = null
                    isChanged = true
                }

                isChanged
            }
        }

        return ret.build()
    }

    private suspend fun getConnectAccessToken(): String {
        checkNetwork()
        return getAccessTokenWithTimeout()
    }

    private fun checkNetwork() {
        if (!c.network.isAvailable && isRefreshRequired()) {
            throw Status.UNAVAILABLE
                .withDescription("The network is not available to renew the access token of the domain $domain")
                .asException()
        }
    }

    private suspend fun getAccessTokenWithTimeout(): String = try {
        withTimeout(API_CREDENTIAL_TIMEOUT.toMillis()) { getAccessToken() }
    } catch (err: TimeoutCancellationException) {
        throw Status.DEADLINE_EXCEEDED
            .withDescription("Timed out getting an access token for the domain $domain")
            .asException()
    }

    private suspend fun getAccessToken(): String = credMutex.withLock {
        c.authenticator.getAccessToken(domain)
    }

    private fun getUnauthenticatedException() =
        Status.UNAUTHENTICATED.withDescription("You are not authenticated to the domain $domain").asException()

    private fun isRefreshRequired(): Boolean {
        val itm = try {
            c.db.get(domain)
        } catch (err: DBException) {
            return false
        }

        return hasValidRefreshToken(itm) && needsNewAccessToken(itm)
    }

    private fun startRefreshLoop() {
        synchronized(c.lock) {
            if (refreshJob != null) {
                return
            }

            refreshJob = c.scope.launch {
                while (true) {
                    delay(getRefreshWait().toMillis())

                    if (!c.network.isAvailable) {
                        continue
                    }

                    try {
                        getAPICredential()
                    } catch (err: Exception) {
                        if (err is CancellationException) {
                            throw err
                        }
                        c.logger.debug("Could not renew the access token of the domain $domain: ${getErrorMessage(err)}")
                    }
                }
            }
        }
    }

    private fun getRefreshWait(): Duration {
        val itm = try {
            c.db.get(domain)
        } catch (err: DBException) {
            return REFRESH_MIN_INTERVAL
        }

        val renewAt = getAccessTokenRenewAt(itm) ?: return REFRESH_MAX_INTERVAL

        return Duration.between(Instant.now(), renewAt).coerceIn(REFRESH_MIN_INTERVAL, REFRESH_MAX_INTERVAL)
    }

    private fun stopRefreshLoop() {
        synchronized(c.lock) {
            refreshJob?.cancel()
            refreshJob = null
        }
    }

    suspend fun close() {
        stopRefreshLoop()

        val job = synchronized(c.lock) { connJob } ?: return
        job.cancel()

        if (withTimeoutOrNull(DISCONNECT_TIMEOUT.toMillis()) { job.join() } == null) {
            c.logger.warn("Timed out waiting for the Connection of the domain $domain to be closed")
        }
    }
}

private suspend fun getErr(fn: suspend () -> Unit): Throwable? = try {
    fn()
    null
} catch (err: Exception) {
    err
}
