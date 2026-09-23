package com.octelium.client.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octelium.client.R
import com.octelium.client.auth.openURL
import com.octelium.client.core.domain.getActiveOperation
import com.octelium.client.core.domain.getPendingOpenURL
import com.octelium.client.core.local.getErrorMessage
import com.octelium.client.ui.components.OperationBanner
import com.octelium.client.ui.components.rememberMutation
import com.octelium.client.ui.theme.OcteliumTheme
import octelium.api.client.daemon.v1.Daemonv1.DomainState

@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        content()
        Footer()
    }
}

@Composable
fun Footer(modifier: Modifier = Modifier) {
    val colors = OcteliumTheme.colors
    val openURL = rememberOpenURL()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Octelium is Free and Open Source Software",
            color = colors.body,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )

        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable { openURL("https://github.com/octelium/octelium") }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "github.com/octelium/octelium",
                color = colors.muted,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
fun rememberOpenURL(): (String) -> Unit {
    val context = LocalContext.current
    val toolbarColor = OcteliumTheme.colors.app.toArgb()

    return remember(context, toolbarColor) {
        { url ->
            try {
                openURL(context, url, toolbarColor)
            } catch (err: Exception) {
                Toast.makeText(context, getErrorMessage(err), Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
fun rememberConnectLauncher(
    vm: MainViewModel,
    onDenied: () -> Unit,
    onGranted: (String) -> Unit,
): (String) -> Unit {
    val context = LocalContext.current
    val hasRequestedNotifications by vm.hasRequestedNotifications.collectAsStateWithLifecycle()
    var pendingDomain by rememberSaveable { mutableStateOf<String?>(null) }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val domain = pendingDomain
        pendingDomain = null

        if (result.resultCode == Activity.RESULT_OK && domain != null) {
            onGranted(domain)
        } else {
            onDenied()
        }
    }

    val requestVPN: (String) -> Unit = { domain ->
        val intent = VpnService.prepare(context)
        if (intent == null) {
            onGranted(domain)
        } else {
            pendingDomain = domain
            vpnLauncher.launch(intent)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingDomain?.let(requestVPN)
    }

    return { domain ->
        val shouldRequestNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            hasRequestedNotifications == false &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

        if (shouldRequestNotifications) {
            vm.setHasRequestedNotifications()
            pendingDomain = domain
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVPN(domain)
        }
    }
}

@Composable
fun DomainOperationBanner(vm: MainViewModel, state: DomainState?) {
    val openURL = rememberOpenURL()
    val mutationCancel = rememberMutation<String> { vm.cancelOperation(it) }

    OperationBanner(
        operation = getActiveOperation(state),
        pendingURL = getPendingOpenURL(state),
        onOpenURL = openURL,
        onCancel = { mutationCancel.mutate(it) },
        isCanceling = mutationCancel.isPending,
        error = mutationCancel.error,
    )
}
