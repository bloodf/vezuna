package app.vezuna.notifications

import android.content.Context

/**
 * Per-device reminder preferences kept outside the vault, so receivers can read them while the vault is still loading.
 */
class ReminderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var hideMedicationNames: Boolean
        get() = prefs.getBoolean(HIDE_NAMES, false)
        set(value) = prefs.edit().putBoolean(HIDE_NAMES, value).apply()

    private companion object {
        const val PREFS = "vezuna-reminder-preferences"
        const val HIDE_NAMES = "hide-medication-names"
    }
}
