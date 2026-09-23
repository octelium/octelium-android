package com.octelium.client.core.domain

import octelium.api.client.daemon.v1.Daemonv1.ConnectionOptions
import octelium.api.client.daemon.v1.Daemonv1.DomainSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTest {

    @Test
    fun testGetDomainSettingsForm() {
        run {
            assertEquals(DomainSettingsForm(), getDomainSettingsForm(null))
        }
        run {
            assertEquals(DomainSettingsForm(), getDomainSettingsForm(DomainSettings.getDefaultInstance()))
        }
        run {
            val ret = getDomainSettingsForm(
                DomainSettings.newBuilder()
                    .setDomain("example.com")
                    .setAutoConnect(true)
                    .setConnectionOptions(
                        ConnectionOptions.newBuilder()
                            .setTunnelMode(ConnectionOptions.TunnelMode.QUICV0)
                            .setL3Mode(ConnectionOptions.L3Mode.BOTH)
                            .setDns(ConnectionOptions.DNS.newBuilder().setMode(ConnectionOptions.DNS.Mode.FULL))
                            .setMtu(1400)
                    )
                    .build(),
            )

            assertEquals(
                DomainSettingsForm(
                    autoConnect = true,
                    tunnelMode = ConnectionOptions.TunnelMode.QUICV0,
                    dnsMode = ConnectionOptions.DNS.Mode.FULL,
                    l3Mode = ConnectionOptions.L3Mode.BOTH,
                    mtu = "1400",
                ),
                ret,
            )
        }
        run {
            val ret = getDomainSettingsForm(
                DomainSettings.newBuilder()
                    .setConnectionOptions(
                        ConnectionOptions.newBuilder()
                            .setDns(ConnectionOptions.DNS.newBuilder().setMode(ConnectionOptions.DNS.Mode.MODE_UNSPECIFIED))
                    )
                    .build(),
            )
            assertEquals(ConnectionOptions.DNS.Mode.DEFAULT, ret.dnsMode)
        }
    }

    @Test
    fun testValidateDomainSettingsForm() {
        assertNull(validateDomainSettingsForm(DomainSettingsForm()))
        assertNull(validateDomainSettingsForm(DomainSettingsForm(mtu = " ")))
        assertNull(validateDomainSettingsForm(DomainSettingsForm(mtu = "576")))
        assertNull(validateDomainSettingsForm(DomainSettingsForm(mtu = "1500")))
        assertEquals(
            "The MTU must be between 576 and 1500",
            validateDomainSettingsForm(DomainSettingsForm(mtu = "575")),
        )
        assertEquals(
            "The MTU must be between 576 and 1500",
            validateDomainSettingsForm(DomainSettingsForm(mtu = "1501")),
        )
        assertEquals(
            "The MTU must be between 576 and 1500",
            validateDomainSettingsForm(DomainSettingsForm(mtu = "abc")),
        )
    }

    @Test
    fun testGetDNSModeOptionLabel() {
        assertEquals("Split DNS", getDNSModeOptionLabel(ConnectionOptions.DNS.Mode.DEFAULT))
        assertEquals("Split DNS", getDNSModeOptionLabel(ConnectionOptions.DNS.Mode.MODE_UNSPECIFIED))
        assertEquals("Full DNS", getDNSModeOptionLabel(ConnectionOptions.DNS.Mode.FULL))
        assertEquals("Disabled", getDNSModeOptionLabel(ConnectionOptions.DNS.Mode.DISABLED))
        assertEquals(3, DNS_MODES.map { getDNSModeOptionLabel(it) }.toSet().size)
        assertEquals(3, TUNNEL_MODES.size)
        assertEquals(4, L3_MODES.size)
    }

    @Test
    fun testToDomainSettings() {
        run {
            val ret = toDomainSettings("example.com", DomainSettingsForm())
            assertEquals("example.com", ret.domain)
            assertFalse(ret.autoConnect)
            assertEquals(ConnectionOptions.DNS.Mode.DEFAULT, ret.connectionOptions.dns.mode)
            assertEquals(0, ret.connectionOptions.mtu)
            assertFalse(ret.connectionOptions.hasServiceOptions())
            assertEquals(
                ConnectionOptions.ImplementationMode.IMPLEMENTATION_MODE_UNSPECIFIED,
                ret.connectionOptions.implementationMode,
            )
        }
        run {
            val form = DomainSettingsForm(
                autoConnect = true,
                tunnelMode = ConnectionOptions.TunnelMode.WIREGUARD,
                dnsMode = ConnectionOptions.DNS.Mode.DISABLED,
                l3Mode = ConnectionOptions.L3Mode.V4,
                mtu = " 1280 ",
            )
            val ret = toDomainSettings("example.com", form)
            assertEquals(1280, ret.connectionOptions.mtu)
            assertEquals(form.copy(mtu = "1280"), getDomainSettingsForm(ret))
        }
    }
}
