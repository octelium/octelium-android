package com.octelium.client.core.cluster

import octelium.api.main.meta.v1.Metav1
import octelium.api.main.user.v1.Userv1
import octelium.api.main.user.v1.Userv1.Service.Spec.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesTest {

    private fun getService(
        name: String = "svc.default",
        hostname: String = "",
        type: Type = Type.HTTP,
        displayName: String = "",
        description: String = "",
    ): Userv1.Service = Userv1.Service.newBuilder()
        .setMetadata(
            Metav1.Metadata.newBuilder()
                .setName(name)
                .setDisplayName(displayName)
                .setDescription(description)
        )
        .setSpec(Userv1.Service.Spec.newBuilder().setType(type))
        .setStatus(Userv1.Service.Status.newBuilder().setPrimaryHostname(hostname))
        .build()

    @Test
    fun testFQDN() {
        run {
            val svc = getService(hostname = "svc")
            assertEquals("svc.local.example.com", getServicePrivateFQDN(svc, "example.com"))
            assertEquals("svc.example.com", getServicePublicFQDN(svc, "example.com"))
            assertEquals("https://svc.example.com", getServicePublicURL(svc, "example.com"))
            assertEquals("svc", getServiceHostname(svc))
        }
        run {
            val svc = getService(hostname = "")
            assertEquals("local.example.com", getServicePrivateFQDN(svc, "example.com"))
            assertEquals("example.com", getServicePublicFQDN(svc, "example.com"))
            assertEquals("https://example.com", getServicePublicURL(svc, "example.com"))
            assertEquals("svc.default", getServiceHostname(svc))
        }
    }

    @Test
    fun testIsServiceWebBrowsable() {
        assertTrue(isServiceWebBrowsable(getService(type = Type.WEB)))
        assertTrue(isServiceWebBrowsable(getService(type = Type.HTTP)))
        assertTrue(isServiceWebBrowsable(getService(type = Type.RDP_WEB)))
        assertFalse(isServiceWebBrowsable(getService(type = Type.SSH)))
        assertFalse(isServiceWebBrowsable(getService(type = Type.UNSET)))
    }

    @Test
    fun testServiceTypes() {
        assertEquals("Kubernetes", getServiceTypeInfo(getService(type = Type.KUBERNETES)).label)
        assertEquals(UNKNOWN_SERVICE_TYPE, getServiceTypeInfo(getService(type = Type.UNSET)))
        assertEquals(Type.POSTGRES, getServiceTypeByKey("POSTGRES")?.type)
        assertNull(getServiceTypeByKey("NONE"))
        assertNull(getServiceTypeByKey(""))
        assertNull(getServiceTypeByKey(null))
        assertEquals(SERVICE_TYPES.size, SERVICE_TYPES.map { it.key }.toSet().size)
        assertEquals(SERVICE_TYPES.size, SERVICE_TYPES.map { it.type }.toSet().size)
    }

    @Test
    fun testSplitServiceName() {
        assertEquals("svc" to "default", splitServiceName("svc.default"))
        assertEquals("svc" to "ns.sub", splitServiceName("svc.ns.sub"))
        assertEquals("svc" to null, splitServiceName("svc"))
        assertEquals(".svc" to null, splitServiceName(".svc"))
    }

    @Test
    fun testPrintResourceNameWithDisplay() {
        assertEquals("svc", printResourceNameWithDisplay(Metav1.Metadata.newBuilder().setName("svc").build()))
        assertEquals(
            "svc (My Service)",
            printResourceNameWithDisplay(Metav1.Metadata.newBuilder().setName("svc").setDisplayName("My Service").build()),
        )
    }

    @Test
    fun testTokenizeQuery() {
        assertEquals(emptyList<String>(), tokenizeQuery(""))
        assertEquals(emptyList<String>(), tokenizeQuery("   "))
        assertEquals(listOf("api", "prod"), tokenizeQuery("  API   Prod "))
    }

    @Test
    fun testMatches() {
        val svc = getService(
            name = "api.production",
            hostname = "api-prod",
            displayName = "Public API",
            description = "The main HTTP API",
        )

        assertTrue(matchesService(svc, emptyList()))
        assertTrue(matchesService(svc, tokenizeQuery("api")))
        assertTrue(matchesService(svc, tokenizeQuery("public main")))
        assertTrue(matchesService(svc, tokenizeQuery("api-prod")))
        assertFalse(matchesService(svc, tokenizeQuery("api staging")))

        val ns = Userv1.Namespace.newBuilder()
            .setMetadata(Metav1.Metadata.newBuilder().setName("production").setDisplayName("Production"))
            .build()
        assertTrue(matchesNamespace(ns, tokenizeQuery("prod")))
        assertFalse(matchesNamespace(ns, tokenizeQuery("staging")))
        assertTrue(matchesAllTokens("Hello World", listOf("hello", "world")))
    }
}
