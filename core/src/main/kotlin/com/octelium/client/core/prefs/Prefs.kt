package com.octelium.client.core.prefs

import java.util.Locale

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

data class Prefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val primaryDomain: String? = null,
    val multiCluster: Boolean = false,
)

val defaultPrefs = Prefs()

fun normalizePrefs(theme: String?, primaryDomain: String?, multiCluster: Boolean?): Prefs = Prefs(
    theme = ThemeMode.entries.find { it.key == theme } ?: defaultPrefs.theme,
    primaryDomain = primaryDomain?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null },
    multiCluster = multiCluster ?: defaultPrefs.multiCluster,
)

fun resolveTheme(arg: ThemeMode, prefersDark: Boolean): Boolean = when (arg) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> prefersDark
}

fun getNextThemeMode(arg: ThemeMode): ThemeMode = when (arg) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
}

fun getThemeModeLabel(arg: ThemeMode): String = when (arg) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
