package app.vezuna.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

/**
 * Reminder facts only the host platform knows, shown on the Medications and Settings screens.
 * The lock-screen preference lives outside the vault so notification receivers can read it while the vault loads.
 */
class ReminderPlatformState(
    hideMedicationNames: Boolean = false,
    private val persistHideMedicationNames: (Boolean) -> Unit = {},
    /** Opens the system screen where exact alarms can be allowed; null where the platform has no such concept. */
    val openExactAlarmSettings: (() -> Unit)? = null,
) {
    private val hideNames = MutableStateFlow(hideMedicationNames)
    val hideMedicationNames: StateFlow<Boolean> = hideNames.asStateFlow()

    private val alarmsDenied = MutableStateFlow(false)
    /** True when exact alarms are not allowed, so reminders fall back to inexact timing. */
    val exactAlarmsDenied: StateFlow<Boolean> = alarmsDenied.asStateFlow()

    private val coverage = MutableStateFlow<Instant?>(null)
    /** Last scheduled reminder, when the platform can only schedule a limited window ahead (iOS). */
    val coverageUntil: StateFlow<Instant?> = coverage.asStateFlow()

    fun setHideMedicationNames(hide: Boolean) {
        hideNames.value = hide
        persistHideMedicationNames(hide)
    }

    fun setExactAlarmsDenied(denied: Boolean) { alarmsDenied.value = denied }

    fun setCoverageUntil(until: Instant?) { coverage.value = until }

    companion object {
        val LOW_COVERAGE = 3.days

        fun isCoverageLow(until: Instant, now: Instant): Boolean = until - now < LOW_COVERAGE
    }
}
