package com.octelium.client.ui.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.octelium.client.core.cluster.ClusterClient
import com.octelium.client.core.cluster.getCommonListOptions
import com.octelium.client.core.cluster.getServiceTypeByKey
import com.octelium.client.core.cluster.matchesService
import com.octelium.client.core.cluster.tokenizeQuery
import com.octelium.client.core.local.getErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import octelium.api.main.user.v1.Userv1
import java.time.Duration
import java.time.Instant

data class ServicesFilter(
    val search: String = "",
    val namespace: String? = null,
    val typeKey: String? = null,
)

data class ServicesState(
    val items: List<Userv1.Service> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val nextPage: Int = 0,
)

private data class AllServices(
    val namespace: String,
    val type: Userv1.Service.Spec.Type,
    val items: List<Userv1.Service>,
    val fetchedAt: Instant,
)

class ServicesViewModel(
    private val cluster: ClusterClient,
    private val domain: String,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private val _filter = MutableStateFlow(ServicesFilter())
    val filter: StateFlow<ServicesFilter> = _filter.asStateFlow()

    private val _state = MutableStateFlow(ServicesState())
    val state: StateFlow<ServicesState> = _state.asStateFlow()

    private val _namespaces = MutableStateFlow<List<Userv1.Namespace>>(emptyList())
    val namespaces: StateFlow<List<Userv1.Namespace>> = _namespaces.asStateFlow()

    private var job: Job? = null
    private var allServices: AllServices? = null

    init {
        load(isRefresh = false, debounce = false)

        viewModelScope.launch {
            try {
                _namespaces.value = cluster.listAllNamespaces(domain)
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                _namespaces.value = emptyList()
            }
        }
    }

    fun setSearch(arg: String) {
        _filter.update { it.copy(search = arg) }
        load(isRefresh = false, debounce = true)
    }

    fun setNamespace(arg: String?) {
        _filter.update { it.copy(namespace = arg) }
        load(isRefresh = false, debounce = false)
    }

    fun setType(arg: String?) {
        _filter.update { it.copy(typeKey = arg) }
        load(isRefresh = false, debounce = false)
    }

    fun refresh() {
        load(isRefresh = true, debounce = false)
    }

    fun loadMore() {
        val cur = _state.value
        if (cur.isLoading || cur.isLoadingMore || !cur.hasMore || job?.isActive == true) {
            return
        }

        _state.update { it.copy(isLoadingMore = true) }

        job = viewModelScope.launch {
            try {
                val resp = listPage(_filter.value, cur.nextPage)
                _state.update {
                    it.copy(
                        items = it.items + resp.itemsList,
                        isLoadingMore = false,
                        hasMore = resp.listResponseMeta.hasMore && resp.itemsList.isNotEmpty(),
                        nextPage = cur.nextPage + 1,
                    )
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                _state.update { it.copy(isLoadingMore = false, error = getErrorMessage(err)) }
            }
        }
    }

    private fun load(isRefresh: Boolean, debounce: Boolean) {
        job?.cancel()

        if (!isRefresh) {
            getCachedSearch(_filter.value)?.let {
                _state.value = ServicesState(items = it, isLoading = false)
                return
            }
        }

        _state.update {
            if (isRefresh) {
                it.copy(isRefreshing = true, error = null)
            } else {
                it.copy(isLoading = true, error = null)
            }
        }

        job = viewModelScope.launch {
            if (debounce) {
                delay(SEARCH_DEBOUNCE_MS)
            }

            val filter = _filter.value
            val tokens = tokenizeQuery(filter.search)

            try {
                if (tokens.isNotEmpty()) {
                    val items = listAllServices(filter).filter { matchesService(it, tokens) }

                    _state.value = ServicesState(items = items, isLoading = false)
                    return@launch
                }

                val resp = listPage(filter, 0)
                _state.value = ServicesState(
                    items = resp.itemsList,
                    isLoading = false,
                    hasMore = resp.listResponseMeta.hasMore && resp.itemsList.isNotEmpty(),
                    nextPage = 1,
                )
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, error = getErrorMessage(err))
                }
            }
        }
    }

    private fun getCachedSearch(filter: ServicesFilter): List<Userv1.Service>? {
        val tokens = tokenizeQuery(filter.search)
        val ret = allServices ?: return null

        if (tokens.isEmpty() || ret.namespace != filter.namespace.orEmpty() || ret.type != getServiceType(filter) ||
            Duration.between(ret.fetchedAt, now()) >= SEARCH_STALE_TIME
        ) {
            return null
        }

        return ret.items.filter { matchesService(it, tokens) }
    }

    private suspend fun listAllServices(filter: ServicesFilter): List<Userv1.Service> {
        val namespace = filter.namespace.orEmpty()
        val type = getServiceType(filter)

        val ret = cluster.listAllServices(domain, namespace = namespace, type = type)
        allServices = AllServices(namespace, type, ret, now())

        return ret
    }

    private fun getServiceType(filter: ServicesFilter): Userv1.Service.Spec.Type =
        getServiceTypeByKey(filter.typeKey)?.type ?: Userv1.Service.Spec.Type.UNSET

    private suspend fun listPage(filter: ServicesFilter, page: Int): Userv1.ServiceList = cluster.listService(
        domain,
        Userv1.ListServiceOptions.newBuilder()
            .setCommon(getCommonListOptions(page, ITEMS_PER_PAGE))
            .setNamespace(filter.namespace.orEmpty())
            .setType(getServiceType(filter))
            .build(),
    )

    companion object {
        const val ITEMS_PER_PAGE = 50
        private const val SEARCH_DEBOUNCE_MS = 250L
        private val SEARCH_STALE_TIME = Duration.ofSeconds(30)
    }
}
