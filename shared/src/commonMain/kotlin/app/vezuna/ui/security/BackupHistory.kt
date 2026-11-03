package app.vezuna.ui.security

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.healthRecords
import app.vezuna.domain.allDocuments
import app.vezuna.security.BackupMember
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days

/**
 * Device-local record of the last backup this household saved (#69). It lives in a plain app preference, never in
 * the vault or inside a backup file, so restoring a backup or resetting the vault does not invent a date.
 * [savedAt] is null until a backup was saved; [household] says whether that backup held every profile.
 * [remind] turns the in-app reminder off; nothing is ever scheduled or sent off the device.
 */
data class BackupHistory(val savedAt: Instant? = null, val household: Boolean = false, val remind: Boolean = true) {
    fun encode(): String = "${savedAt?.epochSeconds ?: ""}|${if (household) "household" else "profile"}|${if (remind) "remind" else "quiet"}"

    /** True when there is data to protect and no backup was saved in the last [REMINDER_DAYS] days. */
    fun reminderDue(now: Instant, hasData: Boolean): Boolean =
        remind && hasData && (savedAt == null || now - savedAt >= REMINDER_DAYS.days)

    companion object {
        const val REMINDER_DAYS = 30

        /** Unreadable or missing text reads as "never backed up", the only claim that cannot be wrong. */
        fun decode(text: String?): BackupHistory {
            val parts = text?.split('|') ?: return BackupHistory()
            if (parts.size != 3) return BackupHistory()
            return BackupHistory(
                savedAt = parts[0].toLongOrNull()?.let(Instant::fromEpochSeconds),
                household = parts[1] == "household",
                remind = parts[2] != "quiet",
            )
        }
    }
}

/** What a backup (or the part of it shown) holds, counted from the decoded records themselves. */
data class BackupContents(
    val profiles: Int,
    val reports: Int,
    val confirmedResults: Int,
    val resultsToReview: Int,
    val medications: Int,
    val doseEvents: Int,
    val hasDeviceData: Boolean,
    val chatMessages: Int,
    /** Allergies, conditions and vaccinations. */
    val healthRecords: Int,
    val documents: Int,
    /** Documents whose original file (first page) travels in the backup. */
    val originalFiles: Int,
) {
    companion object {
        fun of(members: List<BackupMember>): BackupContents = BackupContents(
            profiles = members.size,
            reports = members.sumOf { it.data.reports.size },
            confirmedResults = members.sumOf { m -> m.data.observations.count { it.confirmation == ConfirmationStatus.CONFIRMED } },
            resultsToReview = members.sumOf { m -> m.data.ocrCandidates.count { it.status == ConfirmationStatus.UNREVIEWED } },
            medications = members.sumOf { it.data.medications.size },
            doseEvents = members.sumOf { it.data.doseEvents.size },
            hasDeviceData = members.any { !it.data.vitals.isEmpty },
            chatMessages = members.sumOf { it.data.chat.size },
            healthRecords = members.sumOf { it.data.healthRecords.size },
            documents = members.sumOf { it.data.allDocuments().size },
            originalFiles = members.sumOf { m -> m.data.allDocuments().count { it.documentId in m.files } },
        )
    }
}

/** The members to write when the user chose to leave assistant chat out (#19). Everything else is unchanged. */
fun List<BackupMember>.withoutChat(): List<BackupMember> = map { BackupMember(it.data.copy(chat = emptyList()), it.files) }
