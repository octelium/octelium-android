package com.octelium.client.core.connect

import com.octelium.client.core.tunnel.DNSMode
import com.octelium.client.core.tunnel.TunnelMode
import octelium.api.main.user.v1.Userv1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {

    private fun getGateway(id: String, port: Int = 51820): Userv1.Gateway = Userv1.Gateway.newBuilder()
        .setId(id)
        .setWireguard(Userv1.Gateway.WireGuard.newBuilder().setPort(port))
        .build()

    private val state = Userv1.ConnectionState.newBuilder()
        .setMtu(1280)
        .addGateways(getGateway("gw-1"))
        .addGateways(getGateway("gw-2"))
        .setDns(Userv1.DNS.newBuilder().addServers("fdee:1::53"))
        .build()

    private fun getIDs(arg: Userv1.ConnectionState?): List<String> = arg!!.gatewaysList.map { it.id }

    @Test
    fun testReduceConnectionState() {
        run {
            val next = Userv1.ConnectionState.newBuilder().setMtu(1400).build()
            assertEquals(next, reduceConnectionState(state, Userv1.ConnectResponse.newBuilder().setState(next).build()))
        }

        run {
            val ret = reduceConnectionState(
                state,
                Userv1.ConnectResponse.newBuilder()
                    .setAddGateway(Userv1.ConnectResponse.AddGateway.newBuilder().setGateway(getGateway("gw-3")))
                    .build(),
            )
            assertEquals(listOf("gw-1", "gw-2", "gw-3"), getIDs(ret))
        }

        run {
            val ret = reduceConnectionState(
                state,
                Userv1.ConnectResponse.newBuilder()
                    .setAddGateway(Userv1.ConnectResponse.AddGateway.newBuilder().setGateway(getGateway("gw-1", 1000)))
                    .build(),
            )
            assertEquals(listOf("gw-1", "gw-2"), getIDs(ret))
            assertEquals(1000, ret!!.gatewaysList[0].wireguard.port)
        }

        run {
            val ret = reduceConnectionState(
                state,
                Userv1.ConnectResponse.newBuilder()
                    .setUpdateGateway(Userv1.ConnectResponse.UpdateGateway.newBuilder().setGateway(getGateway("gw-2", 2000)))
                    .build(),
            )
            assertEquals(listOf("gw-1", "gw-2"), getIDs(ret))
            assertEquals(2000, ret!!.gatewaysList[1].wireguard.port)

            assertNull(
                reduceConnectionState(
                    state,
                    Userv1.ConnectResponse.newBuilder()
                        .setUpdateGateway(Userv1.ConnectResponse.UpdateGateway.newBuilder().setGateway(getGateway("gw-3")))
                        .build(),
                ),
            )
        }

        run {
            val ret = reduceConnectionState(
                state,
                Userv1.ConnectResponse.newBuilder()
                    .setDeleteGateway(Userv1.ConnectResponse.DeleteGateway.newBuilder().setId("gw-1"))
                    .build(),
            )
            assertEquals(listOf("gw-2"), getIDs(ret))

            assertNull(
                reduceConnectionState(
                    state,
                    Userv1.ConnectResponse.newBuilder()
                        .setDeleteGateway(Userv1.ConnectResponse.DeleteGateway.newBuilder().setId("gw-3"))
                        .build(),
                ),
            )
        }

        run {
            val ret = reduceConnectionState(
                state,
                Userv1.ConnectResponse.newBuilder()
                    .setUpdateDNS(
                        Userv1.ConnectResponse.UpdateDNS.newBuilder()
                            .setDns(Userv1.DNS.newBuilder().addServers("fdee:1::54"))
                    )
                    .build(),
            )
            assertEquals(listOf("fdee:1::54"), ret!!.dns.serversList)

            assertNull(
                reduceConnectionState(
                    state,
                    Userv1.ConnectResponse.newBuilder()
                        .setUpdateDNS(Userv1.ConnectResponse.UpdateDNS.newBuilder().setDns(Userv1.DNS.getDefaultInstance()))
                        .build(),
                ),
            )
        }

        run {
            assertNull(reduceConnectionState(state, Userv1.ConnectResponse.getDefaultInstance()))
            assertNull(
                reduceConnectionState(
                    state,
                    Userv1.ConnectResponse.newBuilder()
                        .setMessage(Userv1.ConnectResponse.Message.newBuilder().setMessage("hello"))
                        .build(),
                ),
            )
        }
    }

    @Test
    fun testGetInitializeRequest() {
        run {
            val ret = getInitializeRequest(ConnectOptions()).initialize
            assertEquals(Userv1.ConnectRequest.Initialize.L3Mode.V6, ret.l3Mode)
            assertEquals(Userv1.ConnectRequest.Initialize.ConnectionType.UNSET, ret.connectionType)
            assertEquals(false, ret.ignoreDNS)
            assertTrue(!ret.hasServiceOptions() && ret.publishedServicesCount == 0)
        }

        run {
            val ret = getInitializeRequest(
                ConnectOptions(
                    l3Mode = Userv1.ConnectRequest.Initialize.L3Mode.V4,
                    tunnelMode = TunnelMode.QUICV0,
                    dnsMode = DNSMode.DISABLED,
                ),
            ).initialize
            assertEquals(Userv1.ConnectRequest.Initialize.L3Mode.V4, ret.l3Mode)
            assertEquals(Userv1.ConnectRequest.Initialize.ConnectionType.QUICV0, ret.connectionType)
            assertEquals(true, ret.ignoreDNS)
        }
    }
}
