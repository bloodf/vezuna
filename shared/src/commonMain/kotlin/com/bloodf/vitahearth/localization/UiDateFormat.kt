package com.bloodf.vitahearth.localization

import kotlinx.datetime.Instant

/** UI-only local date formatting. Doctor exports deliberately use DoctorReportFormat instead. */
expect fun formatUiDate(instant: Instant, languageTag: String = deviceLanguageTag()): String
expect fun formatUiDateTime(instant: Instant, languageTag: String = deviceLanguageTag()): String
