package com.octelium.client.ui.connection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octelium.client.R
import com.octelium.client.core.domain.getDomainState
import com.octelium.client.core.domain.isConnectionBusy
import com.octelium.client.core.domain.normalizeDomain
import com.octelium.client.core.domain.validateDomain
import com.octelium.client.ui.MainViewModel
import com.octelium.client.ui.components.AlertBox
import com.octelium.client.ui.components.AlertText
import com.octelium.client.ui.components.ButtonSize
import com.octelium.client.ui.components.ButtonVariant
import com.octelium.client.ui.components.OctButton
import com.octelium.client.ui.components.OctTextField
import com.octelium.client.ui.components.TextLinkButton
import com.octelium.client.ui.components.rememberMutation
import com.octelium.client.ui.rememberOpenURL
import com.octelium.client.ui.theme.AlertTone
import com.octelium.client.ui.theme.OcteliumTheme
import octelium.api.client.daemon.v1.Daemonv1.Operation

enum class SignInMethod {
    BROWSER,
    TOKEN,
}

@Composable
fun ClusterSignIn(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    domain: String? = null,
    isCompact: Boolean = false,
    title: String? = null,
    description: String? = null,
    onSuccess: (String) -> Unit = {},
) {
    val colors = OcteliumTheme.colors
    val openURL = rememberOpenURL()
    val status by vm.status.collectAsStateWithLifecycle()
    val isBusy = isConnectionBusy(getDomainState(status, domain))

    var domainInput by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var isAdvanced by rememberSaveable { mutableStateOf(false) }

    val mutation = rememberMutation<SignInMethod>(
        onSuccess = {
            domainInput = ""
            token = ""
        },
    ) { method ->
        val target = normalizeDomain(domain ?: domainInput)
        validateDomain(target)?.let { throw IllegalArgumentException(it) }

        if (method == SignInMethod.TOKEN && token.isBlank()) {
            throw IllegalArgumentException("The authentication Token is required")
        }

        val op = if (method == SignInMethod.BROWSER) {
            vm.authenticateBrowser(target)
        } else {
            vm.authenticateToken(target, token.trim())
        }

        if (op.state == Operation.State.WAITING_FOR_USER && op.hasAction() && op.action.hasOpenURL()) {
            openURL(op.action.openURL.url)
        }

        onSuccess(op.domain.ifEmpty { target })
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isCompact) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (!isCompact) {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(128.dp)
                    .shadow(24.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_mark),
                    contentDescription = "Octelium",
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(80.dp),
                )
            }
        }

        Text(
            text = title ?: if (domain != null) "Sign in to $domain" else "Welcome to Octelium",
            modifier = Modifier.padding(top = if (isCompact) 0.dp else 28.dp),
            color = colors.strong,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (isCompact) 18.sp else 24.sp,
            textAlign = if (isCompact) TextAlign.Start else TextAlign.Center,
        )

        Text(
            text = description ?: if (domain != null) {
                "Continue in your browser to renew this Cluster Session."
            } else {
                "Enter your Cluster domain to securely sign in and connect this device."
            },
            modifier = Modifier
                .padding(top = 8.dp)
                .widthIn(max = 420.dp),
            color = colors.muted,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            textAlign = if (isCompact) TextAlign.Start else TextAlign.Center,
        )

        Column(
            modifier = Modifier
                .padding(top = if (isCompact) 16.dp else 28.dp)
                .fillMaxWidth()
                .shadow(if (colors.isDark) 0.dp else 8.dp, RoundedCornerShape(16.dp), clip = false)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (domain == null) {
                OctTextField(
                    value = domainInput,
                    onValueChange = {
                        domainInput = it
                        mutation.reset()
                    },
                    label = "Cluster domain",
                    placeholder = "example.com",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    onImeAction = { mutation.mutate(SignInMethod.BROWSER) },
                )
            }

            mutation.error?.let {
                AlertBox(tone = AlertTone.RED, title = "Could not start sign in") {
                    AlertText(text = it)
                }
            }

            OctButton(
                text = "Continue in browser",
                onClick = { mutation.mutate(SignInMethod.BROWSER) },
                size = ButtonSize.LG,
                icon = R.drawable.ic_log_in,
                fullWidth = true,
                isLoading = mutation.isPending && mutation.variables == SignInMethod.BROWSER,
                enabled = !mutation.isPending && !isBusy,
            )

            TextLinkButton(
                text = "Use an authentication Token",
                onClick = { isAdvanced = !isAdvanced },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                trailingIcon = R.drawable.ic_chevron_down,
                iconRotation = if (isAdvanced) 180f else 0f,
            )

            AnimatedVisibility(visible = isAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = colors.line)

                    OctTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = "Authentication Token",
                        placeholder = "Paste the Token",
                        isPassword = true,
                        imeAction = ImeAction.Done,
                    )

                    OctButton(
                        text = "Use Token",
                        onClick = { mutation.mutate(SignInMethod.TOKEN) },
                        variant = ButtonVariant.OUTLINE,
                        icon = R.drawable.ic_key_round,
                        fullWidth = true,
                        isLoading = mutation.isPending && mutation.variables == SignInMethod.TOKEN,
                        enabled = !mutation.isPending && !isBusy,
                    )
                }
            }
        }
    }
}
