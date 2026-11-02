package app.vezuna.localization

import java.util.Locale
import kotlin.time.Instant

actual fun deviceLanguageTag(): String = Locale.getDefault().toLanguageTag()

actual fun devicePaperTag(): String = deviceLanguageTag()

actual fun formatUiDate(instant: Instant, languageTag: String): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withLocale(Locale.forLanguageTag(languageTag))
        .withZone(java.time.ZoneOffset.UTC) // collection dates are calendar days stored as noon UTC
        .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))

actual fun formatUiDateTime(instant: Instant, languageTag: String): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        .withLocale(Locale.forLanguageTag(languageTag))
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))

actual fun formatUiMonth(instant: Instant, languageTag: String): String =
    java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag(languageTag))
        .withZone(java.time.ZoneOffset.UTC)
        .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))
