package com.octelium.client.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octelium.client.R
import com.octelium.client.core.domain.canConnect
import com.octelium.client.core.domain.canDisconnect
import com.octelium.client.core.domain.getAuthenticationStateLabel
import com.octelium.client.core.domain.getAuthenticationStateTone
import com.octelium.client.core.domain.getConnectionStateLabel
import com.octelium.client.core.domain.getConnectionStateTone
import com.octelium.client.core.domain.getDNSModeLabel
import com.octelium.client.core.domain.getDomainState
import com.octelium.client.core.domain.getImplementationModeLabel
import com.octelium.client.core.domain.getTunnelDomainState
import com.octelium.client.core.domain.getTunnelModeLabel
import com.octelium.client.core.domain.isAuthenticated
import com.octelium.client.core.domain.isConnected
import com.octelium.client.core.domain.isConnectionBusy
import com.octelium.client.core.domain.printDuration
import com.octelium.client.core.domain.printTimeAgo
import com.octelium.client.core.network.getNetworkLabel
import com.octelium.client.ui.DomainOperationBanner
import com.octelium.client.ui.MainViewModel
import com.octelium.client.ui.ScreenColumn
import com.octelium.client.ui.components.AlertBox
import com.octelium.client.ui.components.AlertText
import com.octelium.client.ui.components.ButtonSize
import com.octelium.client.ui.components.ButtonVariant
import com.octelium.client.ui.components.ConfirmDialog
import com.octelium.client.ui.components.CopyText
import com.octelium.client.ui.components.ErrorBanner
import com.octelium.client.ui.components.InfoItem
import com.octelium.client.ui.components.InfoText
import com.octelium.client.ui.components.Label
import com.octelium.client.ui.components.Mono
import com.octelium.client.ui.components.Notice
import com.octelium.client.ui.components.OctButton
import com.octelium.client.ui.components.PageHeader
import com.octelium.client.ui.components.SectionCard
import com.octelium.client.ui.components.SectionTitle
import com.octelium.client.ui.components.StatusDot
import com.octelium.client.ui.components.rememberMutation
import com.octelium.client.ui.rememberConnectLauncher
import com.octelium.client.ui.theme.AlertTone
import com.octelium.client.ui.theme.OcteliumTheme
import kotlinx.coroutines.delay
import octelium.api.client.daemon.v1.Daemonv1.DomainState
import octelium.api.main.user.v1.Userv1
import java.time.Instant

@Composable
fun ConnectionScreen(vm: MainViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val domain by vm.selectedDomain.collectAsStateWithLifecycle()
    val authCallbackError by vm.authCallbackError.collectAsStateWithLifecycle()
    val isCompletingAuthentication by vm.isCompletingAuthentication.collectAsStateWithLifecycle()
    val state = getDomainState(status, domain)

    ScreenColumn {
        DomainOperationBanner(vm, state)

        if (isCompletingAuthentication) {
            AlertBox(tone = AlertTone.BLUE, modifier = Modifier.padding(bottom = 20.dp), title = "Completing the sign in") {
                AlertText(text = "The Cluster is verifying your browser authentication.")
            }
        }

        authCallbackError?.let {
            AlertBox(tone = AlertTone.RED, modifier = Modifier.padding(bottom = 20.dp), title = "Could not complete the sign in") {
                AlertText(text = it)
                Spacer(modifier = Modifier.height(10.dp))
                OctButton(
                    text = "Dismiss",
                    onClick = vm::dismissAuthCallbackError,
                    variant = ButtonVariant.OUTLINE,
                    size = ButtonSize.XS,
                    isDanger = true,
                )
            }
        }

        val selected = domain
        when {
            selected == null -> ClusterSignIn(vm)
            !isAuthenticated(state) -> {
                ErrorBanner(error = state?.takeIf { it.hasLastError() }?.lastError)
                ClusterSignIn(vm, domain = selected)
            }

            else -> ConnectionDetails(vm, selected, state!!)
        }
    }
}

@Composable
private fun ColumnScope.ConnectionDetails(vm: MainViewModel, domain: String, state: DomainState) {
    val colors = OcteliumTheme.colors
    val status by vm.status.collectAsStateWithLifecycle()
    val networkInfo by vm.networkInfo.collectAsStateWithLifecycle()
    val isOffline by vm.isOffline.collectAsStateWithLifecycle()
    val connection = state.connection
    val isConnected = isConnected(state)

    var tick by remember { mutableIntStateOf(0) }
    var isPermissionDenied by remember { mutableStateOf(false) }
    var isConfirmingLogout by remember { mutableStateOf(false) }

    LaunchedEffect(isConnected, connection.connectedAt) {
        while (isConnected) {
            delay(1000)
            tick++
        }
    }

    val mutationConnect = rememberMutation<String> { vm.connect(it) }
    val mutationDisconnect = rememberMutation<String> { vm.disconnect(it) }
    val mutationLogout = rememberMutation<String>(onSuccess = { isConfirmingLogout = false }) { vm.logout(it) }

    val connect = rememberConnectLauncher(
        vm = vm,
        onDenied = { isPermissionDenied = true },
        onGranted = {
            isPermissionDenied = false
            mutationConnect.mutate(it)
        },
    )

    val tunnelDomain = getTunnelDomainState(status)?.domain
    val isOtherTunnel = tunnelDomain != null && tunnelDomain != domain

    val isBusy = isConnectionBusy(state) || mutationConnect.isPending || mutationDisconnect.isPending
    val now = remember(tick) { Instant.now() }

    PageHeader(
        title = "Connection",
        description = domain,
        actions = {
            Label(
                text = getAuthenticationStateLabel(state.authentication.state),
                tone = getAuthenticationStateTone(state.authentication.state),
                icon = R.drawable.ic_shield_check,
            )
        },
    )

    ErrorBanner(
        error = state.takeIf { it.hasLastError() }?.lastError,
        onRetry = { connect(domain) },
        isPending = mutationConnect.isPending,
    )

    if (isOffline) {
        Notice(
            modifier = Modifier.padding(bottom = 16.dp),
            title = "No network",
            icon = R.drawable.ic_wifi_off,
            content = "This device is currently offline. Octelium reconnects automatically once a network becomes available.",
        )
    }

    SectionCard(padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusDot(
                tone = getConnectionStateTone(connection.state),
                size = 14.dp,
                pulse = isConnectionBusy(state),
            )

            Column {
                Text(
                    text = getConnectionStateLabel(connection.state),
                    color = colors.strong,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                )
                Text(
                    text = if (isConnected && connection.hasConnectedAt()) {
                        "Connected for ${printDuration(connection.connectedAt, now)}"
                    } else {
                        "Your Cluster credentials are ready"
                    },
                    modifier = Modifier.padding(top = 2.dp),
                    color = colors.muted,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (canDisconnect(state)) {
            OctButton(
                text = "Disconnect",
                onClick = { mutationDisconnect.mutate(domain) },
                variant = ButtonVariant.OUTLINE,
                size = ButtonSize.MD,
                icon = R.drawable.ic_plug,
                fullWidth = true,
                isLoading = mutationDisconnect.isPending,
            )
        } else {
            OctButton(
                text = "Connect",
                onClick = { connect(domain) },
                size = ButtonSize.MD,
                icon = R.drawable.ic_plug_zap,
                fullWidth = true,
                isLoading = mutationConnect.isPending,
                enabled = canConnect(state) && !isBusy && !isOtherTunnel,
            )
        }

        val error = mutationConnect.error ?: mutationDisconnect.error ?: when {
            isPermissionDenied -> "The VPN permission was not granted. Octelium needs it in order to route the Cluster traffic of this device."
            isOtherTunnel -> "The domain $tunnelDomain is already connected. Only a single Cluster can be connected at a time on this device."
            else -> null
        }

        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.padding(top = 14.dp),
                color = AlertTone.RED.getColors(colors.isDark).content,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
    }

    if (isConnected) {
        Spacer(modifier = Modifier.height(16.dp))
        TunnelCard(state)
        Spacer(modifier = Modifier.height(16.dp))
        SessionCard(vm, domain, networkInfo = getNetworkLabel(networkInfo))
    }

    Spacer(modifier = Modifier.height(24.dp))

    OctButton(
        text = "Sign out",
        onClick = { isConfirmingLogout = true },
        variant = ButtonVariant.SUBTLE,
        size = ButtonSize.SM,
        icon = R.drawable.ic_log_out,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )

    ConfirmDialog(
        isOpen = isConfirmingLogout,
        title = "Sign out",
        text = "Signing out of $domain disconnects the Cluster, invalidates the Session and removes the stored credentials of this device.",
        confirmLabel = "Sign out",
        onConfirm = { mutationLogout.mutate(domain) },
        onDismiss = {
            isConfirmingLogout = false
            mutationLogout.reset()
        },
        isPending = mutationLogout.isPending,
        error = mutationLogout.error,
    )
}

@Composable
private fun TunnelCard(state: DomainState) {
    val connection = state.connection
    val dns = connection.dns

    SectionCard {
        SectionTitle(text = "Tunnel", modifier = Modifier.padding(bottom = 16.dp))

        InfoGrid {
            item("Tunnel mode") { InfoText(getTunnelModeLabel(connection.tunnelMode)) }
            item("Implementation") { InfoText(getImplementationModeLabel(connection.implementationMode)) }
            item("MTU") { InfoText(if (connection.mtu > 0) connection.mtu.toString() else "—") }
            item("Connected at") { InfoText(printTimeAgo(connection.connectedAt)) }
            item("DNS") {
                InfoText(getDNSModeLabel(dns.mode) + if (connection.hasDns() && !dns.isConfigured) " (not applied)" else "")
            }
        }

        val addresses = connection.addressesList.flatMap { listOf(it.v4, it.v6) }.filter { it.isNotEmpty() }
        if (addresses.isNotEmpty()) {
            InfoItem(title = "Addresses", modifier = Modifier.padding(top = 16.dp)) {
                Column {
                    addresses.forEach { CopyText(value = it) }
                }
            }
        }

        if (dns.serversCount > 0) {
            InfoItem(title = "DNS servers", modifier = Modifier.padding(top = 16.dp)) {
                Column {
                    dns.serversList.forEach { CopyText(value = it) }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(vm: MainViewModel, domain: String, networkInfo: String) {
    val resp by produceState<Result<Userv1.GetStatusResponse>?>(null, domain) {
        value = runCatching { vm.cluster.getStatus(domain) }
    }

    val data = resp?.getOrNull() ?: return

    SectionCard {
        SectionTitle(text = "Session", modifier = Modifier.padding(bottom = 16.dp))

        InfoGrid {
            item("User") { InfoText(data.user.metadata.name.ifEmpty { "—" }) }
            item("Email") { InfoText(data.user.spec.email.ifEmpty { "—" }) }
            item("Cluster") { InfoText(data.cluster.metadata.name.ifEmpty { domain }) }
            item("Cluster-side state") { InfoText(if (data.session.status.isConnected) "Connected" else "Not connected") }
            item("Network") { InfoText(networkInfo) }
        }

        InfoItem(title = "Session", modifier = Modifier.padding(top = 16.dp)) {
            Mono(text = data.session.metadata.name.ifEmpty { "—" })
        }
    }
}

class InfoGridScope {
    val items = mutableListOf<Pair<String, @Composable () -> Unit>>()

    fun item(title: String, content: @Composable () -> Unit) {
        items.add(title to content)
    }
}

@Composable
fun InfoGrid(columns: Int = 2, builder: InfoGridScope.() -> Unit) {
    val scope = InfoGridScope().apply(builder)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        scope.items.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (title, content) ->
                    InfoItem(title = title, modifier = Modifier.weight(1f)) {
                        content()
                    }
                }

                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
