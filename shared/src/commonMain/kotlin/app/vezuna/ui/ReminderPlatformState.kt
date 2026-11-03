package app.vezuna.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant
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
    /** Opens this app's notification settings in the system; null where the platform cannot. */
    val openNotificationSettings: (() -> Unit)? = null,
) {
    private val hideNames = MutableStateFlow(hideMedicationNames)
    val hideMedicationNames: StateFlow<Boolean> = hideNames.asStateFlow()

    private val alarmsDenied = MutableStateFlow(false)
    /** True when exact alarms are not allowed, so reminders fall back to inexact timing. */
    val exactAlarmsDenied: StateFlow<Boolean> = alarmsDenied.asStateFlow()

    private val coverage = MutableStateFlow<Instant?>(null)
    /** Last scheduled reminder, when the platform can only schedule a limited window ahead (iOS). */
    val coverageUntil: StateFlow<Instant?> = coverage.asStateFlow()

    private val access = MutableStateFlow(NotificationAccess.UNKNOWN)
    /** Whether the system lets reminders appear. Schedules are saved either way. */
    val notificationAccess: StateFlow<NotificationAccess> = access.asStateFlow()

    fun setNotificationAccess(value: NotificationAccess) { access.value = value }

    fun setHideMedicationNames(hide: Boolean) {
        hideNames.value = hide
        persistHideMedicationNames(hide)
    }

    fun setExactAlarmsDenied(denied: Boolean) { alarmsDenied.value = denied }

    fun setCoverageUntil(until: Instant?) { coverage.value = until }

    companion object {
        /** Under this, the status card explains the iOS limit in a neutral tone; a big household can sit here permanently. */
        val SHORT_COVERAGE = 14.days
        /** Under this, reminders are about to stop: the card turns into a warning that says to open the app. */
        val URGENT_COVERAGE = 3.days

        fun coverageLevel(until: Instant, now: Instant): CoverageLevel = when {
            until - now < URGENT_COVERAGE -> CoverageLevel.URGENT
            until - now < SHORT_COVERAGE -> CoverageLevel.SHORT
            else -> CoverageLevel.OK
        }
    }
}

enum class CoverageLevel { OK, SHORT, URGENT }

/** System notification permission as the platform reports it. UNKNOWN shows nothing rather than guessing. */
enum class NotificationAccess { UNKNOWN, NOT_REQUESTED, ALLOWED, DENIED }
