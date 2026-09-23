package com.octelium.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octelium.client.R
import com.octelium.client.core.domain.getErrorHint
import com.octelium.client.core.domain.getErrorTitle
import com.octelium.client.core.domain.getOperationTypeLabel
import com.octelium.client.core.domain.isErrorRetryable
import com.octelium.client.ui.theme.AlertTone
import com.octelium.client.ui.theme.OcteliumTheme
import octelium.api.client.daemon.v1.Daemonv1

@Composable
fun ErrorBanner(
    error: Daemonv1.Error?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    isPending: Boolean = false,
) {
    if (error == null) {
        return
    }

    val colors = OcteliumTheme.colors
    val hint = getErrorHint(error)

    AlertBox(
        tone = AlertTone.RED,
        modifier = modifier.padding(bottom = 20.dp),
        title = getErrorTitle(error),
        icon = R.drawable.ic_triangle_alert,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hint != null) {
                AlertText(text = hint)
            }

            if (error.message.isNotEmpty()) {
                Text(
                    text = error.message,
                    color = colors.body.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }

            if (onRetry != null && isErrorRetryable(error)) {
                OctButton(
                    text = "Try again",
                    onClick = onRetry,
                    variant = ButtonVariant.OUTLINE,
                    size = ButtonSize.XS,
                    icon = R.drawable.ic_refresh_cw,
                    isDanger = true,
                    isLoading = isPending,
                )
            }
        }
    }
}

@Composable
fun OperationBanner(
    operation: Daemonv1.Operation?,
    pendingURL: String?,
    onOpenURL: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
    isCanceling: Boolean = false,
    error: String? = null,
) {
    if (operation == null) {
        return
    }

    val tone = AlertTone.BLUE.getColors(OcteliumTheme.colors.isDark)

    AlertBox(
        tone = AlertTone.BLUE,
        modifier = modifier.padding(bottom = 20.dp),
        title = "${getOperationTypeLabel(operation.type)} ${operation.domain}",
        iconContent = {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = tone.content, strokeWidth = 2.dp)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pendingURL != null) {
                AlertText(
                    text = "Finish signing in using your web browser. The browser opens the Cluster Portal at your identity provider.",
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pendingURL != null) {
                    OctButton(
                        text = "Open the browser again",
                        onClick = { onOpenURL(pendingURL) },
                        size = ButtonSize.XS,
                        icon = R.drawable.ic_external_link,
                    )
                }

                if (operation.cancellable) {
                    OctButton(
                        text = "Cancel",
                        onClick = { onCancel(operation.id) },
                        variant = ButtonVariant.OUTLINE,
                        size = ButtonSize.XS,
                        isLoading = isCanceling,
                    )
                }
            }

            if (error != null) {
                Text(text = error, color = AlertTone.RED.getColors(OcteliumTheme.colors.isDark).content, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    isOpen: Boolean,
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Confirm",
    isDanger: Boolean = false,
    isPending: Boolean = false,
    error: String? = null,
) {
    if (!isOpen) {
        return
    }

    val colors = OcteliumTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = CardShape,
        title = {
            Text(text = title, color = colors.strong, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column {
                Text(text = text, color = colors.body, fontWeight = FontWeight.Medium, fontSize = 14.sp)

                if (error != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    AlertBox(tone = AlertTone.RED, title = "The operation failed") {
                        AlertText(text = error)
                    }
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OctButton(text = "Cancel", onClick = onDismiss, variant = ButtonVariant.OUTLINE)
                OctButton(text = confirmLabel, onClick = onConfirm, isDanger = isDanger, isLoading = isPending)
            }
        },
    )
}
