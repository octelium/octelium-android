package com.octelium.client.core.local

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException

interface LocalTransport {
    suspend fun call(method: String, request: ByteArray): ByteArray
}

fun getStatusException(code: Int, message: String): StatusException {
    val status = if (code in 1..16) {
        Status.fromCodeValue(code)
    } else {
        Status.UNKNOWN
    }

    return status.withDescription(message).asException()
}

fun getStatusCode(err: Throwable): Status.Code? = when (err) {
    is StatusException -> err.status.code
    is StatusRuntimeException -> err.status.code
    else -> null
}

fun getErrorMessage(err: Throwable): String {
    val ret = when (err) {
        is StatusException -> err.status.description ?: err.status.code.name
        is StatusRuntimeException -> err.status.description ?: err.status.code.name
        else -> err.message ?: err.javaClass.simpleName
    }

    return ret.ifBlank { "Unknown error" }
}
