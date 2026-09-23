package com.octelium.client.lib

import androidx.annotation.Keep

@Keep
interface NativeCallbacks {
    fun onEvent(data: ByteArray)

    fun onRequest(requestID: Long, data: ByteArray)
}

@Keep
class NativeResult(
    val code: Int,
    val handle: Long,
    val context: Long,
    val data: ByteArray?,
) {
    val message: String
        get() = data?.toString(Charsets.UTF_8).orEmpty()
}

@Keep
object Native {
    @JvmStatic
    external fun open(path: String): String?

    @JvmStatic
    external fun abiVersion(): Int

    @JvmStatic
    external fun newClient(config: ByteArray, callbacks: NativeCallbacks): NativeResult

    @JvmStatic
    external fun call(client: Long, method: String, request: ByteArray): NativeResult

    @JvmStatic
    external fun completeRequest(client: Long, requestID: Long, response: ByteArray): Int

    @JvmStatic
    external fun freeClient(client: Long, context: Long)
}
