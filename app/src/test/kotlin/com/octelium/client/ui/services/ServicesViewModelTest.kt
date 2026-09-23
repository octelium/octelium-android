package com.octelium.client.ui.services

import com.octelium.client.core.cluster.ClusterClient
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.main.meta.v1.Metav1
import octelium.api.main.user.v1.MainServiceGrpcKt
import octelium.api.main.user.v1.Userv1
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ServicesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val serverName = "services-${UUID.randomUUID()}"
    private var isFailing = false

    private val services = (0 until 120).map { idx ->
        Userv1.Service.newBuilder()
            .setMetadata(
                Metav1.Metadata.newBuilder()
                    .setUid("uid-$idx")
                    .setName(if (idx % 2 == 0) "api-$idx.production" else "db-$idx.staging")
            )
            .setSpec(
                Userv1.Service.Spec.newBuilder()
                    .setType(if (idx % 2 == 0) Userv1.Service.Spec.Type.HTTP else Userv1.Service.Spec.Type.POSTGRES)
            )
            .setStatus(Userv1.Service.Status.newBuilder().setNamespace(if (idx % 2 == 0) "production" else "staging"))
            .build()
    }

    private val server = InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(object : MainServiceGrpcKt.MainServiceCoroutineImplBase(Dispatchers.Unconfined) {
            override suspend fun listService(request: Userv1.ListServiceOptions): Userv1.ServiceList {
                if (isFailing) {
                    throw StatusException(Status.UNAVAILABLE.withDescription("unreachable"))
                }

                val items = services.filter {
                    (request.namespace.isEmpty() || it.status.namespace == request.namespace) &&
                        (request.type == Userv1.Service.Spec.Type.UNSET || it.spec.type == request.type)
                }

                val start = request.common.page * request.common.itemsPerPage
                val end = minOf(start + request.common.itemsPerPage, items.size)

                return Userv1.ServiceList.newBuilder()
                    .addAllItems(if (start < end) items.subList(start, end) else emptyList())
                    .setListResponseMeta(Metav1.ListResponseMeta.newBuilder().setHasMore(end < items.size))
                    .build()
            }

            override suspend fun listNamespace(request: Userv1.ListNamespaceOptions): Userv1.NamespaceList =
                Userv1.NamespaceList.newBuilder()
                    .addAllItems(
                        listOf("production", "staging").map {
                            Userv1.Namespace.newBuilder().setMetadata(Metav1.Metadata.newBuilder().setName(it)).build()
                        },
                    )
                    .build()
        })
        .build()

    private val cluster = ClusterClient(
        credentials = { Daemonv1.GetAPICredentialResponse.newBuilder().setAccessToken("token").build() },
        channels = { InProcessChannelBuilder.forName(serverName).directExecutor().build() },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server.start()
    }

    @After
    fun tearDown() {
        cluster.close()
        server.shutdownNow()
        Dispatchers.resetMain()
    }

    @Test
    fun testPaging() = runTest(dispatcher) {
        val vm = ServicesViewModel(cluster, "example.com")
        assertTrue(vm.state.value.isLoading)

        advanceUntilIdle()

        run {
            val state = vm.state.value
            assertFalse(state.isLoading)
            assertEquals(ServicesViewModel.ITEMS_PER_PAGE, state.items.size)
            assertTrue(state.hasMore)
            assertEquals(1, state.nextPage)
            assertEquals(listOf("production", "staging"), vm.namespaces.value.map { it.metadata.name })
        }

        run {
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(100, vm.state.value.items.size)
            assertTrue(vm.state.value.hasMore)

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(120, vm.state.value.items.size)
            assertFalse(vm.state.value.hasMore)

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(120, vm.state.value.items.size)
            assertEquals(120, vm.state.value.items.map { it.metadata.uid }.toSet().size)
        }
    }

    @Test
    fun testFilters() = runTest(dispatcher) {
        val vm = ServicesViewModel(cluster, "example.com")
        advanceUntilIdle()

        run {
            vm.setNamespace("staging")
            advanceUntilIdle()
            assertEquals("staging", vm.filter.value.namespace)
            assertEquals(ServicesViewModel.ITEMS_PER_PAGE, vm.state.value.items.size)
            assertTrue(vm.state.value.items.all { it.status.namespace == "staging" })
        }

        run {
            vm.setNamespace(null)
            vm.setType("HTTP")
            advanceUntilIdle()
            assertTrue(vm.state.value.items.all { it.spec.type == Userv1.Service.Spec.Type.HTTP })
        }

        run {
            vm.setType(null)
            vm.setSearch("api-1")
            advanceUntilIdle()

            val names = vm.state.value.items.map { it.metadata.name }
            assertEquals(listOf("api-10.production", "api-12.production", "api-14.production"), names.take(3))
            assertTrue(names.all { it.contains("api-1") })
            assertFalse(vm.state.value.hasMore)
        }

        run {
            vm.setSearch("nothing matches")
            advanceUntilIdle()
            assertTrue(vm.state.value.items.isEmpty())
            assertNull(vm.state.value.error)
        }
    }

    @Test
    fun testErrors() = runTest(dispatcher) {
        isFailing = true

        val vm = ServicesViewModel(cluster, "example.com")
        advanceUntilIdle()

        assertFalse(vm.state.value.isLoading)
        assertEquals("unreachable", vm.state.value.error)
        assertTrue(vm.state.value.items.isEmpty())

        isFailing = false
        vm.refresh()
        assertTrue(vm.state.value.isRefreshing)
        advanceUntilIdle()

        assertFalse(vm.state.value.isRefreshing)
        assertNull(vm.state.value.error)
        assertEquals(ServicesViewModel.ITEMS_PER_PAGE, vm.state.value.items.size)
    }
}
