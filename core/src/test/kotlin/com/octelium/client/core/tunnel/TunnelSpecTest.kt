package com.octelium.client.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address

class TunnelSpecTest {

    private fun assertInvalid(msg: String, fn: () -> Unit) {
        try {
            fn()
            fail()
        } catch (err: InvalidTunnelConfigurationException) {
            assertEquals(msg, err.message)
        }
    }

    @Test
    fun testParseIP() {
        run {
            val ret = parseIP("10.1.2.3")
            assertTrue(ret is Inet4Address)
            assertEquals("10.1.2.3", ret.hostAddress)
        }
        run {
            val ret = parseIP("fdee:1::5")
            assertTrue(ret is Inet6Address)
            assertEquals("fdee:1:0:0:0:0:0:5", ret.hostAddress)
        }
        run {
            assertEquals(IPFamily.V4, getFamily(parseIP("0.0.0.0")))
            assertEquals(IPFamily.V6, getFamily(parseIP("::")))
            assertEquals("1:2:3:4:5:6:7:8", parseIP("1:2:3:4:5:6:7:8").hostAddress)
            assertEquals("fdee:0:0:0:0:0:a01:203", parseIP("fdee::10.1.2.3").hostAddress)
            assertEquals("0:0:0:0:0:0:0:1", parseIP("::1").hostAddress)
            assertEquals("fdee:0:0:0:0:0:0:0", parseIP("FDEE::").hostAddress)
        }
        for (arg in listOf(
            "",
            "10.1.2",
            "10.1.2.256",
            "10.1.2.3.4",
            "010.1.2.3.4",
            "example.com",
            "localhost",
            "fdee::g",
            "1.2.3.4:53",
            " 10.1.2.3",
            "::ffff:10.1.2.3",
            ":::",
            "1::2::3",
            "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7",
            ":1:2:3:4:5:6:7",
            "12345::1",
            "fdee::10.1.2",
            "[fdee::1]",
        )) {
            assertInvalid("Invalid IP address: $arg") { parseIP(arg) }
        }
    }

    @Test
    fun testParsePrefix() {
        run {
            val ret = parsePrefix("10.1.2.3/32")
            assertEquals(32, ret.prefixLength)
            assertEquals(IPFamily.V4, ret.family)
            assertEquals("10.1.2.3/32", ret.toString())
        }
        run {
            val ret = parsePrefix("fdee:1::/64")
            assertEquals(64, ret.prefixLength)
            assertEquals(IPFamily.V6, ret.family)
        }
        run {
            assertEquals(128, parsePrefix("fdee::1/128").prefixLength)
            assertEquals(0, parsePrefix("0.0.0.0/0").prefixLength)
        }
        for (arg in listOf(
            "10.1.2.3",
            "10.1.2.3/",
            "10.1.2.3/33",
            "fdee::/129",
            "10.1.2.3/-1",
            "10.1.2.3/1/2",
            "10.1.2.3/+8",
            "/24",
            "example.com/24",
            "10.1.2.3/0024",
        )) {
            assertInvalid("Invalid prefix: $arg") { parsePrefix(arg) }
        }
    }

    @Test
    fun testMasked() {
        assertEquals("10.1.0.0/16", parsePrefix("10.1.2.3/16").masked().toString())
        assertEquals("10.1.2.0/23", parsePrefix("10.1.3.255/23").masked().toString())
        assertEquals("10.1.2.3/32", parsePrefix("10.1.2.3/32").masked().toString())
        assertEquals("fdee:1:0:0:0:0:0:0/64", parsePrefix("fdee:1::abcd/64").masked().toString())
        assertEquals("fdee:1:8000:0:0:0:0:0/33", parsePrefix("fdee:1:ffff::/33").masked().toString())
        assertEquals("0.0.0.0/0", parsePrefix("10.1.2.3/0").masked().toString())
    }

    private fun getConfig(
        addresses: List<String> = emptyList(),
        routes: List<String> = emptyList(),
        dns: DNSConfig? = null,
        mtu: Int = 0,
    ): NetworkConfig = NetworkConfig(
        generation = 1,
        addresses = addresses,
        routes = routes,
        dns = dns,
        mtu = mtu,
    )

    @Test
    fun testGetTunnelSpec() {
        run {
            val ret = getTunnelSpec(
                getConfig(
                    addresses = listOf("fdee:1::5/128"),
                    routes = listOf("fdee:1::/64"),
                    mtu = 1280,
                    dns = DNSConfig(
                        servers = listOf("fdee:1::53"),
                        searchDomains = listOf("local.Example.com."),
                        matchDomains = listOf("local.example.com"),
                    ),
                ),
            )

            assertEquals(listOf("fdee:1:0:0:0:0:0:5/128"), ret.addresses.map { it.toString() })
            assertEquals(listOf("fdee:1:0:0:0:0:0:0/64"), ret.routes.map { it.toString() })
            assertEquals(listOf("fdee:1:0:0:0:0:0:53"), ret.dnsServers.map { it.hostAddress })
            assertEquals(listOf("local.example.com"), ret.searchDomains)
            assertEquals(1280, ret.mtu)
            assertEquals(setOf(IPFamily.V4), ret.bypassFamilies)
        }

        run {
            val ret = getTunnelSpec(
                getConfig(
                    addresses = listOf("10.1.2.3/32"),
                    routes = listOf("10.1.2.3/16", "10.1.0.0/16"),
                ),
            )

            assertEquals(listOf("10.1.0.0/16"), ret.routes.map { it.toString() })
            assertTrue(ret.dnsServers.isEmpty())
            assertTrue(ret.searchDomains.isEmpty())
            assertEquals(0, ret.mtu)
            assertEquals(setOf(IPFamily.V6), ret.bypassFamilies)
        }

        run {
            val ret = getTunnelSpec(
                getConfig(
                    addresses = listOf("10.1.2.3/32", "fdee:1::5/128"),
                    routes = listOf("10.1.0.0/16", "fdee:1::/64"),
                    dns = DNSConfig(servers = listOf("10.1.0.53", "fdee:1::53"), matchAllDomains = true),
                ),
            )

            assertEquals(2, ret.dnsServers.size)
            assertTrue(ret.bypassFamilies.isEmpty())
        }

        run {
            val ret = getTunnelSpec(
                getConfig(
                    addresses = listOf("10.1.2.3/32"),
                    dns = DNSConfig(servers = emptyList(), searchDomains = listOf("local.example.com")),
                ),
            )
            assertTrue(ret.searchDomains.isEmpty())
        }

        run {
            val ret = getTunnelSpec(
                getConfig(addresses = listOf("10.1.2.3/32"), routes = listOf("10.1.0.0/16"), mtu = 576),
            )
            assertEquals(576, ret.mtu)
        }
    }

    @Test
    fun testGetTunnelSpecErrors() {
        assertInvalid("The tunnel configuration has no addresses") {
            getTunnelSpec(getConfig(routes = listOf("10.1.0.0/16")))
        }

        assertInvalid("Default routes are not supported: 0.0.0.0/0") {
            getTunnelSpec(getConfig(addresses = listOf("10.1.2.3/32"), routes = listOf("0.0.0.0/0")))
        }

        assertInvalid("Default routes are not supported: 0:0:0:0:0:0:0:0/0") {
            getTunnelSpec(getConfig(addresses = listOf("fdee::5/128"), routes = listOf("::/0")))
        }

        assertInvalid("Invalid MTU: 100") {
            getTunnelSpec(getConfig(addresses = listOf("10.1.2.3/32"), mtu = 100))
        }

        assertInvalid("The MTU 1279 is lower than the minimum IPv6 MTU of 1280") {
            getTunnelSpec(getConfig(addresses = listOf("fdee:1::5/128"), mtu = 1279))
        }

        assertInvalid("The MTU 576 is lower than the minimum IPv6 MTU of 1280") {
            getTunnelSpec(getConfig(addresses = listOf("10.1.2.3/32"), routes = listOf("fdee:1::/64"), mtu = 576))
        }

        assertInvalid("Invalid prefix: 10.1.2.3") {
            getTunnelSpec(getConfig(addresses = listOf("10.1.2.3")))
        }

        assertInvalid("Invalid IP address: dns.example.com") {
            getTunnelSpec(
                getConfig(addresses = listOf("10.1.2.3/32"), dns = DNSConfig(servers = listOf("dns.example.com"))),
            )
        }

        assertInvalid("Invalid search domain: local example.com") {
            getTunnelSpec(
                getConfig(
                    addresses = listOf("10.1.2.3/32"),
                    dns = DNSConfig(servers = listOf("10.1.0.53"), searchDomains = listOf("local example.com")),
                ),
            )
        }
    }
}
