package com.octelium.client.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.octelium.client.R

val Ubuntu = FontFamily(
    Font(R.font.ubuntu_regular, FontWeight.Normal),
    Font(R.font.ubuntu_medium, FontWeight.Medium),
    Font(R.font.ubuntu_medium, FontWeight.SemiBold),
    Font(R.font.ubuntu_bold, FontWeight.Bold),
    Font(R.font.ubuntu_bold, FontWeight.ExtraBold),
)

object OcteliumTheme {
    val colors: OcteliumColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOcteliumColors.current
}

private fun getTypography(): Typography {
    val base = Typography()

    fun TextStyle.ubuntu() = copy(fontFamily = Ubuntu)

    return Typography(
        displayLarge = base.displayLarge.ubuntu(),
        displayMedium = base.displayMedium.ubuntu(),
        displaySmall = base.displaySmall.ubuntu(),
        headlineLarge = base.headlineLarge.ubuntu().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        headlineMedium = base.headlineMedium.ubuntu().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
        headlineSmall = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.02).em,
        ),
        titleLarge = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.01).em,
        ),
        titleMedium = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        titleSmall = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.04.em,
        ),
    )
}

private val typography = getTypography()

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun OcteliumTheme(isDark: Boolean, content: @Composable () -> Unit) {
    val colors = if (isDark) DarkColors else LightColors

    val scheme = if (isDark) {
        darkColorScheme(
            primary = colors.inverse,
            onPrimary = colors.inverseFg,
            primaryContainer = colors.surface3,
            onPrimaryContainer = colors.strong,
            secondary = colors.body,
            onSecondary = colors.inverseFg,
            secondaryContainer = colors.surface3,
            onSecondaryContainer = colors.strong,
            tertiary = colors.muted,
            background = colors.app,
            onBackground = colors.strong,
            surface = colors.surface,
            onSurface = colors.strong,
            surfaceVariant = colors.surface3,
            onSurfaceVariant = colors.muted,
            surfaceTint = colors.surface,
            surfaceBright = colors.surface2,
            surfaceDim = colors.app,
            surfaceContainerLowest = colors.app,
            surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface2,
            surfaceContainerHighest = colors.surface3,
            inverseSurface = colors.inverse,
            inverseOnSurface = colors.inverseFg,
            outline = colors.lineStrong,
            outlineVariant = colors.line,
            error = DangerColorDark,
            onError = colors.inverseFg,
            scrim = colors.app,
        )
    } else {
        lightColorScheme(
            primary = colors.inverse,
            onPrimary = colors.inverseFg,
            primaryContainer = colors.surface3,
            onPrimaryContainer = colors.strong,
            secondary = colors.body,
            onSecondary = colors.inverseFg,
            secondaryContainer = colors.surface3,
            onSecondaryContainer = colors.strong,
            tertiary = colors.muted,
            background = colors.app,
            onBackground = colors.strong,
            surface = colors.surface,
            onSurface = colors.strong,
            surfaceVariant = colors.surface3,
            onSurfaceVariant = colors.muted,
            surfaceTint = colors.surface,
            surfaceBright = colors.surface,
            surfaceDim = colors.surface3,
            surfaceContainerLowest = colors.surface,
            surfaceContainerLow = colors.surface2,
            surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface,
            surfaceContainerHighest = colors.surface3,
            inverseSurface = colors.inverse,
            inverseOnSurface = colors.inverseFg,
            outline = colors.lineStrong,
            outlineVariant = colors.line,
            error = DangerColor,
            onError = colors.inverseFg,
        )
    }

    CompositionLocalProvider(LocalOcteliumColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}
