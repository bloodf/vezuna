package app.vezuna.domain

import app.vezuna.localization.ContentLanguage
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearsUntil

/** Birth-date rules. A birth date is a calendar day, never an instant, so age never shifts with the time zone. */
object ProfileAge {
    const val MAX_YEARS = 130

    fun today(now: Instant, zone: TimeZone): LocalDate = now.toLocalDateTime(zone).date

    fun isValid(birthDate: LocalDate, today: LocalDate): Boolean = birthDate <= today && birthDate.yearsUntil(today) <= MAX_YEARS

    /** Completed years on [today]; null when the date is unset or invalid. */
    fun years(birthDate: LocalDate?, today: LocalDate): Int? = birthDate?.takeIf { isValid(it, today) }?.yearsUntil(today)
}

/** Name given to the first profile when the user does not choose one. Never applied to an existing profile. */
object DefaultProfileName {
    fun of(language: ContentLanguage): String = when (language) {
        ContentLanguage.EN -> "Me"
        ContentLanguage.PT -> "Eu"
        ContentLanguage.ES -> "Yo"
    }
}
