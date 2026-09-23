package com.octelium.client.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octelium.client.BuildConfig
import com.octelium.client.R
import com.octelium.client.core.domain.DNS_MODES
import com.octelium.client.core.domain.DomainSettingsForm
import com.octelium.client.core.domain.L3_MODES
import com.octelium.client.core.domain.LabelTone
import com.octelium.client.core.domain.TUNNEL_MODES
import com.octelium.client.core.domain.canConnect
import com.octelium.client.core.domain.canDisconnect
import com.octelium.client.core.domain.getAuthenticationStateLabel
import com.octelium.client.core.domain.getAuthenticationStateTone
import com.octelium.client.core.domain.getConnectionStateLabel
import com.octelium.client.core.domain.getConnectionStateTone
import com.octelium.client.core.domain.getDNSModeOptionLabel
import com.octelium.client.core.domain.getDomainSettingsForm
import com.octelium.client.core.domain.getDomainState
import com.octelium.client.core.domain.getL3ModeLabel
import com.octelium.client.core.domain.getTunnelModeLabel
import com.octelium.client.core.domain.isAuthenticated
import com.octelium.client.core.domain.isConnected
import com.octelium.client.core.domain.isConnectionBusy
import com.octelium.client.core.domain.toDomainSettings
import com.octelium.client.core.domain.validateDomainSettingsForm
import com.octelium.client.core.prefs.ThemeMode
import com.octelium.client.core.prefs.getThemeModeLabel
import com.octelium.client.runtime.RuntimeState
import com.octelium.client.ui.MainViewModel
import com.octelium.client.ui.ScreenColumn
import com.octelium.client.ui.components.AlertBox
import com.octelium.client.ui.components.AlertText
import com.octelium.client.ui.components.ButtonSize
import com.octelium.client.ui.components.ButtonVariant
import com.octelium.client.ui.components.ConfirmDialog
import com.octelium.client.ui.components.Label
import com.octelium.client.ui.components.Notice
import com.octelium.client.ui.components.OctButton
import com.octelium.client.ui.components.OctSwitch
import com.octelium.client.ui.components.OctTextField
import com.octelium.client.ui.components.PageHeader
import com.octelium.client.ui.components.SectionCard
import com.octelium.client.ui.components.SectionTitle
import com.octelium.client.ui.components.Segment
import com.octelium.client.ui.components.SegmentedControl
import com.octelium.client.ui.components.SelectField
import com.octelium.client.ui.components.SelectOption
import com.octelium.client.ui.components.SettingRow
import com.octelium.client.ui.components.StatusDot
import com.octelium.client.ui.components.TextLinkButton
import com.octelium.client.ui.components.rememberMutation
import com.octelium.client.ui.connection.ClusterSignIn
import com.octelium.client.ui.rememberConnectLauncher
import com.octelium.client.ui.rememberOpenURL
import com.octelium.client.ui.theme.AlertTone
import com.octelium.client.ui.theme.OcteliumTheme
import octelium.api.client.daemon.v1.Daemonv1.DomainState

enum class SettingsSection(
    val key: String,
    val title: String,
    val description: String,
) {
    APPLICATION(
        "application",
        "Application settings",
        "Preferences for this Android application, independent of any Cluster.",
    ),
    CLUSTER(
        "cluster",
        "Cluster settings",
        "Connection policy for the selected Cluster. Changes apply on the next Connection.",
    ),
    CLUSTERS(
        "clusters",
        "Manage Clusters",
        "Add, select, sign out of, or remove Cluster domains from this device.",
    ),
}

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    initialSection: String?,
    onNavigateToConnection: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
) {
    val domain by vm.selectedDomain.collectAsStateWithLifecycle()
    var sectionKey by rememberSaveable { mutableStateOf(initialSection ?: SettingsSection.APPLICATION.key) }

    val section = SettingsSection.entries.find { it.key == sectionKey }
        ?.takeIf { it != SettingsSection.CLUSTER || domain != null }
        ?: SettingsSection.APPLICATION

    ScreenColumn {
        PageHeader(title = section.title, description = section.description)

        SegmentedControl(
            segments = listOf(
                Segment(SettingsSection.APPLICATION, "Application"),
                Segment(SettingsSection.CLUSTER, "Cluster", enabled = domain != null),
                Segment(SettingsSection.CLUSTERS, "Clusters"),
            ),
            value = section,
            onValueChange = { sectionKey = it.key },
            modifier = Modifier.padding(bottom = 20.dp),
        )

        when (section) {
            SettingsSection.APPLICATION -> AppSettings(vm, onNavigateToDiagnostics)
            SettingsSection.CLUSTER -> domain?.let { d ->
                key(d) {
                    DomainSettings(vm, d)
                }
            }

            SettingsSection.CLUSTERS -> Clusters(vm, onNavigateToConnection)
        }
    }
}

@Composable
private fun AppSettings(vm: MainViewModel, onNavigateToDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val runtime by vm.runtimeState.collectAsStateWithLifecycle()
    val info = (runtime as? RuntimeState.Ready)?.info

    SectionCard {
        SectionTitle(text = "Application preferences")
        Text(
            text = "These preferences belong to this application. They never affect your Cluster Sessions.",
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            color = OcteliumTheme.colors.muted,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )

        SettingRow(
            title = "Current version",
            description = if (BuildConfig.RELEASE_TAG.isNotEmpty()) {
                "The version installed on this device."
            } else {
                "This build was not created from a release tag."
            },
        ) {
            ValueText(BuildConfig.RELEASE_TAG.ifEmpty { "Development build" })
        }

        SettingRow(title = "liboctelium", description = "The embedded Octelium client library.") {
            ValueText(getLibVersion(info?.version))
        }

        SettingRow(title = "Theme") {
            SelectField(
                options = ThemeMode.entries.map { SelectOption(it, getThemeModeLabel(it)) },
                value = prefs?.theme ?: ThemeMode.SYSTEM,
                onValueChange = { vm.setTheme(it ?: ThemeMode.SYSTEM) },
                modifier = Modifier.width(140.dp),
            )
        }

        SettingRow(
            title = "Multiple Clusters",
            description = "Show every configured domain in the Cluster switcher. Most people only need one domain.",
        ) {
            OctSwitch(checked = prefs?.multiCluster ?: false, onCheckedChange = vm::setMultiCluster)
        }

        SettingRow(
            title = "Always-on VPN",
            description = "Let Android keep the Octelium Connection of your primary Cluster running at all times.",
        ) {
            OctButton(
                text = "Open",
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                    } catch (err: ActivityNotFoundException) {
                        Toast.makeText(context, "The VPN settings are not available", Toast.LENGTH_LONG).show()
                    }
                },
                variant = ButtonVariant.DEFAULT,
                size = ButtonSize.XS,
            )
        }

        SettingRow(title = "Diagnostics", description = "Runtime information for troubleshooting.", isLast = true) {
            OctButton(
                text = "View",
                onClick = onNavigateToDiagnostics,
                variant = ButtonVariant.DEFAULT,
                size = ButtonSize.XS,
                icon = R.drawable.ic_stethoscope,
            )
        }
    }
}

fun getLibVersion(version: String?): String {
    if (!version.isNullOrEmpty()) {
        return version
    }

    if (BuildConfig.LIBOCTELIUM_COMMIT.isNotEmpty()) {
        return BuildConfig.LIBOCTELIUM_COMMIT.take(12)
    }

    return "—"
}

@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        color = OcteliumTheme.colors.strong,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ColumnScope.DomainSettings(vm: MainViewModel, domain: String) {
    val colors = OcteliumTheme.colors
    val status by vm.status.collectAsStateWithLifecycle()
    val state = getDomainState(status, domain)

    val current = getDomainSettingsForm(state?.takeIf { it.hasSettings() }?.settings)
    var baseline by remember(domain, current) { mutableStateOf(current) }
    var form by remember(domain, current) { mutableStateOf(current) }
    var isAdvanced by rememberSaveable { mutableStateOf(false) }

    val mutation = rememberMutation<DomainSettingsForm>(
        onSuccess = { baseline = it },
    ) {
        vm.updateDomainSettings(domain, toDomainSettings(domain, it))
    }

    val formError = validateDomainSettingsForm(form)
    val isDirty = form != baseline

    val update: (DomainSettingsForm) -> Unit = {
        mutation.reset()
        form = it
    }

    SectionCard {
        SectionTitle(text = domain)
        Text(
            text = "These settings are stored by liboctelium on this device and they are used by the next Connection of this Cluster.",
            modifier = Modifier.padding(top = 4.dp),
            color = colors.muted,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )

        if (isConnected(state)) {
            Notice(
                modifier = Modifier.padding(top = 16.dp),
                title = "Already connected",
                content = "Saving does not reconfigure the active Connection. Reconnect in order to apply the new settings.",
            )
        }

        mutation.error?.let {
            AlertBox(tone = AlertTone.RED, modifier = Modifier.padding(top = 16.dp), title = "Could not save") {
                AlertText(text = it)
            }
        }

        formError?.let {
            AlertBox(tone = AlertTone.ORANGE, modifier = Modifier.padding(top = 16.dp), title = "Check the settings") {
                AlertText(text = it)
            }
        }

        if (mutation.isSuccess && !isDirty) {
            AlertBox(tone = AlertTone.GREEN, modifier = Modifier.padding(top = 16.dp), title = "Saved") {
                AlertText(text = "The settings of the Cluster were stored.")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        SettingRow(
            title = "Auto connect",
            description = "Connect this Cluster whenever Octelium starts and usable credentials are available.",
        ) {
            OctSwitch(checked = form.autoConnect, onCheckedChange = { update(form.copy(autoConnect = it)) })
        }

        SettingRow(title = "Tunnel mode") {
            SelectField(
                options = TUNNEL_MODES.map { SelectOption(it, getTunnelModeLabel(it)) },
                value = form.tunnelMode,
                onValueChange = { it?.let { v -> update(form.copy(tunnelMode = v)) } },
                modifier = Modifier.width(160.dp),
            )
        }

        SettingRow(
            title = "DNS",
            description = "Android cannot route DNS queries per domain. While connected, the Cluster DNS resolves the DNS queries of the other apps unless DNS is disabled.",
            isLast = !isAdvanced,
            isStacked = true,
        ) {
            SelectField(
                options = DNS_MODES.map { SelectOption(it, getDNSModeOptionLabel(it)) },
                value = form.dnsMode,
                onValueChange = { it?.let { v -> update(form.copy(dnsMode = v)) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TextLinkButton(
            text = "Advanced",
            onClick = { isAdvanced = !isAdvanced },
            modifier = Modifier.padding(top = 8.dp),
            trailingIcon = R.drawable.ic_chevron_down,
            iconRotation = if (isAdvanced) 180f else 0f,
        )

        AnimatedVisibility(visible = isAdvanced) {
            Column {
                SettingRow(title = "Layer 3 mode") {
                    SelectField(
                        options = L3_MODES.map { SelectOption(it, getL3ModeLabel(it)) },
                        value = form.l3Mode,
                        onValueChange = { it?.let { v -> update(form.copy(l3Mode = v)) } },
                        modifier = Modifier.width(160.dp),
                    )
                }

                SettingRow(title = "MTU", description = "Leave it empty in order to let Octelium choose.", isLast = true) {
                    OctTextField(
                        value = form.mtu,
                        onValueChange = { update(form.copy(mtu = it.filter { c -> c.isDigit() }.take(4))) },
                        modifier = Modifier.width(120.dp),
                        placeholder = "Auto",
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
        }
    }

    if (isDirty) {
        SectionCard(modifier = Modifier.padding(top = 16.dp), padding = 14.dp) {
            Text(text = "Unsaved changes", color = colors.strong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                text = "Save or cancel before leaving these Cluster settings.",
                color = colors.muted,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                OctButton(
                    text = "Cancel",
                    onClick = {
                        form = baseline
                        mutation.reset()
                    },
                    variant = ButtonVariant.OUTLINE,
                    enabled = !mutation.isPending,
                )
                OctButton(
                    text = "Save changes",
                    onClick = { mutation.mutate(form) },
                    icon = R.drawable.ic_save,
                    isLoading = mutation.isPending,
                    enabled = formError == null,
                )
            }
        }
    }
}

@Composable
private fun Clusters(vm: MainViewModel, onNavigateToConnection: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    val domains = status?.domainsList.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ClusterSignIn(
            vm = vm,
            isCompact = true,
            title = if (domains.isNotEmpty()) "Add another Cluster" else "Add a Cluster",
            description = if (domains.isNotEmpty()) {
                "Add another domain. The selected domain becomes the primary Cluster shown throughout the app."
            } else {
                "Enter your Cluster domain to sign in and connect this device."
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (itm in domains) {
                key(itm.domain) {
                    ClusterItem(vm, itm, onNavigateToConnection)
                }
            }
        }
    }
}

private enum class ClusterAction {
    CONNECT,
    DISCONNECT,
    LOGOUT,
    DELETE,
}

@Composable
private fun ClusterItem(vm: MainViewModel, item: DomainState, onNavigateToConnection: () -> Unit) {
    val colors = OcteliumTheme.colors
    val openURL = rememberOpenURL()
    val selected by vm.selectedDomain.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<ClusterAction?>(null) }
    var isPermissionDenied by remember { mutableStateOf(false) }

    val mutation = rememberMutation<ClusterAction>(onSuccess = { confirm = null }) { action ->
        when (action) {
            ClusterAction.CONNECT -> vm.connect(item.domain)
            ClusterAction.DISCONNECT -> vm.disconnect(item.domain)
            ClusterAction.LOGOUT -> vm.logout(item.domain)
            ClusterAction.DELETE -> vm.deleteDomain(item.domain)
        }
    }

    val mutationAuth = rememberMutation<Unit> {
        vm.selectDomain(item.domain)
        val op = vm.authenticateBrowser(item.domain)
        if (op.hasAction() && op.action.hasOpenURL()) {
            openURL(op.action.openURL.url)
        }
    }

    val connect = rememberConnectLauncher(
        vm = vm,
        onDenied = { isPermissionDenied = true },
        onGranted = {
            isPermissionDenied = false
            mutation.mutate(ClusterAction.CONNECT)
        },
    )

    SectionCard(padding = 16.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(
                tone = getConnectionStateTone(item.connection.state),
                size = 12.dp,
                pulse = isConnectionBusy(item),
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = item.domain,
                        modifier = Modifier.weight(1f, fill = false),
                        color = colors.strong,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.domain == selected) {
                        Label(text = "Selected", tone = LabelTone.SKY)
                    }
                }

                FlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Label(
                        text = getAuthenticationStateLabel(item.authentication.state),
                        tone = getAuthenticationStateTone(item.authentication.state),
                    )
                    Label(text = getConnectionStateLabel(item.connection.state), tone = LabelTone.SLATE)
                    if (item.settings.autoConnect) {
                        Label(text = "Auto connect", tone = LabelTone.NEUTRAL)
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isAuthenticated(item)) {
                if (canDisconnect(item)) {
                    OctButton(
                        text = "Disconnect",
                        onClick = { mutation.mutate(ClusterAction.DISCONNECT) },
                        variant = ButtonVariant.OUTLINE,
                        size = ButtonSize.XS,
                        icon = R.drawable.ic_plug,
                        isLoading = mutation.isPending && mutation.variables == ClusterAction.DISCONNECT,
                    )
                } else {
                    OctButton(
                        text = "Connect",
                        onClick = { connect(item.domain) },
                        size = ButtonSize.XS,
                        icon = R.drawable.ic_plug_zap,
                        enabled = canConnect(item),
                        isLoading = mutation.isPending && mutation.variables == ClusterAction.CONNECT,
                    )
                }

                OctButton(
                    text = "Sign out",
                    onClick = { confirm = ClusterAction.LOGOUT },
                    variant = ButtonVariant.OUTLINE,
                    size = ButtonSize.XS,
                    icon = R.drawable.ic_log_out,
                )
            } else {
                OctButton(
                    text = "Sign in",
                    onClick = { mutationAuth.mutate(Unit) },
                    size = ButtonSize.XS,
                    icon = R.drawable.ic_log_in,
                    enabled = !isConnectionBusy(item) && !mutation.isPending,
                    isLoading = mutationAuth.isPending,
                )
            }

            OctButton(
                text = "Open",
                onClick = {
                    vm.selectDomain(item.domain)
                    onNavigateToConnection()
                },
                variant = ButtonVariant.DEFAULT,
                size = ButtonSize.XS,
            )

            OctButton(
                text = "Remove",
                onClick = { confirm = ClusterAction.DELETE },
                variant = ButtonVariant.OUTLINE,
                size = ButtonSize.XS,
                icon = R.drawable.ic_trash_2,
                isDanger = true,
            )
        }

        val error = if (confirm == null) {
            mutation.error ?: mutationAuth.error
                ?: if (isPermissionDenied) "The VPN permission was not granted." else null
        } else {
            null
        }

        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.padding(top = 12.dp),
                color = AlertTone.RED.getColors(colors.isDark).content,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
    }

    ConfirmDialog(
        isOpen = confirm == ClusterAction.LOGOUT,
        title = "Sign out",
        text = "Signing out of ${item.domain} disconnects the Cluster, invalidates the Session and removes the stored credentials of this device.",
        confirmLabel = "Sign out",
        onConfirm = { mutation.mutate(ClusterAction.LOGOUT) },
        onDismiss = {
            confirm = null
            mutation.reset()
        },
        isPending = mutation.isPending,
        error = mutation.error,
    )

    ConfirmDialog(
        isOpen = confirm == ClusterAction.DELETE,
        title = "Remove the Cluster",
        text = "Removing ${item.domain} signs out and deletes both the credentials and the locally stored settings of the Cluster.",
        confirmLabel = "Remove",
        isDanger = true,
        onConfirm = { mutation.mutate(ClusterAction.DELETE) },
        onDismiss = {
            confirm = null
            mutation.reset()
        },
        isPending = mutation.isPending,
        error = mutation.error,
    )
}
