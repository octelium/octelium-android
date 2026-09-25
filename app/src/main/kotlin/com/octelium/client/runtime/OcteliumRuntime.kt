package com.octelium.client.runtime

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.octelium.client.BuildConfig
import com.octelium.client.core.auth.DeviceInfo
import com.octelium.client.core.client.OcteliumClient
import com.octelium.client.core.cluster.ChannelFactory
import com.octelium.client.core.db.DB
import com.octelium.client.core.db.DBException
import com.octelium.client.core.local.LocalClient
import com.octelium.client.core.local.LogEntry
import com.octelium.client.core.local.LogLevel
import com.octelium.client.core.local.LogStore
import com.octelium.client.core.local.Logger
import com.octelium.client.core.local.StatusStore
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.core.security.InstallationID
import com.octelium.client.core.security.StateKeyStore
import com.octelium.client.core.security.StateKeyUnavailableException
import com.octelium.client.core.tunnel.TunnelHost
import com.octelium.client.lib.LibOctelium
import com.octelium.client.lib.LibraryUnavailableException
import com.octelium.client.security.KeystoreKeyWrapper
import io.grpc.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class RuntimeInfo(
    val version: String,
    val abiVersion: Int,
    val instanceID: String,
)

sealed interface RuntimeState {
    data object Loading : RuntimeState

    data class Ready(
        val client: LocalClient,
        val info: RuntimeInfo,
    ) : RuntimeState

    data class Failed(
        val message: String,
        val isResettable: Boolean,
    ) : RuntimeState
}

interface ClientRuntime {
    val state: StateFlow<RuntimeState>

    fun start()

    fun reset()

    suspend fun awaitClient(): LocalClient = when (val ret = state.first { it !is RuntimeState.Loading }) {
        is RuntimeState.Ready -> ret.client
        is RuntimeState.Failed -> throw Status.UNAVAILABLE.withDescription(ret.message).asException()
        RuntimeState.Loading -> throw IllegalStateException()
    }
}

private const val LOG_TAG = "liboctelium"

class OcteliumRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val statusStore: StatusStore,
    private val logStore: LogStore,
    private val tunnelHost: TunnelHost,
    private val channels: ChannelFactory,
) : ClientRuntime {
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Loading)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val mutex = Mutex()

    private val stateDir = File(context.noBackupFilesDir, "octelium")
    private val keyStore = StateKeyStore(
        keyFile = File(context.noBackupFilesDir, "state-key"),
        stateDir = stateDir,
        wrapper = KeystoreKeyWrapper(context),
    )

    private val logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO

    private var client: OcteliumClient? = null

    override fun start() {
        scope.launch {
            mutex.withLock {
                doStart()
            }
        }
    }

    override fun reset() {
        scope.launch {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    client?.close()
                    client = null
                    statusStore.reset()
                    logStore.clear()
                    keyStore.reset()
                }
                doStart()
            }
        }
    }

    private suspend fun doStart() = withContext(Dispatchers.IO) {
        _state.value = RuntimeState.Loading
        _state.value = try {
            LibOctelium.load()
            startClient()
        } catch (err: LibraryUnavailableException) {
            RuntimeState.Failed(getErrorMessage(err), isResettable = false)
        } catch (err: StateKeyUnavailableException) {
            Log.w(LOG_TAG, "Could not get the state key", err)
            RuntimeState.Failed(
                "The local Octelium state cannot be decrypted on this device. ${err.message}",
                isResettable = true,
            )
        } catch (err: Exception) {
            Log.w(LOG_TAG, "Could not start the Octelium client", err)
            RuntimeState.Failed(getErrorMessage(err), isResettable = false)
        }
    }

    private suspend fun startClient(): RuntimeState {
        val stateKey = keyStore.getOrCreate()

        val ret = try {
            OcteliumClient(
                db = DB(stateDir, stateKey),
                device = DeviceInfo(
                    installationID = InstallationID(File(context.noBackupFilesDir, "installation-id")).get(),
                    name = getDeviceName(),
                ),
                channels = channels,
                tunnels = { LibOctelium.create(it, logLevel) },
                host = tunnelHost,
                onStatus = statusStore::update,
                logger = Logger(logLevel, ::writeLog),
            )
        } catch (err: DBException) {
            return RuntimeState.Failed(
                "Could not open the local Octelium state: ${getErrorMessage(err)}",
                isResettable = true,
            )
        } finally {
            stateKey.fill(0)
        }

        client = ret

        statusStore.update(ret.getStatus())

        return RuntimeState.Ready(
            ret,
            RuntimeInfo(
                version = LibOctelium.getVersion(),
                abiVersion = LibOctelium.getABIVersion(),
                instanceID = ret.instanceID,
            ),
        )
    }

    private fun getDeviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.ifBlank { null }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun writeLog(log: LogEntry) {
        logStore.add(log)

        val priority = when (log.level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
            LogLevel.INFO -> Log.INFO
        }

        Log.println(priority, LOG_TAG, log.message)
    }
}
