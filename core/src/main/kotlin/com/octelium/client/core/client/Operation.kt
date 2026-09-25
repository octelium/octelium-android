package com.octelium.client.core.client

import com.google.protobuf.Timestamp
import com.octelium.client.core.domain.toTimestamp
import octelium.api.client.daemon.v1.Daemonv1
import java.time.Instant
import java.util.UUID

internal class Operation(
    val domain: String,
    val type: Daemonv1.Operation.Type,
    var cancelFn: (() -> Unit)?,
) {
    val id: String = UUID.randomUUID().toString()

    var state = Daemonv1.Operation.State.PENDING
        private set

    val createdAt: Timestamp = toTimestamp(Instant.now())

    var updatedAt: Timestamp = createdAt
        private set

    var completedAt: Timestamp? = null
        private set

    var action: Daemonv1.Action? = null

    var err: Daemonv1.Error? = null
        private set

    fun isDone(): Boolean = when (state) {
        Daemonv1.Operation.State.SUCCEEDED,
        Daemonv1.Operation.State.FAILED,
        Daemonv1.Operation.State.CANCELED -> true

        else -> false
    }

    fun isCancellable(): Boolean = cancelFn != null && !isDone()

    fun setState(arg: Daemonv1.Operation.State) {
        if (isDone()) {
            return
        }

        state = arg
        updatedAt = toTimestamp(Instant.now())

        if (isDone()) {
            completedAt = updatedAt
            action = null
            cancelFn = null
        }
    }

    fun setFailed(arg: Daemonv1.Error) {
        if (isDone()) {
            return
        }

        err = arg
        if (arg.code == Daemonv1.Error.Code.OPERATION_CANCELED) {
            setState(Daemonv1.Operation.State.CANCELED)
            return
        }

        setState(Daemonv1.Operation.State.FAILED)
    }

    fun setCanceled(message: String) {
        setFailed(
            Daemonv1.Error.newBuilder()
                .setCode(Daemonv1.Error.Code.OPERATION_CANCELED)
                .setMessage(message)
                .build(),
        )
    }

    fun toPB(): Daemonv1.Operation {
        val ret = Daemonv1.Operation.newBuilder()
            .setId(id)
            .setDomain(domain)
            .setType(type)
            .setState(state)
            .setCreatedAt(createdAt)
            .setUpdatedAt(updatedAt)
            .setCancellable(isCancellable())

        completedAt?.let { ret.setCompletedAt(it) }
        action?.let { ret.setAction(it) }
        err?.let { ret.setError(it) }

        return ret.build()
    }
}
