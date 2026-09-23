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
import com.octelium.client.core.domain.LabelTone
import com.octelium.client.core.domain.getAuthenticationStateLabel
import com.octelium.client.core.domain.getConnectionStateLabel
import com.octelium.client.core.domain.toRFC3339
import com.octelium.client.core.local.formatLog
import com.octelium.client.core.network.getNetworkLabel
import com.octelium.client.lib.ABI_VERSION
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
import com.octelium.client.ui.connection.InfoGrid
import com.octelium.client.ui.settings.getLibVersion
import com.octelium.client.ui.theme.OcteliumTheme
import octelium.api.client.mobile.v1.Mobilev1

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
                    item("Local API") {
                        InfoText(info?.let { "v${it.apiMajorVersion}.${it.apiMinorVersion}" } ?: "—")
                    }
                    item("C ABI") { InfoText("v$ABI_VERSION") }
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

            info?.let {
                SectionCard {
                    SectionTitle(text = "Authentication callback", modifier = Modifier.padding(bottom = 8.dp))
                    CopyText(value = it.authenticationCallbackURL)
                }
            }
        }
    }
}

private const val MAX_VISIBLE_LOGS = 200

@Composable
private fun LogList(logs: List<Mobilev1.Log>) {
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
                    Mobilev1.Log.Level.ERROR -> Color(0xFFF43F5E)
                    Mobilev1.Log.Level.WARN -> Color(0xFFF59E0B)
                    Mobilev1.Log.Level.DEBUG -> colors.faint
                    else -> colors.body
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
