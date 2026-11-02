# Android exact alarms

Dose reminders use `AlarmManager.setExactAndAllowWhileIdle` so Doze cannot delay a dose by minutes.

## Permission choice

- `SCHEDULE_EXACT_ALARM` is declared. On Android 12 it is granted by default; on Android 14+ new installs start denied and the user grants it in system settings ("Alarms & reminders").
- `USE_EXACT_ALARM` is not used. Google Play restricts it to apps whose core function is an alarm clock, timer or calendar. A medication reminder app is not in that group, so declaring it risks review rejection. Revisit only if Play policy explicitly adds medication reminders.
- `setAlarmClock` is not used: it shows an alarm icon in the status bar and is also intended for alarm-clock apps.

## Fallback

`MedicationNotificationScheduler.canScheduleExactAlarms()` is checked before every alarm. When denied (or revoked mid-call), the alarm falls back to `setAndAllowWhileIdle` (inexact). The Medications screen then shows a localized explanation with a button to the system screen (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`). When the permission state changes, the `RescheduleReceiver` re-arms every alarm.
