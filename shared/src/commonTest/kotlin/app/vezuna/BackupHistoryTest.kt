package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.ui.security.BackupContents
import app.vezuna.ui.security.BackupHistory
import app.vezuna.ui.security.withoutChat
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class BackupHistoryTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    @Test
    fun historyRoundTripsThroughThePreferenceText() {
        val saved = BackupHistory(savedAt = Instant.fromEpochSeconds(now.epochSeconds), household = true, remind = false)
        assertEquals(saved, BackupHistory.decode(saved.encode()))
        assertEquals(BackupHistory(), BackupHistory.decode(BackupHistory().encode()))
    }

    @Test
    fun missingOrDamagedTextMeansNeverBackedUp() {
        listOf(null, "", "garbage", "12|x", "abc|household|remind").forEach { text ->
            assertNull(BackupHistory.decode(text).savedAt, "text=$text")
        }
    }

    @Test
    fun reminderIsDueOnlyWithDataAndAfterThirtyDays() {
        assertTrue(BackupHistory().reminderDue(now, hasData = true), "never backed up")
        assertFalse(BackupHistory().reminderDue(now, hasData = false), "nothing to protect")
        assertFalse(BackupHistory(savedAt = now - 29.days).reminderDue(now, hasData = true))
        assertTrue(BackupHistory(savedAt = now - 30.days).reminderDue(now, hasData = true))
        assertFalse(BackupHistory(savedAt = now - 90.days, remind = false).reminderDue(now, hasData = true), "reminder turned off")
    }

    @Test
    fun contentsCountTheRecordsAndTheOriginalsThatTravel() {
        val a = sampleProfileData("A").let { d -> d.copy(observations = d.observations.map { it.copy(confirmation = ConfirmationStatus.CONFIRMED) }) }
        val withFile = BackupMember(a, mapOf("A-doc" to byteArrayOf(1)))
        val without = BackupMember(sampleProfileData("B"))
        val contents = BackupContents.of(listOf(withFile, without))
        assertEquals(2, contents.profiles)
        assertEquals(2, contents.reports)
        assertEquals(1, contents.confirmedResults, "unreviewed observations are not counted as confirmed")
        assertEquals(2, contents.resultsToReview)
        assertEquals(2, contents.medications)
        assertEquals(2, contents.doseEvents)
        assertEquals(2, contents.chatMessages)
        assertTrue(contents.hasDeviceData)
        assertEquals(2, contents.documents)
        assertEquals(1, contents.originalFiles, "only the first member carries its original")
    }

    @Test
    fun leavingChatOutRemovesOnlyTheChat() = runTest {
        val member = BackupMember(sampleProfileData("A"), mapOf("A-doc" to byteArrayOf(1, 2)))
        val stripped = listOf(member).withoutChat().single()
        assertTrue(stripped.data.chat.isEmpty())
        assertEquals(member.data.copy(chat = emptyList()), stripped.data)
        assertEquals(member.files.keys, stripped.files.keys)

        // The backup written without chat opens with every other record intact.
        val pass = "correct horse battery"
        val service = BackupService()
        val opened = service.open(service.create(stripped.data, pass.toCharArray()), pass.toCharArray())
        assertEquals(stripped.data, opened.data)
    }
}
