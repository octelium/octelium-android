package com.octelium.client.core.network

enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
}

data class NetworkInfo(
    val id: String,
    val transport: NetworkTransport,
    val hasInternet: Boolean,
    val isValidated: Boolean,
    val isCaptivePortal: Boolean,
    val isMetered: Boolean,
    val isVPN: Boolean,
)

data class NetworkState(
    val isAvailable: Boolean,
    val id: String,
)

fun getNetworkState(info: NetworkInfo?): NetworkState {
    if (info == null || info.isVPN || !info.hasInternet || info.isCaptivePortal) {
        return NetworkState(isAvailable = false, id = "")
    }

    return NetworkState(isAvailable = true, id = info.id)
}

fun getNetworkLabel(info: NetworkInfo?): String {
    if (info == null) {
        return "No network"
    }

    val transport = when (info.transport) {
        NetworkTransport.WIFI -> "Wi-Fi"
        NetworkTransport.CELLULAR -> "Cellular"
        NetworkTransport.ETHERNET -> "Ethernet"
        NetworkTransport.OTHER -> "Other"
    }

    return when {
        info.isCaptivePortal -> "$transport (sign-in required)"
        !info.isValidated -> "$transport (not validated)"
        else -> transport
    }
}
