package com.octelium.client.ui.diagnostics

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octelium.client.BuildConfig
import com.octelium.client.R
import com.octelium.client.core.auth.AUTH_CALLBACK_URL
import com.octelium.client.core.cluster.getClusterAPIHost
import com.octelium.client.core.domain.LabelTone
import com.octelium.client.core.domain.getAuthenticationStateLabel
import com.octelium.client.core.domain.getConnectionStateLabel
import com.octelium.client.core.domain.toRFC3339
import com.octelium.client.core.local.LogEntry
import com.octelium.client.core.local.LogLevel
import com.octelium.client.core.local.formatLog
import com.octelium.client.core.network.HostCheck
import com.octelium.client.core.network.HostResolution
import com.octelium.client.core.network.getHostCheckError
import com.octelium.client.core.network.getHostResolutionLabel
import com.octelium.client.core.network.getNetworkLabel
import com.octelium.client.lib.formatABIVersion
import com.octelium.client.runtime.RuntimeState
import com.octelium.client.ui.MainViewModel
import com.octelium.client.ui.ScreenColumn
import com.octelium.client.ui.components.ButtonSize
import com.octelium.client.ui.components.ButtonVariant
import com.octelium.client.ui.components.CopyText
import com.octelium.client.ui.components.InfoText
import com.octelium.client.ui.components.Label
import com.octelium.client.ui.components.Mono
import com.octelium.client.ui.components.OctButton
import com.octelium.client.ui.components.PageHeader
import com.octelium.client.ui.components.SectionCard
import com.octelium.client.ui.components.SectionTitle
import com.octelium.client.ui.components.copyToClipboard
import com.octelium.client.ui.components.rememberMutation
import com.octelium.client.ui.connection.InfoGrid
import com.octelium.client.ui.settings.getLibVersion
import com.octelium.client.ui.theme.OcteliumTheme

@Composable
fun DiagnosticsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val runtime by vm.runtimeState.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val networkInfo by vm.networkInfo.collectAsStateWithLifecycle()
    val info = (runtime as? RuntimeState.Ready)?.info

    ScreenColumn {
        PageHeader(
            title = "Diagnostics",
            description = "Runtime information for troubleshooting the application and liboctelium.",
        )

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard {
                Row(
                    modifier = Modifier.padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionTitle(text = "liboctelium")
                    Label(
                        text = if (info != null) "Available" else "Unavailable",
                        tone = if (info != null) LabelTone.EMERALD else LabelTone.ROSE,
                    )
                }

                InfoGrid {
                    item("Version") { InfoText(getLibVersion(info?.version)) }
                    item("C ABI") { InfoText(info?.let { "v${formatABIVersion(it.abiVersion)}" } ?: "—") }
                    item("State revision") { InfoText(status?.revision?.toString() ?: "—") }
                    item("Instance") { Mono(text = info?.instanceID?.take(12) ?: "—") }
                    item("Commit") { Mono(text = BuildConfig.LIBOCTELIUM_COMMIT.take(12).ifEmpty { "—" }) }
                }
            }

            SectionCard {
                SectionTitle(text = "Application", modifier = Modifier.padding(bottom = 16.dp))

                InfoGrid {
                    item("Version") { InfoText(BuildConfig.RELEASE_TAG.ifEmpty { "Development build" }) }
                    item("Build") { InfoText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }
                    item("Android") { InfoText("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})") }
                    item("Architecture") { InfoText(Build.SUPPORTED_ABIS.firstOrNull() ?: "—") }
                    item("Device") { InfoText("${Build.MANUFACTURER} ${Build.MODEL}") }
                    item("Network") { InfoText(getNetworkLabel(networkInfo)) }
                }
            }

            for (itm in status?.domainsList.orEmpty()) {
                SectionCard {
                    FlowRow(
                        modifier = Modifier.padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SectionTitle(text = itm.domain)
                        Label(text = getConnectionStateLabel(itm.connection.state), tone = LabelTone.SLATE)
                        Label(text = getAuthenticationStateLabel(itm.authentication.state), tone = LabelTone.NEUTRAL)
                    }

                    InfoGrid(columns = 1) {
                        item("Authenticated at") { Mono(text = toRFC3339(itm.authentication.authenticatedAt) ?: "—") }
                        item("Credentials expire at") { Mono(text = toRFC3339(itm.authentication.expiresAt) ?: "—") }
                        item("Connected at") { Mono(text = toRFC3339(itm.connection.connectedAt) ?: "—") }
                        item("Last error") { InfoText(itm.lastError.message.ifEmpty { "—" }) }
                        item("Cluster API") { ClusterAPICheck(vm, itm.domain) }
                    }
                }
            }

            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle(text = "Logs", modifier = Modifier.weight(1f))
                    OctButton(
                        text = "Copy",
                        onClick = { copyToClipboard(context, logs.joinToString("\n") { formatLog(it) }) },
                        variant = ButtonVariant.DEFAULT,
                        size = ButtonSize.XS,
                        icon = R.drawable.ic_copy,
                        enabled = logs.isNotEmpty(),
                    )
                }

                if (logs.isEmpty()) {
                    InfoText(text = "No logs yet")
                } else {
                    LogList(logs.takeLast(MAX_VISIBLE_LOGS))
                }
            }

            SectionCard {
                SectionTitle(text = "Authentication callback", modifier = Modifier.padding(bottom = 8.dp))
                CopyText(value = AUTH_CALLBACK_URL)
            }
        }
    }
}

private const val MAX_VISIBLE_LOGS = 200

@Composable
private fun ClusterAPICheck(vm: MainViewModel, domain: String) {
    var result by remember(domain) { mutableStateOf<HostCheck?>(null) }
    val mutation = rememberMutation<String> { result = vm.checkClusterAPIHost(it) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono(text = getClusterAPIHost(domain), modifier = Modifier.weight(1f))
            OctButton(
                text = "Check DNS",
                onClick = { mutation.mutate(domain) },
                variant = ButtonVariant.DEFAULT,
                size = ButtonSize.XS,
                isLoading = mutation.isPending,
                enabled = !mutation.isPending,
            )
        }

        result?.let {
            Label(
                text = getHostResolutionLabel(it.resolution),
                tone = if (it.resolution == HostResolution.RESOLVED) LabelTone.EMERALD else LabelTone.ROSE,
            )

            if (it.resolution == HostResolution.RESOLVED) {
                Mono(text = it.addresses.joinToString(", "))
            } else {
                getHostCheckError(it)?.let { msg -> InfoText(text = msg) }
            }
        }

        mutation.error?.let { InfoText(text = it) }
    }
}

@Composable
private fun LogList(logs: List<LogEntry>) {
    val colors = OcteliumTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface2)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (itm in logs.asReversed()) {
            Text(
                text = formatLog(itm),
                color = when (itm.level) {
                    LogLevel.ERROR -> Color(0xFFF43F5E)
                    LogLevel.WARN -> Color(0xFFF59E0B)
                    LogLevel.DEBUG -> colors.faint
                    LogLevel.INFO -> colors.body
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
