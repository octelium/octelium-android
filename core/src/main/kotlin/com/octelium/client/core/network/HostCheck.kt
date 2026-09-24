package com.octelium.client.core.network

enum class HostResolution {
    RESOLVED,
    NOT_FOUND,
    REJECTED,
    FAILED,
}

data class HostCheck(
    val host: String,
    val resolution: HostResolution,
    val addresses: List<String> = emptyList(),
    val message: String? = null,
)

fun interface HostResolver {
    suspend fun check(host: String): HostCheck
}

fun getHostCheck(host: String, resolved: List<String>, queried: List<String>?, message: String? = null): HostCheck =
    when {
        resolved.isNotEmpty() -> HostCheck(host, HostResolution.RESOLVED, resolved.distinct())
        queried == null -> HostCheck(host, HostResolution.FAILED, message = message?.ifBlank { null })
        queried.isEmpty() -> HostCheck(host, HostResolution.NOT_FOUND)
        else -> HostCheck(host, HostResolution.REJECTED, queried.distinct())
    }

fun getHostResolutionLabel(arg: HostResolution): String = when (arg) {
    HostResolution.RESOLVED -> "Resolved"
    HostResolution.NOT_FOUND -> "Not found"
    HostResolution.REJECTED -> "Rejected by Android"
    HostResolution.FAILED -> "Failed"
}

fun getHostCheckError(arg: HostCheck): String? = when (arg.resolution) {
    HostResolution.RESOLVED -> null

    HostResolution.NOT_FOUND ->
        "${arg.host} could not be found. Make sure that the Cluster domain is correct and that " +
            "the DNS of the Cluster has a record for ${arg.host}."

    HostResolution.REJECTED ->
        "${arg.host} resolves to ${arg.addresses.joinToString(", ")} but Android refuses its DNS answer " +
            "because the answer includes a name that is not a valid host name, typically a CNAME target " +
            "whose label begins with \"_\". Point ${arg.host} to a valid host name or directly to its " +
            "A/AAAA records."

    HostResolution.FAILED ->
        "Could not resolve ${arg.host}" + (arg.message?.let { ": $it" } ?: ". Check your Internet connection.")
}
