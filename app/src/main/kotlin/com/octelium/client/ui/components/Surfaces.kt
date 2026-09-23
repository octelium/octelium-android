package com.octelium.client.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.octelium.client.R
import com.octelium.client.core.domain.ConnectivityTone
import com.octelium.client.core.domain.LabelTone
import com.octelium.client.ui.theme.AlertTone
import com.octelium.client.ui.theme.OcteliumTheme
import com.octelium.client.ui.theme.SuccessColor
import com.octelium.client.ui.theme.ToneColors
import com.octelium.client.ui.theme.getConnectivityToneColor
import com.octelium.client.ui.theme.getLabelToneColors
import kotlinx.coroutines.delay

val CardShape = RoundedCornerShape(12.dp)

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = OcteliumTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (colors.isDark) 0.dp else 1.dp, CardShape, clip = false)
            .clip(CardShape)
            .background(colors.surface)
            .border(1.dp, colors.line, CardShape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = OcteliumTheme.colors.strong,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        letterSpacing = (-0.01).em,
    )
}

@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = OcteliumTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = colors.strong,
            style = MaterialTheme.typography.headlineSmall,
        )

        if (description != null) {
            Text(
                text = description,
                modifier = Modifier.padding(top = 4.dp),
                color = colors.muted,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
        }

        if (actions != null) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 18.dp, bottom = 20.dp), color = colors.line)
    }
}

@Composable
fun InfoItem(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = OcteliumTheme.colors

    Column(modifier = modifier) {
        Text(
            text = title.uppercase(),
            color = colors.faint,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.05.em,
        )
        Box(modifier = Modifier.padding(top = 2.dp)) {
            content()
        }
    }
}

@Composable
fun InfoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = OcteliumTheme.colors.body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    )
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = text,
        modifier = modifier,
        color = OcteliumTheme.colors.body,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    tone: LabelTone = LabelTone.NEUTRAL,
    toneColors: ToneColors? = null,
    @DrawableRes icon: Int? = null,
    prefix: String? = null,
    isMono: Boolean = false,
) {
    val colors = OcteliumTheme.colors
    val tc = toneColors ?: getLabelToneColors(tone, colors.isDark)
    val shape = RoundedCornerShape(6.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(tc.background)
            .border(1.dp, tc.border, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tc.content,
                modifier = Modifier
                    .size(12.dp)
                    .alpha(0.7f),
            )
        }

        if (prefix != null) {
            Text(text = prefix, color = colors.faint, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
        }

        Text(
            text = text,
            color = tc.content,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = if (isMono) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatusDot(
    tone: ConnectivityTone,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    pulse: Boolean = false,
) {
    val color = getConnectivityToneColor(tone, OcteliumTheme.colors.isDark)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (pulse) {
            val transition = rememberInfiniteTransition(label = "ping")
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Restart),
                label = "ping",
            )

            Box(
                modifier = Modifier
                    .size(size)
                    .scale(1f + progress)
                    .alpha(0.6f * (1f - progress))
                    .clip(CircleShape)
                    .background(color),
            )
        }

        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
fun Notice(
    modifier: Modifier = Modifier,
    title: String? = null,
    @DrawableRes icon: Int? = R.drawable.ic_info,
    content: String,
) {
    val colors = OcteliumTheme.colors
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = colors.faint,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(16.dp),
            )
        }

        Column {
            if (title != null) {
                Text(text = title, color = colors.strong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(
                text = content,
                modifier = Modifier.padding(top = 2.dp),
                color = colors.muted,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
fun AlertBox(
    tone: AlertTone,
    modifier: Modifier = Modifier,
    title: String? = null,
    @DrawableRes icon: Int? = null,
    iconContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = OcteliumTheme.colors
    val tc = tone.getColors(colors.isDark)
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tc.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (iconContent != null) {
            Box(modifier = Modifier.padding(top = 2.dp).size(18.dp), contentAlignment = Alignment.Center) {
                iconContent()
            }
        } else if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tc.content,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(text = title, color = tc.content, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
            }
            content()
        }
    }
}

@Composable
fun AlertText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = OcteliumTheme.colors.body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    )
}

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    isLast: Boolean = false,
    isStacked: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = OcteliumTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        if (isStacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column {
                    Text(text = title, color = colors.strong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (description != null) {
                        Text(
                            text = description,
                            modifier = Modifier.padding(top = 2.dp),
                            color = colors.muted,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }
                }

                if (trailing != null) {
                    trailing()
                }
            }

            if (!isLast) {
                HorizontalDivider(color = colors.line)
            }

            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = colors.strong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (description != null) {
                    Text(
                        text = description,
                        modifier = Modifier.padding(top = 2.dp),
                        color = colors.muted,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                }
            }

            if (trailing != null) {
                trailing()
            }
        }

        if (!isLast) {
            HorizontalDivider(color = colors.line)
        }
    }
}

@Composable
fun CopyText(
    value: String,
    modifier: Modifier = Modifier,
    isMono: Boolean = true,
    hideText: Boolean = false,
) {
    val colors = OcteliumTheme.colors
    val context = LocalContext.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(1200)
            isCopied = false
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!hideText) {
            if (isMono) {
                Mono(text = value, modifier = Modifier.weight(1f, fill = false))
            } else {
                InfoText(text = value, modifier = Modifier.weight(1f, fill = false))
            }
        }

        OctIconButton(
            icon = if (isCopied) R.drawable.ic_check_check else R.drawable.ic_copy,
            contentDescription = if (isCopied) "Copied" else "Copy to clipboard",
            onClick = {
                copyToClipboard(context, value)
                isCopied = true
            },
            size = 28.dp,
            iconSize = 15.dp,
            tint = if (isCopied) SuccessColor else colors.body,
        )
    }
}

fun copyToClipboard(context: Context, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Octelium", value))
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    @DrawableRes icon: Int = R.drawable.ic_inbox,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = OcteliumTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp)
            .clip(CardShape)
            .background(colors.surface.copy(alpha = 0.7f))
            .drawBehind {
                drawRoundRect(
                    color = colors.lineStrong,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.surface3)
                .border(1.dp, colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = colors.faint, modifier = Modifier.size(22.dp))
        }

        Text(
            text = title,
            modifier = Modifier.padding(top = 16.dp),
            color = colors.strong,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )

        if (message != null) {
            Text(
                text = message,
                modifier = Modifier.padding(top = 4.dp),
                color = colors.muted,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        if (action != null) {
            Box(modifier = Modifier.padding(top = 20.dp)) {
                action()
            }
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    AlertBox(tone = AlertTone.RED, modifier = modifier, title = title, icon = R.drawable.ic_circle_alert) {
        AlertText(text = message)
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(10.dp))
            OctButton(
                text = "Try again",
                onClick = onRetry,
                variant = ButtonVariant.OUTLINE,
                size = ButtonSize.XS,
                icon = R.drawable.ic_refresh_cw,
                isDanger = true,
            )
        }
    }
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(6.dp)) {
    val colors = OcteliumTheme.colors
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeleton",
    )

    Box(
        modifier = modifier
            .alpha(alpha)
            .clip(shape)
            .background(colors.surface3),
    )
}

@Composable
fun ResourceListSkeleton(count: Int = 5) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) {
            SectionCard(padding = 16.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonBox(modifier = Modifier.size(44.dp), shape = RoundedCornerShape(22.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkeletonBox(modifier = Modifier.fillMaxWidth(0.4f).height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SkeletonBox(modifier = Modifier.width(72.dp).height(18.dp))
                            SkeletonBox(modifier = Modifier.width(96.dp).height(18.dp))
                            SkeletonBox(modifier = Modifier.width(56.dp).height(18.dp))
                        }
                    }
                }
            }
        }
    }
}
