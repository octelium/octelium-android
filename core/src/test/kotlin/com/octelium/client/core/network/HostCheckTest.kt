package com.octelium.client.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCheckTest {

    private val host = "octelium-api.example.com"

    @Test
    fun testGetHostCheck() {
        run {
            val ret = getHostCheck(host, listOf("192.0.2.1", "2001:db8::1", "192.0.2.1"), null)
            assertEquals(HostResolution.RESOLVED, ret.resolution)
            assertEquals(listOf("192.0.2.1", "2001:db8::1"), ret.addresses)
            assertNull(ret.message)
        }

        run {
            val ret = getHostCheck(host, listOf("192.0.2.1"), emptyList(), "ignored")
            assertEquals(HostResolution.RESOLVED, ret.resolution)
        }

        run {
            val ret = getHostCheck(host, emptyList(), listOf("192.0.2.1"))
            assertEquals(HostResolution.REJECTED, ret.resolution)
            assertEquals(listOf("192.0.2.1"), ret.addresses)
        }

        run {
            val ret = getHostCheck(host, emptyList(), emptyList())
            assertEquals(HostResolution.NOT_FOUND, ret.resolution)
            assertTrue(ret.addresses.isEmpty())
        }

        run {
            val ret = getHostCheck(host, emptyList(), null, "Network unreachable")
            assertEquals(HostResolution.FAILED, ret.resolution)
            assertEquals("Network unreachable", ret.message)
        }

        run {
            val ret = getHostCheck(host, emptyList(), null, " ")
            assertEquals(HostResolution.FAILED, ret.resolution)
            assertNull(ret.message)
        }
    }

    @Test
    fun testGetHostCheckError() {
        assertNull(getHostCheckError(getHostCheck(host, listOf("192.0.2.1"), null)))

        run {
            val ret = getHostCheckError(getHostCheck(host, emptyList(), emptyList()))!!
            assertTrue(ret.startsWith("octelium-api.example.com could not be found."))
            assertTrue(ret.contains("has a record for octelium-api.example.com"))
        }

        run {
            val ret = getHostCheckError(getHostCheck(host, emptyList(), listOf("192.0.2.1", "2001:db8::1")))!!
            assertTrue(ret.startsWith("octelium-api.example.com resolves to 192.0.2.1, 2001:db8::1 but Android refuses"))
            assertTrue(ret.contains("label begins with \"_\""))
        }

        assertEquals(
            "Could not resolve octelium-api.example.com: Network unreachable",
            getHostCheckError(getHostCheck(host, emptyList(), null, "Network unreachable")),
        )

        assertEquals(
            "Could not resolve octelium-api.example.com. Check your Internet connection.",
            getHostCheckError(getHostCheck(host, emptyList(), null)),
        )
    }

    @Test
    fun testGetHostResolutionLabel() {
        assertEquals("Resolved", getHostResolutionLabel(HostResolution.RESOLVED))
        assertEquals("Not found", getHostResolutionLabel(HostResolution.NOT_FOUND))
        assertEquals("Rejected by Android", getHostResolutionLabel(HostResolution.REJECTED))
        assertEquals("Failed", getHostResolutionLabel(HostResolution.FAILED))
    }
}
