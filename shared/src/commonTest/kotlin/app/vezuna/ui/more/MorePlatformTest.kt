package app.vezuna.ui.more

import kotlin.test.Test
import kotlin.test.assertEquals

class MorePlatformTest {
    @Test
    fun themeModeResolvesAgainstSystem() {
        assertEquals(true, ThemeMode.SYSTEM.isDark(true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(false))
        assertEquals(false, ThemeMode.LIGHT.isDark(true))
        assertEquals(true, ThemeMode.DARK.isDark(false))
    }

    @Test
    fun choosingThemePersistsAndRestoreDoesNot() {
        val saved = mutableListOf<ThemeMode>()
        MorePlatform.persistTheme = { saved += it }
        MorePlatform.restoreTheme(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, MorePlatform.themeMode)
        assertEquals(emptyList(), saved)
        MorePlatform.chooseTheme(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, MorePlatform.themeMode)
        assertEquals(listOf(ThemeMode.DARK), saved)
        MorePlatform.persistTheme = {}
        MorePlatform.restoreTheme(ThemeMode.SYSTEM)
    }
}
