package app.vezuna.localization

import kotlin.time.Instant

/** UI-only local date formatting. Doctor exports deliberately use DoctorReportFormat instead. */
expect fun formatUiDate(instant: Instant, languageTag: String = deviceLanguageTag()): String
expect fun formatUiDateTime(instant: Instant, languageTag: String = deviceLanguageTag()): String

/** Month and year of a calendar day ("October 2026"), for list section headings. */
expect fun formatUiMonth(instant: Instant, languageTag: String = deviceLanguageTag()): String
