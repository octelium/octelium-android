package com.octelium.client.ui.services

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octelium.client.R
import com.octelium.client.core.cluster.SERVICE_TYPES
import com.octelium.client.core.cluster.getServiceHostname
import com.octelium.client.core.cluster.getServicePrivateFQDN
import com.octelium.client.core.cluster.getServicePublicFQDN
import com.octelium.client.core.cluster.getServicePublicURL
import com.octelium.client.core.cluster.getServiceTypeInfo
import com.octelium.client.core.cluster.isServiceWebBrowsable
import com.octelium.client.core.cluster.splitServiceName
import com.octelium.client.core.cluster.tokenizeQuery
import com.octelium.client.core.domain.LabelTone
import com.octelium.client.core.domain.getDomainState
import com.octelium.client.core.domain.isAuthenticated
import com.octelium.client.core.domain.isConnected
import com.octelium.client.ui.DomainOperationBanner
import com.octelium.client.ui.Footer
import com.octelium.client.ui.MainViewModel
import com.octelium.client.ui.components.ButtonSize
import com.octelium.client.ui.components.ButtonVariant
import com.octelium.client.ui.components.CardShape
import com.octelium.client.ui.components.CopyText
import com.octelium.client.ui.components.EmptyState
import com.octelium.client.ui.components.ErrorState
import com.octelium.client.ui.components.InfoItem
import com.octelium.client.ui.components.Label
import com.octelium.client.ui.components.Notice
import com.octelium.client.ui.components.OctButton
import com.octelium.client.ui.components.OctIconButton
import com.octelium.client.ui.components.PageHeader
import com.octelium.client.ui.components.ResourceListSkeleton
import com.octelium.client.ui.components.SearchField
import com.octelium.client.ui.components.SectionCard
import com.octelium.client.ui.components.SelectField
import com.octelium.client.ui.components.SelectOption
import com.octelium.client.ui.rememberOpenURL
import com.octelium.client.ui.theme.OcteliumTheme
import octelium.api.main.user.v1.Userv1

@Composable
fun ServicesScreen(vm: MainViewModel, onNavigateToConnection: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    val domain by vm.selectedDomain.collectAsStateWithLifecycle()
    val state = getDomainState(status, domain)
    val selected = domain

    if (selected == null || !isAuthenticated(state)) {
        Column(modifier = Modifier.padding(16.dp)) {
            EmptyState(
                title = if (selected == null) "Set up your Cluster" else "Sign in to $selected",
                message = if (selected == null) {
                    "Add your primary domain to browse the Services available to you."
                } else {
                    "The Cluster resources are read directly from the Cluster API using your Session."
                },
                icon = if (selected == null) R.drawable.ic_shield else R.drawable.ic_log_in,
                action = {
                    OctButton(text = if (selected == null) "Get started" else "Sign in again", onClick = onNavigateToConnection)
                },
            )
        }
        return
    }

    ServiceList(vm, selected)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceList(vm: MainViewModel, domain: String) {
    val colors = OcteliumTheme.colors
    val svm: ServicesViewModel = viewModel(
        key = "services-$domain",
        factory = viewModelFactory {
            initializer { ServicesViewModel(vm.cluster, domain) }
        },
    )

    val status by vm.status.collectAsStateWithLifecycle()
    val state by svm.state.collectAsStateWithLifecycle()
    val filter by svm.filter.collectAsStateWithLifecycle()
    val namespaces by svm.namespaces.collectAsStateWithLifecycle()
    val domainState = getDomainState(status, domain)

    val tokens = tokenizeQuery(filter.search)
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }

    LaunchedEffect(shouldLoadMore, state.items.size) {
        if (shouldLoadMore) {
            svm.loadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = svm::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Column {
                    DomainOperationBanner(vm, domainState)

                    PageHeader(
                        title = "Services",
                        description = "The Services you are authorized to access at $domain",
                    )

                    if (!isConnected(domainState)) {
                        Notice(
                            modifier = Modifier.padding(bottom = 20.dp),
                            title = "Not connected",
                            content = "You can browse the Services of the Cluster while disconnected. Connect in order to actually reach them from this device.",
                        )
                    }

                    SearchField(
                        value = filter.search,
                        onValueChange = svm::setSearch,
                        placeholder = "Search the Services…",
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SelectField(
                            options = namespaces.map { SelectOption(it.metadata.name, it.metadata.name) },
                            value = filter.namespace,
                            onValueChange = svm::setNamespace,
                            modifier = Modifier.weight(1f),
                            label = "Namespace",
                            placeholder = "Every Namespace",
                            isClearable = true,
                        )

                        SelectField(
                            options = SERVICE_TYPES.map { SelectOption(it.key, it.label) },
                            value = filter.typeKey,
                            onValueChange = svm::setType,
                            modifier = Modifier.weight(1f),
                            label = "Type",
                            placeholder = "Every type",
                            isClearable = true,
                        )
                    }
                }
            }

            when {
                state.isLoading -> item(key = "loading") { ResourceListSkeleton() }

                state.error != null && state.items.isEmpty() -> item(key = "error") {
                    ErrorState(
                        title = "Unable to load the Services",
                        message = "The Cluster API could not be reached. Check that you are still signed in. ${state.error}",
                        onRetry = svm::refresh,
                    )
                }

                state.items.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        title = if (tokens.isNotEmpty()) "Nothing found" else "No Service",
                        message = if (tokens.isNotEmpty()) {
                            "No Service matches your search in this Cluster."
                        } else {
                            "You are not authorized to access any Service yet."
                        },
                        icon = R.drawable.ic_search_x,
                    )
                }

                else -> items(state.items, key = { it.metadata.uid.ifEmpty { it.metadata.name } }) { itm ->
                    ServiceItem(item = itm, domain = domain)
                }
            }

            if (state.isLoadingMore) {
                item(key = "more") {
                    Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = colors.muted, strokeWidth = 2.dp)
                    }
                }
            }

            item(key = "footer") {
                Footer()
            }
        }
    }
}

@Composable
private fun ServiceItem(item: Userv1.Service, domain: String) {
    val colors = OcteliumTheme.colors
    val openURL = rememberOpenURL()
    var isExpanded by rememberSaveable(item.metadata.name) { mutableStateOf(false) }

    val typeInfo = getServiceTypeInfo(item)
    val typeStyle = getServiceTypeStyle(item.spec.type)
    val (name, namespace) = splitServiceName(item.metadata.name)

    SectionCard(padding = 16.dp) {
        Row(
            modifier = Modifier.clickable { isExpanded = !isExpanded },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (colors.isDark) Color(0xFF334155) else Color(0xFF18181B))
                    .border(1.dp, if (colors.isDark) Color(0xFF475569) else Color(0xFF3F3F46).copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(typeStyle.icon),
                    contentDescription = "${typeInfo.label} service",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        color = colors.strong,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    if (namespace != null) {
                        Text(text = ".", color = colors.faint, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            text = namespace,
                            color = colors.muted,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (item.metadata.displayName.isNotEmpty()) {
                    Text(
                        text = item.metadata.displayName,
                        modifier = Modifier.padding(top = 2.dp),
                        color = colors.muted,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Label(text = typeInfo.label, toneColors = typeStyle.palette.getColors(colors.isDark))
                    Label(text = item.spec.port.toString(), tone = LabelTone.SLATE, prefix = "Port")

                    if (item.spec.isTLS) {
                        Label(text = "TLS", tone = LabelTone.EMERALD, icon = R.drawable.ic_shield_check)
                    }

                    if (item.spec.isPublic) {
                        Label(text = "Public", tone = LabelTone.SKY, icon = R.drawable.ic_globe)
                    }

                    Label(text = getServiceHostname(item), tone = LabelTone.NEUTRAL, isMono = true)
                }
            }

            OctIconButton(
                icon = R.drawable.ic_chevron_down,
                contentDescription = if (isExpanded) "Hide the details" else "Show the details",
                onClick = { isExpanded = !isExpanded },
                modifier = Modifier.rotate(if (isExpanded) 180f else 0f),
            )
        }

        if (item.spec.isPublic && isServiceWebBrowsable(item)) {
            OctButton(
                text = "Open",
                onClick = { openURL(getServicePublicURL(item, domain)) },
                modifier = Modifier.padding(top = 12.dp),
                variant = ButtonVariant.OUTLINE,
                size = ButtonSize.XS,
                icon = R.drawable.ic_arrow_up_right,
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            ServiceDetails(item, domain)
        }
    }
}

@Composable
private fun ServiceDetails(item: Userv1.Service, domain: String) {
    val colors = OcteliumTheme.colors

    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.surface2)
            .border(1.dp, colors.line, CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (item.metadata.description.isNotEmpty()) {
            Text(text = item.metadata.description, color = colors.body, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }

        InfoItem(title = "Private FQDN") {
            CopyText(value = getServicePrivateFQDN(item, domain))
        }

        if (item.spec.isPublic) {
            InfoItem(title = "Public FQDN") {
                CopyText(value = getServicePublicFQDN(item, domain))
            }
        }

        InfoItem(title = "Resource name") {
            CopyText(value = item.metadata.name)
        }

        if (item.status.addressesCount > 0) {
            InfoItem(title = "Private addresses") {
                Column {
                    item.status.addressesList.forEach { CopyText(value = it) }
                }
            }
        }
    }
}
