package com.octelium.client.core.tunnel

import octelium.api.client.mobile.v1.Mobilev1
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

enum class IPFamily {
    V4,
    V6,
}

data class IPPrefix(
    val address: InetAddress,
    val prefixLength: Int,
) {
    val family: IPFamily
        get() = getFamily(address)

    fun masked(): IPPrefix {
        val bytes = address.address
        for (i in bytes.indices) {
            val bits = (prefixLength - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and (0xff shl (8 - bits)).toByte().toInt()).toByte()
        }

        return IPPrefix(InetAddress.getByAddress(bytes), prefixLength)
    }

    override fun toString(): String = "${address.hostAddress}/$prefixLength"
}

data class TunnelSpec(
    val addresses: List<IPPrefix>,
    val routes: List<IPPrefix>,
    val dnsServers: List<InetAddress>,
    val searchDomains: List<String>,
    val mtu: Int,
    val bypassFamilies: Set<IPFamily>,
)

class InvalidTunnelConfigurationException(message: String) : IllegalArgumentException(message)

const val MIN_TUNNEL_MTU = 576
const val MIN_IPV6_TUNNEL_MTU = 1280
const val MAX_TUNNEL_MTU = 9000

private val rgxSearchDomain = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

fun getFamily(arg: InetAddress): IPFamily = if (arg is Inet6Address) IPFamily.V6 else IPFamily.V4

private fun parseIPv4(arg: String): ByteArray? {
    val parts = arg.split(".")
    if (parts.size != 4) {
        return null
    }

    val ret = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) {
            return null
        }

        val value = part.toInt()
        if (value > 255) {
            return null
        }

        ret[i] = value.toByte()
    }

    return ret
}

private fun parseIPv6Groups(arg: String): List<Int>? {
    if (arg.isEmpty()) {
        return emptyList()
    }

    val groups = arg.split(":")
    val ret = ArrayList<Int>()

    for ((i, group) in groups.withIndex()) {
        if (i == groups.lastIndex && group.contains('.')) {
            val v4 = parseIPv4(group) ?: return null
            ret.add(((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff))
            ret.add(((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff))
            continue
        }

        if (group.isEmpty() || group.length > 4 || !group.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return null
        }

        ret.add(group.toInt(16))
    }

    return ret
}

private fun parseIPv6(arg: String): ByteArray? {
    val parts = arg.split("::")
    if (parts.size > 2) {
        return null
    }

    val head = parseIPv6Groups(parts[0]) ?: return null

    val groups = if (parts.size == 2) {
        val tail = parseIPv6Groups(parts[1]) ?: return null
        if (head.size + tail.size > 7) {
            return null
        }
        head + List(8 - head.size - tail.size) { 0 } + tail
    } else {
        head
    }

    if (groups.size != 8) {
        return null
    }

    val ret = ByteArray(16)
    for ((i, group) in groups.withIndex()) {
        ret[i * 2] = (group shr 8).toByte()
        ret[i * 2 + 1] = group.toByte()
    }

    return ret
}

fun parseIP(arg: String): InetAddress {
    val invalidErr = InvalidTunnelConfigurationException("Invalid IP address: $arg")

    parseIPv4(arg)?.let {
        return InetAddress.getByAddress(it)
    }

    if (!arg.contains(':')) {
        throw invalidErr
    }

    val ret = InetAddress.getByAddress(parseIPv6(arg) ?: throw invalidErr)
    if (ret !is Inet6Address) {
        throw invalidErr
    }

    return ret
}

fun parsePrefix(arg: String): IPPrefix {
    val invalidErr = InvalidTunnelConfigurationException("Invalid prefix: $arg")

    val parts = arg.split("/")
    if (parts.size != 2 || parts[1].isEmpty() || !parts[1].all { it.isDigit() } || parts[1].length > 3) {
        throw invalidErr
    }

    val address = try {
        parseIP(parts[0])
    } catch (err: InvalidTunnelConfigurationException) {
        throw invalidErr
    }

    val prefixLength = parts[1].toInt()

    val maxPrefixLength = if (address is Inet4Address) 32 else 128
    if (prefixLength > maxPrefixLength) {
        throw invalidErr
    }

    return IPPrefix(address, prefixLength)
}

fun getTunnelSpec(cfg: Mobilev1.TunnelConfiguration): TunnelSpec {
    val addresses = cfg.addressesList.map { parsePrefix(it) }
    if (addresses.isEmpty()) {
        throw InvalidTunnelConfigurationException("The tunnel configuration has no addresses")
    }

    val routes = cfg.routesList.map { parsePrefix(it).masked() }.distinct()
    routes.find { it.prefixLength == 0 }?.let {
        throw InvalidTunnelConfigurationException("Default routes are not supported: $it")
    }

    if (cfg.mtu != 0 && (cfg.mtu < MIN_TUNNEL_MTU || cfg.mtu > MAX_TUNNEL_MTU)) {
        throw InvalidTunnelConfigurationException("Invalid MTU: ${cfg.mtu}")
    }

    val dnsServers = if (cfg.hasDns()) cfg.dns.serversList.map { parseIP(it) } else emptyList()

    val searchDomains = if (cfg.hasDns() && dnsServers.isNotEmpty()) {
        cfg.dns.searchDomainsList.map { it.trim().trimEnd('.').lowercase() }.onEach {
            if (!rgxSearchDomain.matches(it)) {
                throw InvalidTunnelConfigurationException("Invalid search domain: $it")
            }
        }.distinct()
    } else {
        emptyList()
    }

    val families = HashSet<IPFamily>()
    addresses.forEach { families.add(it.family) }
    routes.forEach { families.add(it.family) }
    dnsServers.forEach { families.add(getFamily(it)) }

    if (cfg.mtu != 0 && cfg.mtu < MIN_IPV6_TUNNEL_MTU && families.contains(IPFamily.V6)) {
        throw InvalidTunnelConfigurationException(
            "The MTU ${cfg.mtu} is lower than the minimum IPv6 MTU of $MIN_IPV6_TUNNEL_MTU",
        )
    }

    return TunnelSpec(
        addresses = addresses,
        routes = routes,
        dnsServers = dnsServers,
        searchDomains = searchDomains,
        mtu = cfg.mtu,
        bypassFamilies = IPFamily.entries.filter { !families.contains(it) }.toSet(),
    )
}
