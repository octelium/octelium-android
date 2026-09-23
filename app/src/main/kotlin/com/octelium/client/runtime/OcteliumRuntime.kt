package com.octelium.client.runtime

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.protobuf.ByteString
import com.octelium.client.BuildConfig
import com.octelium.client.core.local.EventHandler
import com.octelium.client.core.local.LocalClient
import com.octelium.client.core.local.LogStore
import com.octelium.client.core.local.StatusStore
import com.octelium.client.core.local.checkInfo
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.core.security.InstallationID
import com.octelium.client.core.security.StateKeyStore
import com.octelium.client.core.security.StateKeyUnavailableException
import com.octelium.client.core.tunnel.PlatformRequestHandler
import com.octelium.client.core.tunnel.TunnelHost
import com.octelium.client.lib.LibOctelium
import com.octelium.client.lib.LibraryUnavailableException
import com.octelium.client.lib.NativeCallbacks
import com.octelium.client.security.KeystoreKeyWrapper
import io.grpc.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import octelium.api.client.mobile.v1.Mobilev1
import java.io.File

sealed interface RuntimeState {
    data object Loading : RuntimeState

    data class Ready(
        val client: LocalClient,
        val info: Mobilev1.GetInfoResponse,
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

    suspend fun awaitInfo(): Mobilev1.GetInfoResponse {
        awaitClient()
        return (state.value as RuntimeState.Ready).info
    }
}

private const val LOG_TAG = "liboctelium"

@OptIn(ExperimentalCoroutinesApi::class)
class OcteliumRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val statusStore: StatusStore,
    private val logStore: LogStore,
    private val tunnelHost: TunnelHost,
) : ClientRuntime {
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Loading)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val requestDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val stateDir = File(context.noBackupFilesDir, "octelium")
    private val keyStore = StateKeyStore(
        keyFile = File(context.noBackupFilesDir, "state-key"),
        stateDir = stateDir,
        wrapper = KeystoreKeyWrapper(context),
    )

    private var lib: LibOctelium? = null

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
                    lib?.close()
                    lib = null
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
            Log.w(LOG_TAG, "Could not start liboctelium", err)
            RuntimeState.Failed(getErrorMessage(err), isResettable = false)
        }
    }

    private suspend fun startClient(): RuntimeState {
        val stateKey = keyStore.getOrCreate()

        val cfg = Mobilev1.Config.newBuilder()
            .setPlatform(Mobilev1.Config.Platform.ANDROID)
            .setStateDir(stateDir.path)
            .setStateKey(ByteString.copyFrom(stateKey))
            .setDevice(
                Mobilev1.Config.Device.newBuilder()
                    .setId(InstallationID(File(context.noBackupFilesDir, "installation-id")).get())
                    .setName(getDeviceName())
            )
            .setLogLevel(if (BuildConfig.DEBUG) Mobilev1.Log.Level.DEBUG else Mobilev1.Log.Level.INFO)
            .build()

        stateKey.fill(0)

        val callbacks = RuntimeCallbacks(EventHandler(statusStore, logStore, ::writeLog))

        val ret = try {
            LibOctelium.create(cfg, callbacks)
        } catch (err: Exception) {
            return RuntimeState.Failed(
                "Could not open the local Octelium state: ${getErrorMessage(err)}",
                isResettable = true,
            )
        }

        callbacks.requestHandler = PlatformRequestHandler(tunnelHost, ret)
        lib = ret

        return try {
            val client = LocalClient(ret)
            val info = client.getInfo()

            checkInfo(info)?.let {
                return RuntimeState.Failed(it, isResettable = false)
            }

            statusStore.update(client.getStatus())

            RuntimeState.Ready(client, info)
        } catch (err: Exception) {
            RuntimeState.Failed(getErrorMessage(err), isResettable = false)
        }
    }

    private fun getDeviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.ifBlank { null }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun writeLog(log: Mobilev1.Log) {
        val priority = when (log.level) {
            Mobilev1.Log.Level.DEBUG -> Log.DEBUG
            Mobilev1.Log.Level.WARN -> Log.WARN
            Mobilev1.Log.Level.ERROR -> Log.ERROR
            else -> Log.INFO
        }

        Log.println(priority, LOG_TAG, log.message)
    }

    private inner class RuntimeCallbacks(private val eventHandler: EventHandler) : NativeCallbacks {
        @Volatile
        var requestHandler: PlatformRequestHandler? = null

        override fun onEvent(data: ByteArray) {
            eventHandler.handle(data)
        }

        override fun onRequest(requestID: Long, data: ByteArray) {
            val handler = requestHandler ?: return

            scope.launch(requestDispatcher) {
                handler.handle(requestID, data)
            }
        }
    }
}
