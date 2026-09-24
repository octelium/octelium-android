package com.octelium.client.network

import android.content.Context
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import com.octelium.client.core.network.HostCheck
import com.octelium.client.core.network.HostResolver
import com.octelium.client.core.network.getHostCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume

class SystemHostResolver(private val context: Context) : HostResolver {

    override suspend fun check(host: String): HostCheck = withContext(Dispatchers.IO) {
        val resolved = try {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        } catch (err: UnknownHostException) {
            emptyList()
        }

        if (resolved.isNotEmpty()) {
            return@withContext getHostCheck(host, resolved, null)
        }

        when (val ret = withTimeoutOrNull(QUERY_TIMEOUT_MS) { query(host) }) {
            null -> getHostCheck(host, resolved, null, "The DNS query timed out")
            else -> ret.fold(
                onSuccess = { getHostCheck(host, resolved, it) },
                onFailure = { getHostCheck(host, resolved, null, it.message) },
            )
        }
    }

    private suspend fun query(host: String): Result<List<String>> = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }

        getDnsResolver().query(
            null,
            host,
            DnsResolver.FLAG_EMPTY,
            Runnable::run,
            signal,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    cont.resume(Result.success(answer.mapNotNull { it.hostAddress }))
                }

                override fun onError(error: DnsResolver.DnsException) {
                    cont.resume(Result.failure(error))
                }
            },
        )
    }

    private fun getDnsResolver(): DnsResolver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
        DnsResolver(context, Looper.getMainLooper())
    } else {
        @Suppress("DEPRECATION")
        DnsResolver.getInstance()
    }

    companion object {
        private const val QUERY_TIMEOUT_MS = 10_000L
    }
}
