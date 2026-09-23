package com.octelium.client.core.cluster

import octelium.api.main.meta.v1.Metav1
import octelium.api.main.user.v1.Userv1
import octelium.api.main.user.v1.Userv1.Service.Spec.Type
import java.util.Locale

fun getServicePrivateFQDN(arg: Userv1.Service, domain: String): String =
    if (arg.status.primaryHostname.isNotEmpty()) {
        "${arg.status.primaryHostname}.local.$domain"
    } else {
        "local.$domain"
    }

fun getServicePublicFQDN(arg: Userv1.Service, domain: String): String =
    if (arg.status.primaryHostname.isNotEmpty()) {
        "${arg.status.primaryHostname}.$domain"
    } else {
        domain
    }

fun getServicePublicURL(arg: Userv1.Service, domain: String): String =
    "https://${getServicePublicFQDN(arg, domain)}"

fun getServiceHostname(arg: Userv1.Service): String =
    arg.status.primaryHostname.ifEmpty { arg.metadata.name }

fun isServiceWebBrowsable(arg: Userv1.Service): Boolean = when (arg.spec.type) {
    Type.WEB, Type.HTTP, Type.RDP_WEB -> true
    else -> false
}

data class ServiceTypeInfo(
    val key: String,
    val type: Type,
    val label: String,
)

val SERVICE_TYPES = listOf(
    ServiceTypeInfo("WEB", Type.WEB, "Web App"),
    ServiceTypeInfo("HTTP", Type.HTTP, "HTTP"),
    ServiceTypeInfo("GRPC", Type.GRPC, "gRPC"),
    ServiceTypeInfo("SSH", Type.SSH, "SSH"),
    ServiceTypeInfo("KUBERNETES", Type.KUBERNETES, "Kubernetes"),
    ServiceTypeInfo("POSTGRES", Type.POSTGRES, "PostgreSQL"),
    ServiceTypeInfo("MYSQL", Type.MYSQL, "MySQL"),
    ServiceTypeInfo("TCP", Type.TCP, "TCP"),
    ServiceTypeInfo("UDP", Type.UDP, "UDP"),
    ServiceTypeInfo("DNS", Type.DNS, "DNS"),
    ServiceTypeInfo("SOCKS5", Type.SOCKS5, "SOCKS5"),
    ServiceTypeInfo("RDP_WEB", Type.RDP_WEB, "RDP Web"),
    ServiceTypeInfo("RDP", Type.RDP, "RDP"),
    ServiceTypeInfo("LLM", Type.LLM, "AI / LLM"),
    ServiceTypeInfo("MCP", Type.MCP, "MCP"),
)

val UNKNOWN_SERVICE_TYPE = ServiceTypeInfo("UNSET", Type.UNSET, "Service")

fun getServiceTypeInfo(arg: Userv1.Service): ServiceTypeInfo =
    SERVICE_TYPES.find { it.type == arg.spec.type } ?: UNKNOWN_SERVICE_TYPE

fun getServiceTypeByKey(key: String?): ServiceTypeInfo? =
    if (key.isNullOrEmpty()) null else SERVICE_TYPES.find { it.key == key }

fun splitServiceName(name: String): Pair<String, String?> {
    val idx = name.indexOf('.')
    if (idx <= 0) {
        return name to null
    }

    return name.substring(0, idx) to name.substring(idx + 1)
}

fun printResourceNameWithDisplay(arg: Metav1.Metadata): String =
    if (arg.displayName.isNotEmpty()) "${arg.name} (${arg.displayName})" else arg.name

fun tokenizeQuery(arg: String): List<String> =
    arg.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }

fun matchesAllTokens(text: String, tokens: List<String>): Boolean {
    val value = text.lowercase(Locale.ROOT)
    return tokens.all { value.contains(it) }
}

fun matchesService(arg: Userv1.Service, tokens: List<String>): Boolean = matchesAllTokens(
    "${arg.metadata.name} ${arg.metadata.displayName} ${arg.metadata.description} ${arg.status.primaryHostname}",
    tokens,
)

fun matchesNamespace(arg: Userv1.Namespace, tokens: List<String>): Boolean = matchesAllTokens(
    "${arg.metadata.name} ${arg.metadata.displayName} ${arg.metadata.description}",
    tokens,
)
