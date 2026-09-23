package com.octelium.client.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTest {

    @Test
    fun testNormalizePrefs() {
        run {
            assertEquals(defaultPrefs, normalizePrefs(null, null, null))
        }
        run {
            val ret = normalizePrefs("dark", "example.com", true)
            assertEquals(ThemeMode.DARK, ret.theme)
            assertEquals("example.com", ret.primaryDomain)
            assertTrue(ret.multiCluster)
        }
        run {
            val ret = normalizePrefs("neon", "", null)
            assertEquals(ThemeMode.SYSTEM, ret.theme)
            assertNull(ret.primaryDomain)
            assertFalse(ret.multiCluster)
        }
        run {
            assertEquals("example.com", normalizePrefs("light", " Example.COM ", false).primaryDomain)
            assertNull(normalizePrefs("light", "   ", false).primaryDomain)
        }
    }

    @Test
    fun testResolveTheme() {
        assertFalse(resolveTheme(ThemeMode.LIGHT, true))
        assertTrue(resolveTheme(ThemeMode.DARK, false))
        assertTrue(resolveTheme(ThemeMode.SYSTEM, true))
        assertFalse(resolveTheme(ThemeMode.SYSTEM, false))
    }

    @Test
    fun testGetNextThemeMode() {
        assertEquals(ThemeMode.LIGHT, getNextThemeMode(ThemeMode.SYSTEM))
        assertEquals(ThemeMode.DARK, getNextThemeMode(ThemeMode.LIGHT))
        assertEquals(ThemeMode.SYSTEM, getNextThemeMode(ThemeMode.DARK))
        assertEquals("System", getThemeModeLabel(ThemeMode.SYSTEM))
        assertEquals("Dark", getThemeModeLabel(ThemeMode.DARK))
    }
}
