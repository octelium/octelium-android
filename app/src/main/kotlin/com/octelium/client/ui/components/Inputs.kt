package com.octelium.client.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octelium.client.R
import com.octelium.client.ui.theme.OcteliumTheme
import com.octelium.client.ui.theme.Ubuntu

private val InputShape = RoundedCornerShape(8.dp)

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = 6.dp),
        color = OcteliumTheme.colors.strong,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    )
}

@Composable
fun FieldDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = 6.dp),
        color = OcteliumTheme.colors.muted,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
    )
}

@Composable
fun OctTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    @DrawableRes leadingIcon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    enabled: Boolean = true,
    error: String? = null,
) {
    val colors = OcteliumTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    var isRevealed by remember { mutableStateOf(false) }

    val borderColor = when {
        error != null -> Color(0xFFFA5252)
        isFocused -> colors.inverse
        else -> colors.lineStrong
    }

    Column(modifier = modifier) {
        if (label != null) {
            FieldLabel(text = label)
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(
                color = if (enabled) colors.strong else colors.faint,
                fontFamily = Ubuntu,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
            ),
            cursorBrush = SolidColor(colors.strong),
            visualTransformation = if (isPassword && !isRevealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .then(if (isFocused) Modifier.shadow(3.dp, InputShape, clip = false) else Modifier)
                        .clip(InputShape)
                        .background(if (enabled) colors.surface else colors.surface3)
                        .border(2.dp, borderColor, InputShape)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            painter = painterResource(leadingIcon),
                            contentDescription = null,
                            tint = colors.faint,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    Box(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                color = colors.faint,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }

                    if (isPassword) {
                        OctIconButton(
                            icon = if (isRevealed) R.drawable.ic_eye_off else R.drawable.ic_eye,
                            contentDescription = if (isRevealed) "Hide" else "Show",
                            onClick = { isRevealed = !isRevealed },
                            size = 28.dp,
                            iconSize = 16.dp,
                        )
                    }

                    trailing?.invoke()
                }
            },
        )

        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.padding(top = 4.dp),
                color = Color(0xFFFA5252),
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search…",
) {
    OctTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = R.drawable.ic_search,
        imeAction = ImeAction.Search,
        trailing = if (value.isNotEmpty()) {
            {
                OctIconButton(
                    icon = R.drawable.ic_x,
                    contentDescription = "Clear search",
                    onClick = { onValueChange("") },
                    size = 26.dp,
                    iconSize = 15.dp,
                )
            }
        } else {
            null
        },
    )
}

data class SelectOption<T>(
    val value: T,
    val label: String,
)

@Composable
fun <T> SelectField(
    options: List<SelectOption<T>>,
    value: T?,
    onValueChange: (T?) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "Select",
    isClearable: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = OcteliumTheme.colors
    var isExpanded by remember { mutableStateOf(false) }
    val selected = options.find { it.value == value }

    Column(modifier = modifier) {
        if (label != null) {
            FieldLabel(text = label)
        }

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(InputShape)
                    .background(if (enabled) colors.surface else colors.surface3)
                    .border(2.dp, if (isExpanded) colors.inverse else colors.lineStrong, InputShape)
                    .clickable(enabled = enabled, role = Role.DropdownList) { isExpanded = true }
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected?.label ?: placeholder,
                    modifier = Modifier.weight(1f),
                    color = if (selected == null) colors.faint else colors.strong,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (isClearable && selected != null) {
                    OctIconButton(
                        icon = R.drawable.ic_x,
                        contentDescription = "Clear",
                        onClick = { onValueChange(null) },
                        size = 28.dp,
                        iconSize = 15.dp,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = colors.faint,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(16.dp)
                            .rotate(if (isExpanded) 180f else 0f),
                    )
                }
            }

            DropdownMenu(
                expanded = isExpanded,
                onDismissRequest = { isExpanded = false },
                shape = InputShape,
                containerColor = colors.surface,
                border = BorderStroke(1.dp, colors.line),
            ) {
                for (itm in options) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = itm.label,
                                color = colors.strong,
                                fontWeight = if (itm.value == value) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                        },
                        trailingIcon = if (itm.value == value) {
                            {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = colors.strong,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            isExpanded = false
                            onValueChange(itm.value)
                        },
                    )
                }
            }
        }
    }
}

data class Segment<T>(
    val value: T,
    val label: String,
    val enabled: Boolean = true,
)

@Composable
fun <T> SegmentedControl(
    segments: List<Segment<T>>,
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OcteliumTheme.colors
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface3)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (itm in segments) {
            val isSelected = itm.value == value
            val itemShape = RoundedCornerShape(6.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(if (isSelected) Modifier.shadow(1.dp, itemShape, clip = false) else Modifier)
                    .clip(itemShape)
                    .background(if (isSelected) colors.surface else Color.Transparent)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        enabled = itm.enabled && !isSelected,
                        role = Role.Tab,
                    ) { onValueChange(itm.value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = itm.label,
                    color = when {
                        !itm.enabled -> colors.faint
                        isSelected -> colors.strong
                        else -> colors.muted
                    },
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun OctSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = OcteliumTheme.colors

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.inverseFg,
            checkedTrackColor = colors.inverse,
            checkedBorderColor = colors.inverse,
            uncheckedThumbColor = colors.faint,
            uncheckedTrackColor = colors.surface3,
            uncheckedBorderColor = colors.lineStrong,
        ),
    )
}
