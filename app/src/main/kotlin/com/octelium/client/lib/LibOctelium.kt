package com.octelium.client.lib

import com.octelium.client.core.local.LocalTransport
import com.octelium.client.core.local.getStatusException
import com.octelium.client.core.tunnel.RequestCompleter
import io.grpc.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import octelium.api.client.mobile.v1.Mobilev1
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

const val ABI_VERSION = 1

class LibraryUnavailableException(message: String) : Exception(message)

class LibOctelium private constructor(
    private val handle: Long,
    private val context: Long,
) : LocalTransport, RequestCompleter, Closeable {

    private val isClosed = AtomicBoolean(false)

    override suspend fun call(method: String, request: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        if (isClosed.get()) {
            throw getStatusException(Status.Code.UNAVAILABLE.value(), "liboctelium is closed")
        }

        val ret = Native.call(handle, method, request)
        if (ret.code != 0) {
            throw getStatusException(ret.code, ret.message)
        }

        ret.data ?: ByteArray(0)
    }

    override fun complete(requestID: Long, response: ByteArray): Int {
        if (isClosed.get()) {
            return Status.Code.UNAVAILABLE.value()
        }

        return Native.completeRequest(handle, requestID, response)
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            Native.freeClient(handle, context)
        }
    }

    companion object {
        @Volatile
        private var isLoaded = false

        @Synchronized
        fun load(path: String = "liboctelium.so", loadLibraries: () -> Unit = ::loadSystemLibraries) {
            if (isLoaded) {
                return
            }

            try {
                loadLibraries()
            } catch (err: UnsatisfiedLinkError) {
                throw LibraryUnavailableException("liboctelium is not bundled with this build: ${err.message}")
            }

            Native.open(path)?.let {
                throw LibraryUnavailableException("Could not load liboctelium: $it")
            }

            val abiVersion = Native.abiVersion()
            if (abiVersion != ABI_VERSION) {
                throw LibraryUnavailableException(
                    "liboctelium implements the C ABI version $abiVersion while this application requires the version $ABI_VERSION",
                )
            }

            isLoaded = true
        }

        fun create(config: Mobilev1.Config, callbacks: NativeCallbacks): LibOctelium {
            val ret = Native.newClient(config.toByteArray(), callbacks)
            if (ret.code != 0) {
                throw getStatusException(ret.code, ret.message)
            }

            return LibOctelium(ret.handle, ret.context)
        }

        private fun loadSystemLibraries() {
            System.loadLibrary("octelium")
            System.loadLibrary("octelium_jni")
        }
    }
}
