package app.vezuna

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.vezuna.ui.more.MorePlatform
import app.vezuna.ui.more.ThemeMode

private const val PREFS = "vezuna-appearance"
private const val THEME_KEY = "theme-mode"

/** Fills the shared More hooks: saved theme, app version, OS language settings and the browser. */
internal fun installMorePlatform(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    MorePlatform.persistTheme = { prefs.edit().putString(THEME_KEY, it.name).apply() }
    MorePlatform.restoreTheme(prefs.getString(THEME_KEY, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM)
    @Suppress("DEPRECATION")
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    MorePlatform.versionName = info?.versionName
    MorePlatform.buildNumber = info?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString() }
    MorePlatform.openLanguageSettings = {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
        } else {
            Intent(Settings.ACTION_LOCALE_SETTINGS)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    MorePlatform.openUrl = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
