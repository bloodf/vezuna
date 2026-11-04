package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.StorageSchema
import app.vezuna.data.StorageSchemaTooNewException
import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.restoreMembers
import app.vezuna.domain.Appointment
import app.vezuna.domain.AppointmentReminders
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.domain.pastAppointments
import app.vezuna.domain.remappedAsNew
import app.vezuna.domain.upcomingAppointments
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.VitaMessageException
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.security.PassphraseBackupCodec
import app.vezuna.ui.AppScreen
import app.vezuna.ui.RestoreMode
import app.vezuna.ui.nav.routeFromName
import app.vezuna.ui.records.AppointmentDraft
import app.vezuna.ui.records.toDraft
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class AppointmentsTest {
    private val pass = "correct horse battery".toCharArray()
    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val soon = Instant.parse("2026-11-10T14:30:00Z")
    private val earlier = Instant.parse("2026-10-01T09:00:00Z")
    private val utc = TimeZone.UTC

    private fun withVisits(base: ProfileData = sampleProfileData("A")): ProfileData {
        val p = base.profile.profileId
        return base.copy(
            appointments = listOf(
                Appointment(p, "$p-next", soon, "Dr. Silva", "Bring last results", 60),
                Appointment(p, "$p-later", Instant.parse("2027-01-05T10:00:00Z"), "Dr. Costa"),
                Appointment(p, "$p-old", earlier, "Dr. Lima", "  checkup "),
            ),
        )
    }

    // ---- model and validation ----

    @Test
    fun modelRejectsBlankClinicianAndOutOfRangeReminder() {
        assertFailsWith<IllegalArgumentException> { Appointment("p", "a", soon, "  ") }
        assertFailsWith<IllegalArgumentException> { Appointment("p", "a", soon, "Dr", reminderMinutesBefore = 0) }
        assertFailsWith<IllegalArgumentException> { Appointment("p", "a", soon, "Dr", reminderMinutesBefore = Appointment.MAX_REMINDER_MINUTES + 1) }
        assertEquals(soon - 60.minutes, Appointment("p", "a", soon, "Dr", reminderMinutesBefore = 60).reminderAt)
        assertNull(Appointment("p", "a", soon, "Dr").reminderAt)
    }

    @Test
    fun upcomingAndPastAreSeparatedAndOrdered() {
        val data = withVisits()
        assertEquals(listOf("A-profile-next", "A-profile-later"), data.upcomingAppointments(now).map { it.appointmentId })
        assertEquals(listOf("A-profile-old"), data.pastAppointments(now).map { it.appointmentId })
        // A visit starting right now is still upcoming.
        assertEquals(listOf("A-profile-next"), data.upcomingAppointments(soon).take(1).map { it.appointmentId })
    }

    @Test
    fun draftCombinesDateAndTimeInTheDeviceZoneAndRoundTrips() {
        val draft = AppointmentDraft(date = Instant.parse("2026-11-10T12:00:00Z"), time = "14:30", clinician = " Dr. Silva ", notes = "  ", reminderMinutesBefore = 60)
        assertEquals(soon, draft.at(utc))
        val saved = draft.toAppointment("p", "a", utc)
        assertEquals("Dr. Silva", saved.clinician)
        assertNull(saved.notes)
        assertEquals(draft.copy(clinician = "Dr. Silva", notes = ""), saved.toDraft(utc))
        assertNull(AppointmentDraft(date = null, clinician = "x").at(utc))
        assertNull(draft.copy(time = "25:00").at(utc))
        assertTrue(AppointmentDraft(clinician = " ").clinicianMissing)
    }

    @Test
    fun aReminderThatAlreadyPassedIsInvalid() {
        val draft = AppointmentDraft(date = Instant.parse("2026-11-03T12:00:00Z"), time = "12:30", clinician = "Dr", reminderMinutesBefore = 60)
        assertTrue(draft.reminderPassed(now, utc))
        assertFalse(draft.isValid(now, utc))
        assertTrue(draft.copy(reminderMinutesBefore = 15).isValid(now, utc))
        assertTrue(draft.copy(reminderMinutesBefore = null).isValid(now, utc))
    }

    // ---- controller, isolation ----

    @Test
    fun controllerAddsEditsAndDeletesOnlyInTheCurrentProfile() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        val first = controller.state.value.data!!.profile.profileId
        val draft = AppointmentDraft(date = Instant.parse("2099-05-04T12:00:00Z"), time = "09:15", clinician = "Dr. Silva", reminderMinutesBefore = 15)
        controller.saveAppointment(null, draft)
        assertNull(controller.state.value.error)
        val saved = controller.state.value.data!!.appointments.single()

        controller.createProfile("Bia")
        assertTrue(controller.state.value.data!!.appointments.isEmpty(), "another profile sees none")
        controller.deleteAppointment(saved.appointmentId)
        assertEquals(L10nKey.APPT_NOT_FOUND, (controller.state.value.error as UserText.Key).key)

        controller.switchProfile(first)
        controller.saveAppointment(saved.appointmentId, saved.toDraft(TimeZone.currentSystemDefault()).copy(clinician = "Dr. Costa", reminderMinutesBefore = null))
        assertNull(controller.state.value.error)
        assertEquals(listOf("Dr. Costa" to null), repository.readProfile(first).appointments.map { it.clinician to it.reminderMinutesBefore })
        controller.deleteAppointment(saved.appointmentId)
        assertTrue(repository.readProfile(first).appointments.isEmpty())
    }

    @Test
    fun controllerRefusesInvalidDraftsWithoutSaving() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        val future = Instant.parse("2099-05-04T12:00:00Z")
        controller.saveAppointment(null, AppointmentDraft(date = future, clinician = " "))
        assertEquals(L10nKey.APPT_CLINICIAN_REQUIRED, (controller.state.value.error as UserText.Key).key)
        controller.saveAppointment(null, AppointmentDraft(date = null, clinician = "Dr"))
        assertEquals(L10nKey.APPT_DATE_REQUIRED, (controller.state.value.error as UserText.Key).key)
        controller.saveAppointment(null, AppointmentDraft(date = Instant.parse("2020-01-01T12:00:00Z"), clinician = "Dr", reminderMinutesBefore = 60))
        assertEquals(L10nKey.APPT_REMINDER_PASSED, (controller.state.value.error as UserText.Key).key)
        assertTrue(controller.state.value.data!!.appointments.isEmpty())
        // A past visit without a reminder is a valid record.
        controller.saveAppointment(null, AppointmentDraft(date = Instant.parse("2020-01-01T12:00:00Z"), clinician = "Dr"))
        assertEquals(1, repository.readProfile(controller.state.value.data!!.profile.profileId).appointments.size)
    }

    // ---- persistence ----

    @Test
    fun theyPersistInTheVaultAndGoWithTheProfile() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        repository.createProfile(Profile("a", "Ana", createdAt = now))
        repository.createProfile(Profile("b", "Bia", createdAt = now))
        repository.addAppointment(Appointment("a", "v1", soon, "Dr. Silva", "notes", 60))
        repository.addAppointment(Appointment("a", "v2", earlier, "Dr. Lima"))
        repository.updateAppointment(Appointment("a", "v1", soon, "Dr. Silva", "changed", null))
        repository.deleteAppointment("a", "v2")
        assertFailsWith<IllegalArgumentException> { repository.addAppointment(Appointment("a", "v1", soon, "Again")) }
        assertFailsWith<IllegalArgumentException> { repository.updateAppointment(Appointment("a", "missing", soon, "Nope")) }
        assertFailsWith<IllegalArgumentException> { repository.deleteAppointment("b", "v1") }

        val reopened = VaultBackedRepository(vault).also { it.load() }
        val stored = reopened.readProfile("a").appointments.single()
        assertEquals(Triple(soon, "changed", null), Triple(stored.at, stored.notes, stored.reminderMinutesBefore))
        assertTrue(reopened.readProfile("b").appointments.isEmpty())
    }

    @Test
    fun aPreChangeProfileLoadsWithNoVisitsAndEmptyListsAreNotWritten() {
        val json = Json { encodeDefaults = true }
        val old = """{"profile":{"profileId":"p","displayName":"Ana","createdAt":"2026-03-10T12:00:00Z"},"documents":[],"reports":[],"observations":[],"ocrCandidates":[],"medications":[],"doseEvents":[],"chat":[]}"""
        val data = json.decodeFromString(ProfileData.serializer(), old)
        assertTrue(data.appointments.isEmpty())
        assertFalse("appointments" in json.encodeToString(ProfileData.serializer(), data), "would make older builds refuse this file")
        assertTrue("appointments" in json.encodeToString(ProfileData.serializer(), withVisits()))
    }

    @Test
    fun aStoredProfileHoldingAnotherProfilesVisitIsNotLoaded() = runTest {
        val vault = GenerationalMemoryVault()
        val repo = VaultBackedRepository(vault).also { it.load() }
        repo.createProfile(Profile("a", "Ana", createdAt = now))
        repo.addAppointment(Appointment("a", "v1", soon, "Dr"))
        val key = "repository-profile-a-v1"
        val envelope = app.vezuna.security.CipherEnvelopeCodec.decode(vault.current.getValue(key))
        val plain = vault.decrypt(envelope).decodeToString()
        val forged = plain.replace("\"profileId\":\"a\",\"appointmentId\"", "\"profileId\":\"b\",\"appointmentId\"")
        assertTrue(forged != plain, "the visit was rewritten")
        vault.current[key] = app.vezuna.security.CipherEnvelopeCodec.encode(vault.encrypt(forged.encodeToByteArray(), envelope.associatedData))
        vault.previous.remove(key)
        val reopened = VaultBackedRepository(vault).also { runCatching { it.load() } }
        assertTrue(runCatching { reopened.readProfile("a").appointments }.getOrNull().orEmpty().isEmpty(), "another profile's visit must not load into a")
    }

    // ---- backup and restore ----

    private suspend fun backupSchemaOf(file: ByteArray): Int =
        Json.parseToJsonElement(PassphraseBackupCodec.open(file, pass.copyOf()).decodeToString()).jsonObject.getValue("schemaVersion").jsonPrimitive.int

    @Test
    fun theySurviveBackupsAndAreWrittenAsTheNewerSchema() = runTest(timeout = 5.minutes) {
        val service = BackupService()
        val a = withVisits(sampleProfileData("A"))
        val b = withVisits(sampleProfileData("B"))
        assertEquals(a, service.open(service.create(a, pass.copyOf()), pass.copyOf()).data)
        assertEquals(6, backupSchemaOf(service.create(a, pass.copyOf())))
        val household = service.createHousehold(listOf(BackupMember(a), BackupMember(b)), pass.copyOf())
        assertEquals(6, backupSchemaOf(household))
        // Health records alone stay at 5, and a household mixing both takes the highest number any member needs.
        val withHealth = sampleProfileData("D").copy(allergies = listOf(app.vezuna.domain.AllergyRecord("D-profile", "d1", "Latex")))
        assertEquals(5, backupSchemaOf(service.createHousehold(listOf(BackupMember(withHealth)), pass.copyOf())))
        assertEquals(6, backupSchemaOf(service.createHousehold(listOf(BackupMember(withHealth), BackupMember(a)), pass.copyOf())))
        assertEquals(listOf(a, b), service.open(household, pass.copyOf()).members.map { it.data })
        // Without visits the schema stays where it was, so older builds keep reading those backups.
        assertEquals(4, backupSchemaOf(service.createHousehold(listOf(BackupMember(sampleProfileData("C"))), pass.copyOf())))
    }

    @Test
    fun anOlderBackupLoadsWithNoVisitsAndANewerOneIsRefusedAsUnsupported() = runTest(timeout = 5.minutes) {
        val service = BackupService()
        val profile = """{"profileId":"p","displayName":"Ana","createdAt":"2026-03-10T12:00:00Z"}"""
        suspend fun open(payload: String) = service.open(PassphraseBackupCodec.seal(payload.encodeToByteArray(), pass.copyOf()), pass.copyOf())
        val old = open("""{"schemaVersion":4,"exportedAt":"2026-03-10T12:00:00Z","members":[{"data":{"profile":$profile}}]}""")
        assertTrue(old.members.single().data.appointments.isEmpty())
        listOf(5, 6).forEach { v -> assertEquals(1, open("""{"schemaVersion":$v,"exportedAt":"2026-03-10T12:00:00Z","members":[{"data":{"profile":$profile}}]}""").members.size) }
        val failure = assertFailsWith<VitaMessageException> {
            open("""{"schemaVersion":7,"exportedAt":"2026-03-10T12:00:00Z","members":[{"data":{"profile":$profile,"appointments":[]}}]}""")
        }
        assertEquals(L10nKey.BACKUP_UNSUPPORTED_SCHEMA, failure.userText.key)
    }

    @Test
    fun restoreKeepsVisitsInTheVaultBackedRepositoryAndRestoreAsNewRemapsIds() = runTest(timeout = 5.minutes) {
        listOf(RestoreMode.REPLACE_EXISTING, RestoreMode.RESTORE_AS_NEW).forEach { mode ->
            val vault = GenerationalMemoryVault()
            val repo = VaultBackedRepository(vault).also { it.load() }
            val data = withVisits(sampleProfileData("A"))
            val service = BackupService()
            val opened = service.open(service.createHousehold(listOf(BackupMember(data)), pass.copyOf()), pass.copyOf())
            var next = 0
            val outcome = repo.restoreMembers(opened.members, mode, null) { "new-${next++}" }
            val restored = VaultBackedRepository(vault).also { it.load() }.readProfile(outcome.profileIds.single())
            fun List<Appointment>.facts() = map { Triple(it.at, it.clinician, it.reminderMinutesBefore) to it.notes }.toSet()
            assertEquals(data.appointments.facts(), restored.appointments.facts())
            assertTrue(restored.isInternallyConsistent())
            assertTrue(restored.appointments.all { it.profileId == restored.profile.profileId })
            if (mode == RestoreMode.RESTORE_AS_NEW) assertTrue(restored.appointments.none { it.appointmentId in data.appointments.map { old -> old.appointmentId } })
        }
        var n = 0
        val copy = withVisits().remappedAsNew { "id-${n++}" }
        assertTrue(copy.isInternallyConsistent())
    }

    @Test
    fun forgedVisitsAreRefused() {
        val base = sampleProfileData("A")
        val p = base.profile.profileId
        assertFalse(base.copy(appointments = listOf(Appointment("other", "x1", soon, "Dr"))).isInternallyConsistent(), "another profile's visit")
        assertFalse(base.copy(appointments = listOf(Appointment(p, "d", soon, "Dr"), Appointment(p, "d", soon, "Dr 2"))).isInternallyConsistent(), "duplicate id")
        assertFalse(base.copy(appointments = listOf(Appointment(p, "../x", soon, "Dr"))).isInternallyConsistent(), "hostile id")
    }

    // ---- reminders ----

    @Test
    fun remindersExistOnlyForOptedInUpcomingVisits() {
        val data = withVisits()
        val notices = AppointmentReminders.notices(data, now)
        assertEquals(listOf("A-profile-next"), notices.map { it.appointmentId }, "only the visit that opted in")
        assertEquals(soon - 60.minutes, notices.single().fireAt)
        assertEquals("Dr. Silva", notices.single().clinician)
        // Once the reminder was delivered nothing is armed, so it never repeats.
        assertTrue(AppointmentReminders.notices(data, soon - 30.minutes, handled = setOf(notices.single().key)).isEmpty())
    }

    @Test
    fun deletingAVisitOrSwitchingItsReminderOffRemovesItsReminder() {
        val data = withVisits()
        val id = "A-profile-next"
        assertEquals(listOf(id), AppointmentReminders.notices(data, now).map { it.appointmentId })
        val off = data.copy(appointments = data.appointments.map { if (it.appointmentId == id) it.copy(reminderMinutesBefore = null) else it })
        assertTrue(AppointmentReminders.notices(off, now).isEmpty())
        val deleted = data.copy(appointments = data.appointments.filterNot { it.appointmentId == id })
        assertTrue(AppointmentReminders.notices(deleted, now).isEmpty())
    }

    @Test
    fun editingTheTimeMovesTheReminder() {
        val data = withVisits()
        val moved = data.copy(appointments = data.appointments.map { if (it.appointmentId == "A-profile-next") it.copy(at = soon + 60.minutes) else it })
        assertEquals(soon, AppointmentReminders.notices(moved, now).single().fireAt)
    }

    @Test
    fun aStoredDocumentFromANewerSchemaIsRefusedNotMisread() {
        val json = Json { encodeDefaults = true }
        val document = json.encodeToJsonElement(ProfileData.serializer(), withVisits()) as kotlinx.serialization.json.JsonObject
        assertEquals(4, StorageSchema.CURRENT)
        assertEquals(StorageSchema.stamp(document), StorageSchema.migrate(StorageSchema.stamp(document, 3)), "older documents migrate with nothing lost")
        assertFailsWith<StorageSchemaTooNewException> { StorageSchema.migrate(StorageSchema.stamp(document, 5)) }
    }

    @Test
    fun theVisitTimeIsTheSameInstantInAnyDeviceZone() {
        val draft = AppointmentDraft(date = Instant.parse("2026-11-10T12:00:00Z"), time = "14:30", clinician = "Dr")
        val saoPaulo = TimeZone.of("America/Sao_Paulo")
        val lisbon = TimeZone.of("Europe/Lisbon")
        assertEquals(Instant.parse("2026-11-10T17:30:00Z"), draft.at(saoPaulo))
        assertEquals(Instant.parse("2026-11-10T14:30:00Z"), draft.at(lisbon))
        val saved = draft.toAppointment("p", "a", saoPaulo)
        assertEquals(draft, saved.toDraft(saoPaulo).copy(clinician = "Dr", notes = ""), "round trips in the zone it was entered in")
        assertEquals("02:30", saved.toDraft(TimeZone.of("Asia/Tokyo")).time, "the same instant read on a device in Tokyo")
    }

    @Test
    fun aVisitWhoseReminderAlreadyFiredCanStillBeEditedButNotGivenAPastReminder() {
        val existing = Appointment("p", "a", soon, "Dr", reminderMinutesBefore = 24 * 60)
        val afterReminder = soon - 12.minutes * 60
        val edited = existing.toDraft(utc).copy(notes = "bring results")
        assertTrue(edited.isValid(afterReminder, utc, existing), "unchanged reminder, other edit")
        assertFalse(edited.copy(reminderMinutesBefore = 2 * 24 * 60).isValid(afterReminder, utc, existing), "a new lead time already in the past is refused")
        assertTrue(edited.copy(reminderMinutesBefore = 15).isValid(afterReminder, utc, existing), "a new lead time still ahead is fine")
        assertFalse(edited.isValid(afterReminder, utc, null), "a new visit is checked")
    }

    @Test
    fun aReminderDueWhileTheDeviceWasOffStillFiresUntilDeliveredAndNeverTwice() {
        val data = withVisits()
        val dueAt = soon - 60.minutes
        val late = dueAt + 5.minutes
        val notice = AppointmentReminders.notices(data, late).single()
        assertEquals(dueAt, notice.fireAt, "keeps its original time; the platform fires it now")
        assertTrue(AppointmentReminders.notices(data, late, handled = setOf(notice.key)).isEmpty(), "delivered once")
        assertTrue(AppointmentReminders.notices(data, soon + 1.minutes).isEmpty(), "the visit started: too late")
        val moved = data.copy(appointments = data.appointments.map { if (it.appointmentId == "A-profile-next") it.copy(at = soon + 60.minutes) else it })
        assertEquals(1, AppointmentReminders.notices(moved, late, handled = setOf(notice.key)).size, "a moved visit is reminded again")
        assertEquals(setOf("A-profile-next"), AppointmentReminders.reminderIds(data))
    }

    @Test
    fun theNearestRemindersOfAHouseholdComeFirstWhateverTheProfileOrder() {
        val first = ProfileData(Profile("p1", "Ana", createdAt = soon), appointments = listOf(Appointment("p1", "far", soon + 90.days, "Dr. A", reminderMinutesBefore = 60), Appointment("p1", "started", soon - 2.hours, "Dr. S", reminderMinutesBefore = 15)))
        val second = ProfileData(Profile("p2", "Bia", createdAt = soon), appointments = listOf(Appointment("p2", "near", soon + 2.hours, "Dr. B", reminderMinutesBefore = 60)))
        val now = soon - 1.hours
        assertEquals(listOf("near", "far"), AppointmentReminders.noticesOf(listOf(first, second), now).map { it.appointmentId })
        assertEquals(setOf("far"), AppointmentReminders.aheadIds(first, now))
        assertEquals(setOf("far:${(soon + 90.days - 60.minutes).epochSeconds}"), AppointmentReminders.currentKeys(first, now), "the key a pending request must carry to stay current")
        assertTrue(AppointmentReminders.aheadIds(first, soon + 365.days).isEmpty(), "a visit that started is no longer ahead")
    }

    @Test
    fun routesParse() {
        assertEquals(AppScreen.Appointments, routeFromName("Appointments"))
        assertEquals(AppScreen.AppointmentForm(), routeFromName("AppointmentForm"))
        assertEquals(AppScreen.AppointmentForm("v1"), routeFromName("AppointmentForm:v1"))
    }
}
