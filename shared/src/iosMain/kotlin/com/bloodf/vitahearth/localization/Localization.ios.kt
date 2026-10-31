package com.bloodf.vitahearth.localization

import kotlinx.datetime.Instant
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.preferredLanguages

actual fun deviceLanguageTag(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"

actual fun formatUiDate(instant: Instant, languageTag: String): String =
    formatter(languageTag, withTime = false).stringFromDate(instant.toNSDate())

actual fun formatUiDateTime(instant: Instant, languageTag: String): String =
    formatter(languageTag, withTime = true).stringFromDate(instant.toNSDate())

private fun formatter(languageTag: String, withTime: Boolean) = NSDateFormatter().apply {
    locale = NSLocale(localeIdentifier = languageTag)
    timeZone = NSTimeZone.localTimeZone
    dateStyle = NSDateFormatterMediumStyle
    timeStyle = if (withTime) NSDateFormatterShortStyle else NSDateFormatterNoStyle
}

private fun Instant.toNSDate(): NSDate = NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble() + nanosecondsOfSecond / 1_000_000_000.0)
