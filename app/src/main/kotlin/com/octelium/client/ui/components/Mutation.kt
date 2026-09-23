package com.octelium.client.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.octelium.client.core.local.getErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class MutationState<T>(
    private val scope: CoroutineScope,
    private val fn: () -> (suspend (T) -> Unit),
    private val onSuccess: () -> ((T) -> Unit)?,
) {
    var isPending by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var variables by mutableStateOf<T?>(null)
        private set

    var isSuccess by mutableStateOf(false)
        private set

    fun mutate(arg: T) {
        if (isPending) {
            return
        }

        isPending = true
        error = null
        isSuccess = false
        variables = arg

        scope.launch {
            try {
                fn()(arg)
                isSuccess = true
                onSuccess()?.invoke(arg)
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                error = getErrorMessage(err)
            } finally {
                isPending = false
            }
        }
    }

    fun reset() {
        error = null
        isSuccess = false
        variables = null
    }
}

@Composable
fun <T> rememberMutation(
    onSuccess: ((T) -> Unit)? = null,
    fn: suspend (T) -> Unit,
): MutationState<T> {
    val scope = rememberCoroutineScope()
    val currentFn by rememberUpdatedState(fn)
    val currentOnSuccess by rememberUpdatedState(onSuccess)

    return remember { MutationState(scope, { currentFn }, { currentOnSuccess }) }
}
