package com.octelium.client.core.local

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.test.runTest
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.mobile.v1.Mobilev1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeTransport(private val handler: (String, ByteArray) -> ByteArray) : LocalTransport {
    val calls = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun call(method: String, request: ByteArray): ByteArray {
        calls.add(method to request)
        return handler(method, request)
    }
}

class LocalClientTest {

    @Test
    fun testGetInfo() = runTest {
        val transport = FakeTransport { _, _ ->
            Mobilev1.GetInfoResponse.newBuilder()
                .setVersion("v0.44.0")
                .setApiMajorVersion(1)
                .setInstanceID("instance")
                .setAuthenticationCallbackURL("com.octelium.client:/callback/success")
                .build()
                .toByteArray()
        }

        val ret = LocalClient(transport).getInfo()
        assertEquals("v0.44.0", ret.version)
        assertEquals("instance", ret.instanceID)
        assertEquals("GetInfo", transport.calls.single().first)
        assertEquals(0, transport.calls.single().second.size)
        assertNull(checkInfo(ret))
    }

    @Test
    fun testCheckInfo() {
        run {
            val info = Mobilev1.GetInfoResponse.newBuilder()
                .setApiMajorVersion(2)
                .setAuthenticationCallbackURL("com.octelium.client:/callback/success")
                .build()
            assertEquals(
                "liboctelium implements the local API version 2 while this application requires the version 1",
                checkInfo(info),
            )
        }
        run {
            val info = Mobilev1.GetInfoResponse.newBuilder().setApiMajorVersion(1).build()
            assertEquals("liboctelium does not provide an authentication callback URL", checkInfo(info))
        }
    }

    @Test
    fun testRequests() = runTest {
        val transport = FakeTransport { method, _ ->
            when (method) {
                "SetNetworkState" -> Mobilev1.SetNetworkStateResponse.getDefaultInstance().toByteArray()
                "GetAPICredential" -> Daemonv1.GetAPICredentialResponse.newBuilder()
                    .setAccessToken("token")
                    .build()
                    .toByteArray()

                "UpdateDomainSettings" -> Daemonv1.DomainSettings.newBuilder()
                    .setDomain("example.com")
                    .setAutoConnect(true)
                    .build()
                    .toByteArray()

                "GetStatus" -> Daemonv1.GetStatusResponse.newBuilder().setRevision(3).build().toByteArray()
                else -> Daemonv1.Operation.newBuilder()
                    .setId("op")
                    .setDomain("example.com")
                    .build()
                    .toByteArray()
            }
        }

        val c = LocalClient(transport)

        run {
            val ret = c.authenticateBrowser("example.com")
            assertEquals("op", ret.id)
            val req = Daemonv1.AuthenticateRequest.parseFrom(transport.calls.last().second)
            assertEquals("Authenticate", transport.calls.last().first)
            assertEquals("example.com", req.domain)
            assertTrue(req.hasBrowser())
        }

        run {
            c.authenticateToken("example.com", "secret")
            val req = Daemonv1.AuthenticateRequest.parseFrom(transport.calls.last().second)
            assertEquals("secret", req.authenticationToken.authenticationToken)
            assertFalse(req.hasBrowser())
        }

        run {
            c.completeAuthentication("op", "com.octelium.client:/callback/success?octelium_response=abc")
            val req = Mobilev1.CompleteAuthenticationRequest.parseFrom(transport.calls.last().second)
            assertEquals("CompleteAuthentication", transport.calls.last().first)
            assertEquals("op", req.operationID)
            assertEquals("com.octelium.client:/callback/success?octelium_response=abc", req.callbackURL)
        }

        run {
            c.connect("example.com")
            assertEquals("Connect", transport.calls.last().first)
            val req = Daemonv1.ConnectRequest.parseFrom(transport.calls.last().second)
            assertEquals("example.com", req.domain)
            assertFalse(req.hasOptions())
        }

        run {
            c.disconnect("example.com")
            assertEquals("Disconnect", transport.calls.last().first)
            assertEquals("example.com", Daemonv1.DisconnectRequest.parseFrom(transport.calls.last().second).domain)
        }

        run {
            c.logout("example.com")
            assertEquals("Logout", transport.calls.last().first)
        }

        run {
            c.deleteDomain("example.com")
            assertEquals("DeleteDomain", transport.calls.last().first)
        }

        run {
            c.getOperation("op")
            assertEquals("GetOperation", transport.calls.last().first)
            assertEquals("op", Daemonv1.GetOperationRequest.parseFrom(transport.calls.last().second).id)
        }

        run {
            c.cancelOperation("op")
            assertEquals("CancelOperation", transport.calls.last().first)
            assertEquals("op", Daemonv1.CancelOperationRequest.parseFrom(transport.calls.last().second).id)
        }

        run {
            assertEquals("token", c.getAPICredential("example.com").accessToken)
            assertEquals("GetAPICredential", transport.calls.last().first)
        }

        run {
            val ret = c.updateDomainSettings(
                "example.com",
                Daemonv1.DomainSettings.newBuilder().setAutoConnect(true).build(),
            )
            assertTrue(ret.autoConnect)
            val req = Daemonv1.UpdateDomainSettingsRequest.parseFrom(transport.calls.last().second)
            assertEquals("example.com", req.domain)
            assertTrue(req.settings.autoConnect)
        }

        run {
            c.setNetworkState(true, "100")
            val req = Mobilev1.SetNetworkStateRequest.parseFrom(transport.calls.last().second)
            assertEquals("SetNetworkState", transport.calls.last().first)
            assertTrue(req.isAvailable)
            assertEquals("100", req.id)
        }

        run {
            assertEquals(3L, c.getStatus().revision)
        }
    }

    @Test
    fun testErrors() = runTest {
        run {
            val c = LocalClient(FakeTransport { _, _ -> throw getStatusException(5, "Unknown Cluster domain") })
            try {
                c.connect("example.com")
                fail()
            } catch (err: StatusException) {
                assertEquals(Status.Code.NOT_FOUND, err.status.code)
                assertEquals("Unknown Cluster domain", err.status.description)
            }
        }
        run {
            val c = LocalClient(FakeTransport { _, _ -> byteArrayOf(0xff.toByte(), 0xff.toByte()) })
            try {
                c.connect("example.com")
                fail()
            } catch (err: StatusException) {
                assertEquals(Status.Code.INTERNAL, err.status.code)
                assertTrue(err.status.description!!.startsWith("Could not unmarshal the Connect response"))
            }
        }
    }

    @Test
    fun testGetStatusException() {
        assertEquals(Status.Code.INVALID_ARGUMENT, getStatusException(3, "invalid").status.code)
        assertEquals("invalid", getStatusException(3, "invalid").status.description)
        assertEquals(Status.Code.UNAUTHENTICATED, getStatusException(16, "").status.code)
        assertEquals(Status.Code.UNKNOWN, getStatusException(0, "").status.code)
        assertEquals(Status.Code.UNKNOWN, getStatusException(17, "").status.code)
        assertEquals(Status.Code.UNKNOWN, getStatusException(-1, "").status.code)
    }

    @Test
    fun testGetErrorMessage() {
        assertEquals("invalid", getErrorMessage(getStatusException(3, "invalid")))
        assertEquals("NOT_FOUND", getErrorMessage(Status.NOT_FOUND.asException()))
        assertEquals("NOT_FOUND", getErrorMessage(Status.NOT_FOUND.asRuntimeException()))
        assertEquals("boom", getErrorMessage(IllegalStateException("boom")))
        assertEquals("IllegalStateException", getErrorMessage(IllegalStateException()))
        assertEquals(Status.Code.ABORTED, getStatusCode(Status.ABORTED.asRuntimeException()))
        assertNull(getStatusCode(IllegalStateException()))
    }
}
