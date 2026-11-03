package app.vezuna.ui.more

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

/**
 * Hooks the platform shells fill in at startup: the saved theme choice, the app version, the OS language
 * settings page and the browser. Unset hooks make the matching row show its truthful fallback.
 */
object MorePlatform {
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** Persists a user choice; set by the platform together with [restoreTheme]. */
    var persistTheme: (ThemeMode) -> Unit = {}
    var versionName: String? = null
    var buildNumber: String? = null
    var openLanguageSettings: (() -> Unit)? = null
    var openUrl: ((String) -> Unit)? = null

    /** Opens the phone's own emergency info (Android settings, Health app on iOS); null when the platform cannot, so the card shows steps only. */
    var openEmergencyInfo: (() -> Unit)? = null
    var phoneIsIos: Boolean = false

    fun restoreTheme(mode: ThemeMode) {
        themeMode = mode
    }

    fun chooseTheme(mode: ThemeMode) {
        themeMode = mode
        persistTheme(mode)
    }
}

object VezunaLinks {
    const val REPOSITORY = "https://github.com/bloodf/vezuna"
    const val LICENSE = "$REPOSITORY/blob/main/LICENSE"
    const val CONTRIBUTORS = "$REPOSITORY/graphs/contributors"
    const val NEW_ISSUE = "$REPOSITORY/issues/new"
}
