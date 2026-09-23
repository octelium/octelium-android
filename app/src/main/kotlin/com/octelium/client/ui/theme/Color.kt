package com.octelium.client.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.octelium.client.core.domain.ConnectivityTone
import com.octelium.client.core.domain.LabelTone

@Immutable
data class OcteliumColors(
    val app: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val surfaceActive: Color,
    val lineStrong: Color,
    val strong: Color,
    val body: Color,
    val muted: Color,
    val faint: Color,
    val inverse: Color,
    val inverseFg: Color,
    val inverseHover: Color,
    val isDark: Boolean,
)

val LightColors = OcteliumColors(
    app = Color(0xFFF1F5F9),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF8FAFC),
    surface3 = Color(0xFFF1F5F9),
    line = Color(0xFFE2E8F0),
    surfaceActive = Color(0xFFE2E8F0),
    lineStrong = Color(0xFFCBD5E1),
    strong = Color(0xFF0F172A),
    body = Color(0xFF334155),
    muted = Color(0xFF64748B),
    faint = Color(0xFF94A3B8),
    inverse = Color(0xFF18181B),
    inverseFg = Color(0xFFFFFFFF),
    inverseHover = Color(0xFF000000),
    isDark = false,
)

val DarkColors = OcteliumColors(
    app = Color(0xFF0C0C0E),
    surface = Color(0xFF171719),
    surface2 = Color(0xFF1D1D1F),
    surface3 = Color(0xFF242426),
    line = Color(0xFF2C2C2F),
    surfaceActive = Color(0xFF2D2D30),
    lineStrong = Color(0xFF424245),
    strong = Color(0xFFECECEF),
    body = Color(0xFFCDCDD1),
    muted = Color(0xFFA5A5AA),
    faint = Color(0xFF7A7A7F),
    inverse = Color(0xFFECECEF),
    inverseFg = Color(0xFF161618),
    inverseHover = Color(0xFFF8F8FA),
    isDark = true,
)

val LocalOcteliumColors = staticCompositionLocalOf { LightColors }

@Immutable
data class ToneColors(
    val background: Color,
    val content: Color,
    val border: Color,
)

enum class Palette(
    private val light50: Long,
    private val light700: Long,
    private val light200: Long,
    private val base500: Long,
    private val dark300: Long,
) {
    SLATE(0xFFF1F5F9, 0xFF334155, 0xFFE2E8F0, 0xFF1E293B, 0xFFCBD5E1),
    EMERALD(0xFFECFDF5, 0xFF047857, 0xFFA7F3D0, 0xFF10B981, 0xFF6EE7B7),
    SKY(0xFFF0F9FF, 0xFF0369A1, 0xFFBAE6FD, 0xFF0EA5E9, 0xFF7DD3FC),
    AMBER(0xFFFFFBEB, 0xFFB45309, 0xFFFDE68A, 0xFFF59E0B, 0xFFFCD34D),
    ROSE(0xFFFFF1F2, 0xFFBE123C, 0xFFFECDD3, 0xFFF43F5E, 0xFFFDA4AF),
    BLUE(0xFFEFF6FF, 0xFF1D4ED8, 0xFFBFDBFE, 0xFF3B82F6, 0xFF93C5FD),
    VIOLET(0xFFF5F3FF, 0xFF6D28D9, 0xFFDDD6FE, 0xFF8B5CF6, 0xFFC4B5FD),
    INDIGO(0xFFEEF2FF, 0xFF4338CA, 0xFFC7D2FE, 0xFF6366F1, 0xFFA5B4FC),
    CYAN(0xFFECFEFF, 0xFF0E7490, 0xFFA5F3FC, 0xFF06B6D4, 0xFF67E8F9),
    TEAL(0xFFF0FDFA, 0xFF0F766E, 0xFF99F6E4, 0xFF14B8A6, 0xFF5EEAD4),
    GREEN(0xFFF0FDF4, 0xFF15803D, 0xFFBBF7D0, 0xFF22C55E, 0xFF86EFAC),
    FUCHSIA(0xFFFDF4FF, 0xFFA21CAF, 0xFFF5D0FE, 0xFFD946EF, 0xFFF0ABFC),
    PURPLE(0xFFFAF5FF, 0xFF7E22CE, 0xFFE9D5FF, 0xFFA855F7, 0xFFD8B4FE),
    ORANGE(0xFFFFF7ED, 0xFFC2410C, 0xFFFED7AA, 0xFFF97316, 0xFFFDBA74),
    ;

    fun getColors(isDark: Boolean): ToneColors {
        if (!isDark) {
            return ToneColors(Color(light50), Color(light700), Color(light200))
        }

        if (this == SLATE) {
            return ToneColors(Color(0xFF1E293B), Color(0xFFCBD5E1), Color(0xFF334155))
        }

        return ToneColors(
            background = Color(base500).copy(alpha = 0.1f),
            content = Color(dark300),
            border = Color(base500).copy(alpha = 0.3f),
        )
    }
}

fun getLabelToneColors(tone: LabelTone, isDark: Boolean): ToneColors = when (tone) {
    LabelTone.NEUTRAL -> if (isDark) {
        ToneColors(Color(0xFF1E293B), Color(0xFFE2E8F0), Color(0xFF334155))
    } else {
        ToneColors(Color(0xFFF8FAFC), Color(0xFF334155), Color(0xFFE2E8F0))
    }

    LabelTone.SLATE -> Palette.SLATE.getColors(isDark)
    LabelTone.EMERALD -> Palette.EMERALD.getColors(isDark)
    LabelTone.SKY -> Palette.SKY.getColors(isDark)
    LabelTone.AMBER -> Palette.AMBER.getColors(isDark)
    LabelTone.ROSE -> Palette.ROSE.getColors(isDark)
}

fun getConnectivityToneColor(tone: ConnectivityTone, isDark: Boolean): Color = when (tone) {
    ConnectivityTone.CONNECTED -> Color(0xFF10B981)
    ConnectivityTone.PENDING -> Color(0xFFF59E0B)
    ConnectivityTone.IDLE -> if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
    ConnectivityTone.ERROR -> Color(0xFFF43F5E)
}

enum class AlertTone(
    private val base: Long,
    private val light: Long,
    private val dark: Long,
) {
    RED(0xFFFA5252, 0xFFE03131, 0xFFFF8787),
    BLUE(0xFF228BE6, 0xFF1C7ED6, 0xFF74C0FC),
    GREEN(0xFF40C057, 0xFF2F9E44, 0xFF8CE99A),
    ORANGE(0xFFFD7E14, 0xFFE8590C, 0xFFFFA94D),
    ;

    fun getColors(isDark: Boolean): ToneColors = ToneColors(
        background = Color(base).copy(alpha = if (isDark) 0.15f else 0.1f),
        content = Color(if (isDark) dark else light),
        border = Color(base).copy(alpha = if (isDark) 0.3f else 0.2f),
    )
}

val DangerColor = Color(0xFFE11D48)
val DangerColorDark = Color(0xFFFB7185)
val SuccessColor = Color(0xFF10B981)
