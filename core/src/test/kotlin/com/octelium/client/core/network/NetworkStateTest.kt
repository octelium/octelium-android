package com.octelium.client.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkStateTest {

    private val wifi = NetworkInfo(
        id = "100",
        transport = NetworkTransport.WIFI,
        hasInternet = true,
        isValidated = true,
        isCaptivePortal = false,
        isMetered = false,
        isVPN = false,
    )

    @Test
    fun testGetNetworkState() {
        assertEquals(NetworkState(false, ""), getNetworkState(null))
        assertEquals(NetworkState(true, "100"), getNetworkState(wifi))
        assertEquals(NetworkState(true, "100"), getNetworkState(wifi.copy(isValidated = false)))
        assertEquals(NetworkState(false, ""), getNetworkState(wifi.copy(isCaptivePortal = true)))
        assertEquals(NetworkState(false, ""), getNetworkState(wifi.copy(hasInternet = false)))
        assertEquals(NetworkState(false, ""), getNetworkState(wifi.copy(isVPN = true)))
        assertEquals(
            NetworkState(true, "200"),
            getNetworkState(wifi.copy(id = "200", transport = NetworkTransport.CELLULAR, isMetered = true)),
        )
    }

    @Test
    fun testGetNetworkLabel() {
        assertEquals("No network", getNetworkLabel(null))
        assertEquals("Wi-Fi", getNetworkLabel(wifi))
        assertEquals("Cellular", getNetworkLabel(wifi.copy(transport = NetworkTransport.CELLULAR)))
        assertEquals("Ethernet (not validated)", getNetworkLabel(wifi.copy(transport = NetworkTransport.ETHERNET, isValidated = false)))
        assertEquals("Wi-Fi (sign-in required)", getNetworkLabel(wifi.copy(isCaptivePortal = true)))
        assertEquals("Other", getNetworkLabel(wifi.copy(transport = NetworkTransport.OTHER)))
    }
}
