package com.octelium.client.core.cluster

import com.google.protobuf.Timestamp
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.test.runTest
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.main.meta.v1.Metav1
import octelium.api.main.user.v1.MainServiceGrpcKt
import octelium.api.main.user.v1.Userv1
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections
import java.util.UUID

class ClusterClientTest {

    private val serverName = "cluster-${UUID.randomUUID()}"
    private val tokens = Collections.synchronizedList(mutableListOf<String?>())
    private val validTokens = Collections.synchronizedSet(mutableSetOf<String>())
    private var totalServices = 250

    private val server = InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(
            ServerInterceptors.intercept(
                object : MainServiceGrpcKt.MainServiceCoroutineImplBase() {
                    override suspend fun getStatus(request: Userv1.GetStatusRequest): Userv1.GetStatusResponse =
                        Userv1.GetStatusResponse.newBuilder().setDomain("example.com").build()

                    override suspend fun listService(request: Userv1.ListServiceOptions): Userv1.ServiceList {
                        val page = request.common.page
                        val itemsPerPage = request.common.itemsPerPage
                        val start = page * itemsPerPage
                        val end = minOf(start + itemsPerPage, totalServices)

                        return Userv1.ServiceList.newBuilder()
                            .addAllItems((start until end).map { idx ->
                                Userv1.Service.newBuilder()
                                    .setMetadata(Metav1.Metadata.newBuilder().setName("svc-$idx.${request.namespace}"))
                                    .build()
                            })
                            .setListResponseMeta(
                                Metav1.ListResponseMeta.newBuilder()
                                    .setPage(page)
                                    .setItemsPerPage(itemsPerPage)
                                    .setTotalCount(totalServices)
                                    .setHasMore(end < totalServices)
                            )
                            .build()
                    }

                    override suspend fun listNamespace(request: Userv1.ListNamespaceOptions): Userv1.NamespaceList =
                        Userv1.NamespaceList.newBuilder()
                            .addItems(
                                Userv1.Namespace.newBuilder()
                                    .setMetadata(Metav1.Metadata.newBuilder().setName("default"))
                            )
                            .build()
                },
                object : ServerInterceptor {
                    override fun <ReqT, RespT> interceptCall(
                        call: ServerCall<ReqT, RespT>,
                        headers: Metadata,
                        next: ServerCallHandler<ReqT, RespT>,
                    ): ServerCall.Listener<ReqT> {
                        val token = headers.get(Metadata.Key.of(AUTH_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER))
                        tokens.add(token)

                        if (token == null || !validTokens.contains(token)) {
                            call.close(Status.UNAUTHENTICATED.withDescription("invalid token"), Metadata())
                            return object : ServerCall.Listener<ReqT>() {}
                        }

                        return next.startCall(call, headers)
                    }
                },
            ),
        )
        .build()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdownNow()
    }

    private fun getClient(now: () -> Instant = Instant::now, source: CredentialSource): ClusterClient =
        ClusterClient(
            credentials = source,
            channels = { InProcessChannelBuilder.forName(serverName).directExecutor().build() },
            now = now,
        )

    @Test
    fun testCall() = runTest {
        var count = 0
        validTokens.add("token-1")

        val c = getClient {
            count++
            Daemonv1.GetAPICredentialResponse.newBuilder().setAccessToken("token-$count").build()
        }

        run {
            assertEquals("example.com", c.getStatus("example.com").domain)
            assertEquals(listOf<String?>("token-1"), tokens.toList())
            assertEquals(1, count)
        }

        run {
            c.getStatus("example.com")
            assertEquals(1, count)
            assertEquals("token-1", tokens.last())
        }

        run {
            validTokens.clear()
            validTokens.add("token-2")

            c.getStatus("example.com")
            assertEquals(2, count)
            assertEquals(listOf<String?>("token-1", "token-1", "token-1", "token-2"), tokens.toList())
        }

        run {
            c.invalidate("example.com")
            validTokens.add("token-3")
            c.getStatus("example.com")
            assertEquals(3, count)
            assertEquals("token-3", tokens.last())
        }

        c.close()
    }

    @Test
    fun testUnauthenticated() = runTest {
        run {
            var count = 0
            val c = getClient {
                count++
                Daemonv1.GetAPICredentialResponse.newBuilder().setAccessToken("invalid").build()
            }

            try {
                c.getStatus("example.com")
                fail()
            } catch (err: StatusException) {
                assertEquals(Status.Code.UNAUTHENTICATED, err.status.code)
            }
            assertEquals(2, count)
            c.close()
        }

        run {
            val c = getClient { Daemonv1.GetAPICredentialResponse.getDefaultInstance() }

            try {
                c.getStatus("example.com")
                fail()
            } catch (err: StatusException) {
                assertEquals(Status.Code.UNAUTHENTICATED, err.status.code)
                assertEquals("You are not authenticated to the domain example.com", err.status.description)
            }
            c.close()
        }

        run {
            val c = getClient { throw Status.UNAUTHENTICATED.withDescription("logged out").asException() }

            try {
                c.getStatus("example.com")
                fail()
            } catch (err: StatusException) {
                assertEquals("logged out", err.status.description)
            }
            c.close()
        }
    }

    @Test
    fun testListAll() = runTest {
        validTokens.add("token")
        val c = getClient { Daemonv1.GetAPICredentialResponse.newBuilder().setAccessToken("token").build() }

        run {
            val ret = c.listAllServices("example.com", namespace = "default")
            assertEquals(250, ret.size)
            assertEquals("svc-0.default", ret.first().metadata.name)
            assertEquals("svc-249.default", ret.last().metadata.name)
        }

        run {
            totalServices = 0
            assertTrue(c.listAllServices("example.com").isEmpty())
        }

        run {
            assertEquals(listOf("default"), c.listAllNamespaces("example.com").map { it.metadata.name })
        }

        run {
            val ret = c.listService(
                "example.com",
                Userv1.ListServiceOptions.newBuilder().setCommon(getCommonListOptions(0, 10)).build(),
            )
            assertTrue(ret.itemsList.isEmpty())
        }

        c.close()
    }

    @Test
    fun testListAllHelper() = runTest {
        run {
            val ret = listAll { page ->
                listOf(page) to Metav1.ListResponseMeta.newBuilder().setHasMore(page < 2).build()
            }
            assertEquals(listOf(0, 1, 2), ret)
        }
        run {
            val ret = listAll { page -> listOf(page) to null }
            assertEquals(listOf(0), ret)
        }
        run {
            val ret = listAll { _ -> emptyList<Int>() to Metav1.ListResponseMeta.newBuilder().setHasMore(true).build() }
            assertTrue(ret.isEmpty())
        }
        run {
            try {
                listAll { page -> listOf(page) to Metav1.ListResponseMeta.newBuilder().setHasMore(true).build() }
                fail()
            } catch (err: IllegalStateException) {
                assertEquals("The list exceeded the supported pagination range", err.message)
            }
        }
    }

    @Test
    fun testGetCommonListOptions() {
        val ret = getCommonListOptions(3, 25)
        assertEquals(3, ret.page)
        assertEquals(25, ret.itemsPerPage)
        assertEquals(Metav1.CommonListOptions.OrderBy.Type.NAME, ret.orderBy.type)
        assertEquals(Metav1.CommonListOptions.OrderBy.Mode.ASC, ret.orderBy.mode)
    }

    @Test
    fun testCredentialCache() {
        var now = Instant.parse("2026-09-23T10:00:00Z")
        val c = CredentialCache { now }

        run {
            assertNull(c.get("example.com"))
        }

        run {
            c.set("example.com", Daemonv1.GetAPICredentialResponse.newBuilder().setAccessToken("token").build())
            assertEquals("token", c.get("example.com"))
        }

        run {
            c.set(
                "example.com",
                Daemonv1.GetAPICredentialResponse.newBuilder()
                    .setAccessToken("token")
                    .setExpiresAt(Timestamp.newBuilder().setSeconds(now.epochSecond + 60))
                    .build(),
            )
            assertEquals("token", c.get("example.com"))

            now = now.plusSeconds(29)
            assertEquals("token", c.get("example.com"))

            now = now.plusSeconds(1)
            assertNull(c.get("example.com"))
            assertNull(c.get("example.com"))
        }

        run {
            c.set("a.example.com", Daemonv1.GetAPICredentialResponse.newBuilder().setAccessToken("a").build())
            c.set("b.example.com", Daemonv1.GetAPICredentialResponse.newBuilder().setAccessToken("b").build())
            c.remove("a.example.com")
            assertNull(c.get("a.example.com"))
            assertEquals("b", c.get("b.example.com"))
            c.clear()
            assertNull(c.get("b.example.com"))
        }
    }

    @Test
    fun testGetChangedSessions() {
        fun getDomain(domain: String, state: Daemonv1.AuthenticationStatus.State, at: Long): Daemonv1.DomainState =
            Daemonv1.DomainState.newBuilder()
                .setDomain(domain)
                .setAuthentication(
                    Daemonv1.AuthenticationStatus.newBuilder()
                        .setState(state)
                        .setAuthenticatedAt(Timestamp.newBuilder().setSeconds(at))
                )
                .build()

        val authenticated = Daemonv1.AuthenticationStatus.State.AUTHENTICATED

        val prev = getSessionKeys(
            Daemonv1.GetStatusResponse.newBuilder()
                .addDomains(getDomain("a.example.com", authenticated, 1))
                .addDomains(getDomain("b.example.com", authenticated, 1))
                .addDomains(getDomain("c.example.com", authenticated, 1))
                .build(),
        )

        val cur = getSessionKeys(
            Daemonv1.GetStatusResponse.newBuilder()
                .addDomains(getDomain("a.example.com", authenticated, 1))
                .addDomains(getDomain("b.example.com", authenticated, 2))
                .addDomains(getDomain("d.example.com", authenticated, 1))
                .build(),
        )

        assertEquals(setOf("b.example.com", "c.example.com"), getChangedSessions(prev, cur))
        assertTrue(getChangedSessions(emptyMap(), cur).isEmpty())
        assertTrue(getSessionKeys(null).isEmpty())
    }

    @Test
    fun testGetClusterAPIHost() {
        assertEquals("octelium-api.example.com", getClusterAPIHost("example.com"))
    }
}
