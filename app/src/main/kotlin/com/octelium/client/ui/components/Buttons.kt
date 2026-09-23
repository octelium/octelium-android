package com.octelium.client.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octelium.client.ui.theme.AlertTone
import com.octelium.client.ui.theme.OcteliumTheme
import com.octelium.client.ui.theme.Ubuntu

enum class ButtonVariant {
    FILLED,
    OUTLINE,
    DEFAULT,
    SUBTLE,
}

enum class ButtonSize(
    val height: Dp,
    val fontSize: TextUnit,
    val paddingHorizontal: Dp,
    val iconSize: Dp,
) {
    XS(30.dp, 12.sp, 12.dp, 14.dp),
    SM(36.dp, 14.sp, 16.dp, 16.dp),
    MD(42.dp, 16.sp, 20.dp, 17.dp),
    LG(50.dp, 17.sp, 24.dp, 18.dp),
}

val ButtonShape = RoundedCornerShape(8.dp)

@Composable
fun OctButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.FILLED,
    size: ButtonSize = ButtonSize.SM,
    @DrawableRes icon: Int? = null,
    isDanger: Boolean = false,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val colors = OcteliumTheme.colors
    val danger = AlertTone.RED.getColors(colors.isDark)
    val dangerColor = if (colors.isDark) Color(0xFFFF6B6B) else Color(0xFFFA5252)

    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val isEnabled = enabled && !isLoading

    val (background, content, border) = when {
        !enabled -> Triple(colors.surface3, colors.faint, Color.Transparent)
        variant == ButtonVariant.FILLED && isDanger -> Triple(dangerColor, Color.White, Color.Transparent)
        variant == ButtonVariant.FILLED -> Triple(
            if (isPressed) colors.inverseHover else colors.inverse,
            colors.inverseFg,
            Color.Transparent,
        )

        variant == ButtonVariant.OUTLINE && isDanger -> Triple(
            if (isPressed) danger.background else Color.Transparent,
            dangerColor,
            dangerColor,
        )

        variant == ButtonVariant.OUTLINE -> Triple(
            if (isPressed) colors.surface3 else Color.Transparent,
            colors.inverse,
            colors.inverse,
        )

        variant == ButtonVariant.DEFAULT -> Triple(
            if (isPressed) colors.surface3 else colors.surface,
            colors.strong,
            colors.lineStrong,
        )

        else -> Triple(
            if (isPressed) colors.surface3 else Color.Transparent,
            if (isDanger) dangerColor else colors.muted,
            Color.Transparent,
        )
    }

    var m = modifier
    if (fullWidth) {
        m = m.fillMaxWidth()
    }

    if (variant == ButtonVariant.FILLED && enabled) {
        m = m.shadow(if (isPressed) 1.dp else 3.dp, ButtonShape, clip = false)
    }

    Box(
        modifier = m
            .offset { IntOffset(0, if (isPressed && enabled) 1.dp.roundToPx() else 0) }
            .height(size.height)
            .clip(ButtonShape)
            .background(background)
            .border(1.dp, border, ButtonShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = isEnabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = size.paddingHorizontal),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.alpha(if (isLoading) 0f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(size.iconSize),
                )
            }

            Text(
                text = text,
                color = content,
                fontFamily = Ubuntu,
                fontWeight = FontWeight.Bold,
                fontSize = size.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(size.iconSize),
                color = content,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
fun OctIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.SUBTLE,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val colors = OcteliumTheme.colors

    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()

    val border = if (variant == ButtonVariant.DEFAULT) colors.lineStrong else Color.Transparent
    val background = when {
        isPressed -> colors.surface3
        variant == ButtonVariant.DEFAULT -> colors.surface
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(ButtonShape)
            .background(background)
            .border(1.dp, border, ButtonShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint ?: if (variant == ButtonVariant.DEFAULT) colors.strong else colors.faint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun TextLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes trailingIcon: Int? = null,
    iconRotation: Float = 0f,
) {
    val colors = OcteliumTheme.colors

    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val color = if (isPressed) colors.strong else colors.muted

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, fontFamily = Ubuntu)

        if (trailingIcon != null) {
            Icon(
                painter = painterResource(trailingIcon),
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(15.dp)
                    .rotate(iconRotation),
            )
        }
    }
}
