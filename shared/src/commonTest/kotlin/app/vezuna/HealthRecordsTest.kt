package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.StorageSchema
import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.AllergySeverity
import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.HealthRecordKind
import app.vezuna.domain.PartialDate
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.VaccinationRecord
import app.vezuna.domain.allergiesByName
import app.vezuna.domain.healthRecords
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.domain.remappedAsNew
import app.vezuna.domain.vaccinationsByDate
import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.ExportFamily
import app.vezuna.export.ExportOptions
import app.vezuna.llm.HealthPromptBuilder
import app.vezuna.localization.ContentLanguage
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.VitaMessageException
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.security.PassphraseBackupCodec
import app.vezuna.ui.AppScreen
import app.vezuna.ui.export.ExportScope
import app.vezuna.ui.nav.routeFromName
import app.vezuna.ui.records.HealthRecordDraft
import app.vezuna.ui.records.toDraft
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HealthRecordsTest {
    private val pass = "correct horse battery".toCharArray()
    private val at = Instant.parse("2026-03-10T12:00:00Z")
    private val today = LocalDate(2026, 11, 3)

    private fun withRecords(base: ProfileData = sampleProfileData("A")): ProfileData {
        val p = base.profile.profileId
        return base.copy(
            allergies = listOf(
                AllergyRecord(p, "${p}-allergy", "Penicilina", "Urticária nos braços", AllergySeverity.SEVERE, "  anotado por mim"),
                AllergyRecord(p, "${p}-allergy-2", "amendoim"),
            ),
            conditions = listOf(
                ConditionRecord(p, "${p}-condition", "Rhinitis", PartialDate(2015, 3), ConditionStatus.ONGOING),
                ConditionRecord(p, "${p}-condition-2", "Migraine", notes = "=exact year unknown"),
            ),
            vaccinations = listOf(
                VaccinationRecord(p, "${p}-vaccination-old", "Hepatitis B", Instant.parse("2019-03-02T12:00:00Z"), "3"),
                VaccinationRecord(p, "${p}-vaccination-undated", "Tetanus", doseNumber = "Booster"),
                VaccinationRecord(p, "${p}-vaccination", "Influenza", at, "1", "FLU-118"),
            ),
        )
    }

    // ---- dates ----

    @Test
    fun partialDatesKeepOnlyTheKnownPrecision() {
        assertEquals(PartialDate(2015), PartialDate.parse("2015", "", ""))
        assertEquals("2015", PartialDate(2015).iso)
        assertEquals("2015-03", PartialDate.parse(" 2015", "3 ", "")!!.iso)
        assertEquals("2015-03-09", PartialDate.parse("2015", "3", "9")!!.iso)
        assertEquals(PartialDate(1850), PartialDate.parse("1850", "", ""), "a restored older record can be saved again")
        listOf(Triple("", "3", ""), Triple("2015", "", "9"), Triple("2015", "13", ""), Triple("2015", "2", "30"), Triple("0", "", ""), Triple("10000", "", ""), Triple("abc", "", "")).forEach { (y, m, d) ->
            assertNull(PartialDate.parse(y, m, d), "$y-$m-$d")
        }
    }

    @Test
    fun aPartialDateIsFutureOnlyWhenItsWholeKnownPartIs() {
        assertFalse(PartialDate(2026).isAfter(today))
        assertFalse(PartialDate(2026, 11).isAfter(today))
        assertFalse(PartialDate(2026, 11, 3).isAfter(today))
        assertTrue(PartialDate(2026, 11, 4).isAfter(today))
        assertTrue(PartialDate(2026, 12).isAfter(today))
        assertTrue(PartialDate(2027).isAfter(today))
    }

    @Test
    fun draftKeepsTheWordsAndNeverInventsADate() {
        val condition = HealthRecordDraft(HealthRecordKind.CONDITION, name = " Asma na infância\n(leve) ", notes = "linha 1\nlinha 2").toRecord("p", "id") as ConditionRecord
        assertEquals("Asma na infância\n(leve)", condition.name)
        assertEquals("linha 1\nlinha 2", condition.notes)
        assertNull(condition.onset)
        assertNull(condition.status)
        assertNull((HealthRecordDraft(HealthRecordKind.VACCINATION, name = "Flu").toRecord("p", "id") as VaccinationRecord).date)
        assertNull((HealthRecordDraft(HealthRecordKind.ALLERGY, name = "Latex").toRecord("p", "id") as AllergyRecord).severity)
    }

    @Test
    fun draftValidationAndRoundTrip() {
        assertFalse(HealthRecordDraft(HealthRecordKind.ALLERGY, name = " ").isValid(today))
        assertTrue(HealthRecordDraft(HealthRecordKind.CONDITION, name = "x").isValid(today), "unknown onset is valid")
        assertFalse(HealthRecordDraft(HealthRecordKind.CONDITION, name = "x", onsetMonth = "3").isValid(today))
        assertFalse(HealthRecordDraft(HealthRecordKind.CONDITION, name = "x", onsetYear = "2027").isValid(today))
        assertFalse(HealthRecordDraft(HealthRecordKind.VACCINATION, name = "x", date = Instant.parse("2026-11-04T12:00:00Z")).isValid(today))
        withRecords().healthRecords.forEach { record ->
            val expected = when (record) {
                is AllergyRecord -> record.copy(notes = record.notes?.trim())
                else -> record
            }
            assertEquals(expected, record.toDraft().toRecord(record.profileId, record.id))
        }
    }

    @Test
    fun displayOrderIsAlphabeticalAndNewestVaccinationFirst() {
        val data = withRecords()
        assertEquals(listOf("amendoim", "Penicilina"), data.allergiesByName.map { it.substance })
        assertEquals(listOf("Influenza", "Hepatitis B", "Tetanus"), data.vaccinationsByDate.map { it.vaccine })
    }

    // ---- repository, controller, isolation ----

    @Test
    fun controllerAddsEditsAndDeletesOnlyInTheCurrentProfile() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        val first = controller.state.value.data!!.profile.profileId
        controller.saveHealthRecord(null, HealthRecordDraft(HealthRecordKind.ALLERGY, name = "Latex", severity = AllergySeverity.MILD))
        assertNull(controller.state.value.error)
        val saved = controller.state.value.data!!.allergies.single()
        assertEquals("Latex", saved.substance)

        controller.createProfile("Bia")
        assertTrue(controller.state.value.data!!.healthRecords.isEmpty(), "another profile sees none of them")
        controller.deleteHealthRecord(saved.id)
        assertEquals(L10nKey.HR_NOT_FOUND, (controller.state.value.error as UserText.Key).key, "a record of another profile cannot be reached")

        controller.switchProfile(first)
        controller.saveHealthRecord(saved.id, saved.toDraft().copy(reaction = "Rash"))
        assertNull(controller.state.value.error)
        assertEquals("Rash", repository.readProfile(first).allergies.single().reaction)
        controller.saveHealthRecord(saved.id, HealthRecordDraft(HealthRecordKind.VACCINATION, name = "Wrong kind"))
        assertEquals(L10nKey.HR_NOT_FOUND, (controller.state.value.error as UserText.Key).key)
        controller.deleteHealthRecord(saved.id)
        assertTrue(repository.readProfile(first).healthRecords.isEmpty())
    }

    @Test
    fun controllerRefusesAnInvalidDraftWithoutSaving() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        controller.saveHealthRecord(null, HealthRecordDraft(HealthRecordKind.CONDITION, name = "  "))
        assertEquals(L10nKey.HR_NAME_REQUIRED, (controller.state.value.error as UserText.Key).key)
        controller.saveHealthRecord(null, HealthRecordDraft(HealthRecordKind.CONDITION, name = "x", onsetMonth = "2"))
        assertEquals(L10nKey.HR_ONSET_INVALID, (controller.state.value.error as UserText.Key).key)
        assertTrue(controller.state.value.data!!.healthRecords.isEmpty())
    }

    @Test
    fun theyPersistInTheVaultAndGoWithTheProfile() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        repository.createProfile(Profile("a", "Ana", createdAt = at))
        repository.createProfile(Profile("b", "Bia", createdAt = at))
        repository.addHealthRecord(AllergyRecord("a", "a1", "Latex"))
        repository.addHealthRecord(ConditionRecord("a", "c1", "Asthma", PartialDate(2001)))
        repository.addHealthRecord(VaccinationRecord("a", "v1", "Flu", at))
        repository.updateHealthRecord(AllergyRecord("a", "a1", "Latex", "Rash"))
        assertFailsWith<IllegalArgumentException> { repository.addHealthRecord(AllergyRecord("a", "a1", "Again")) }
        assertFailsWith<IllegalArgumentException> { repository.updateHealthRecord(AllergyRecord("a", "missing", "Nope")) }
        assertFailsWith<IllegalArgumentException> { repository.deleteHealthRecord("b", "a1") }

        val reopened = VaultBackedRepository(vault).also { it.load() }
        val stored = reopened.readProfile("a")
        assertEquals("Rash", stored.allergies.single().reaction)
        assertEquals(PartialDate(2001), stored.conditions.single().onset)
        assertEquals(at, stored.vaccinations.single().date)
        assertTrue(reopened.readProfile("b").healthRecords.isEmpty())

        reopened.deleteProfile("a")
        val afterDelete = VaultBackedRepository(vault).also { it.load() }
        assertEquals(listOf("b"), afterDelete.profiles.value.map { it.profileId })
    }

    @Test
    fun storageSchemaStepIsANoOpSoOldDocumentsLoadUnchanged() {
        assertEquals(3, StorageSchema.CURRENT)
        val json = Json { encodeDefaults = true }
        val document = json.encodeToJsonElement(ProfileData.serializer(), ProfileData(Profile("p", "Ana", createdAt = at))) as kotlinx.serialization.json.JsonObject
        assertEquals(StorageSchema.stamp(document), StorageSchema.migrate(StorageSchema.stamp(document, 2)))
    }

    // ---- old data, backups ----

    @Test
    fun aPreChangeRecordDecodesWithEmptyListsAndEmptyListsAreNotWritten() {
        val json = Json { encodeDefaults = true }
        val old = """{"profile":{"profileId":"p","displayName":"Ana","createdAt":"2026-03-10T12:00:00Z"},"documents":[],"reports":[],"observations":[],"ocrCandidates":[],"medications":[],"doseEvents":[],"chat":[]}"""
        val data = json.decodeFromString(ProfileData.serializer(), old)
        assertTrue(data.healthRecords.isEmpty())
        val written = json.encodeToString(ProfileData.serializer(), data)
        listOf("allergies", "conditions", "vaccinations").forEach { assertFalse(it in written, "$it would make older builds refuse this file") }
        assertTrue("allergies" in json.encodeToString(ProfileData.serializer(), withRecords()))
    }

    @Test
    fun theySurviveSingleAndHouseholdBackups() = runTest {
        val service = BackupService()
        val a = withRecords(sampleProfileData("A"))
        val b = withRecords(sampleProfileData("B"))
        assertEquals(a, service.open(service.create(a, pass.copyOf()), pass.copyOf()).data)
        val opened = service.open(service.createHousehold(listOf(BackupMember(a), BackupMember(b)), pass.copyOf()), pass.copyOf())
        assertEquals(listOf(a, b), opened.members.map { it.data })
    }

    @Test
    fun restoreKeepsThemAndRestoreAsNewRemapsTheirIds() = runTest {
        val data = withRecords()
        val repository = InMemoryVezunaRepository()
        repository.restoreProfile(data)
        assertEquals(data.healthRecords, repository.readProfile(data.profile.profileId).healthRecords)

        var next = 0
        val copy = data.remappedAsNew { "new-${next++}" }
        assertTrue(copy.isInternallyConsistent())
        assertTrue(copy.healthRecords.none { it.id in data.healthRecords.map { old -> old.id } })
        assertTrue(copy.healthRecords.all { it.profileId == copy.profile.profileId })
        assertEquals(data.allergies.map { it.substance to it.reaction }, copy.allergies.map { it.substance to it.reaction })
    }

    private suspend fun backupSchemaOf(file: ByteArray): Int =
        Json.parseToJsonElement(PassphraseBackupCodec.open(file, pass.copyOf()).decodeToString()).jsonObject.getValue("schemaVersion").jsonPrimitive.int

    private suspend fun open(payload: String) = BackupService().open(PassphraseBackupCodec.seal(payload.encodeToByteArray(), pass.copyOf()), pass.copyOf())

    @Test
    fun backupsWithRecordsAreWrittenAsSchema5SoOlderBuildsSayUnsupported() = runTest(timeout = 5.minutes) {
        val service = BackupService()
        val plain = sampleProfileData("A")
        assertEquals(3, backupSchemaOf(service.create(plain, pass.copyOf())))
        assertEquals(4, backupSchemaOf(service.createHousehold(listOf(BackupMember(plain)), pass.copyOf())))
        val withRecords = withRecords(plain)
        assertEquals(5, backupSchemaOf(service.create(withRecords, pass.copyOf())))
        assertEquals(5, backupSchemaOf(service.createHousehold(listOf(BackupMember(plain), BackupMember(withRecords("B".let { sampleProfileData(it) }))), pass.copyOf())))
        val vault = plain.copy(vaultDocuments = listOf(app.vezuna.domain.VaultDocument(app.vezuna.domain.SourceDocument(plain.profile.profileId, "v1", "rx.pdf", "application/pdf", "h", at, "k"))))
        assertEquals(5, backupSchemaOf(service.create(vault, pass.copyOf())))
    }

    @Test
    fun olderSchemasStillLoadAndANewerOneIsRefusedAsUnsupportedNotDamaged() = runTest(timeout = 5.minutes) {
        val profile = """{"profileId":"p","displayName":"Ana","createdAt":"2026-03-10T12:00:00Z"}"""
        listOf(1, 2, 3).forEach { v ->
            assertEquals("p", open("""{"schemaVersion":$v,"profileId":"p","exportedAt":"2026-03-10T12:00:00Z","data":{"profile":$profile}}""").data.profile.profileId)
        }
        listOf(4, 5).forEach { v ->
            val opened = open("""{"schemaVersion":$v,"exportedAt":"2026-03-10T12:00:00Z","members":[{"data":{"profile":$profile}}]}""")
            assertEquals(listOf("p"), opened.members.map { it.data.profile.profileId })
        }
        val failure = assertFailsWith<VitaMessageException> {
            open("""{"schemaVersion":6,"exportedAt":"2026-03-10T12:00:00Z","members":[{"data":{"profile":$profile}}]}""")
        }
        assertEquals(L10nKey.BACKUP_UNSUPPORTED_SCHEMA, failure.userText.key)
    }

    @Test
    fun aBuildThatDoesNotKnowTheRecordKeysRefusesInsteadOfDroppingThem() = runTest {
        // Defence in depth for a schema-3 file carrying keys from the future: unknown keys fail the decode, never get dropped.
        val payload = """{"schemaVersion":3,"profileId":"p","exportedAt":"2026-03-10T12:00:00Z","data":{"profile":{"profileId":"p","displayName":"Ana","createdAt":"2026-03-10T12:00:00Z"},"recordsFromTheFuture":[{"x":1}]}}"""
        val failure = assertFailsWith<VitaMessageException> { open(payload) }
        assertEquals(L10nKey.BACKUP_INVALID_CONTENT, failure.userText.key)
    }

    @Test
    fun aVersion2VaultDocumentLoadsAndIsRestampedAsVersion3WithNoRecords() = runTest {
        val vault = GenerationalMemoryVault()
        val first = VaultBackedRepository(vault).also { it.load() }
        first.createProfile(Profile("a", "Ana", createdAt = at))
        // Rewrite every stored document the way a schema-2 build left it.
        vault.current.keys.toList().forEach { key ->
            val envelope = app.vezuna.security.CipherEnvelopeCodec.decode(vault.current.getValue(key))
            val plain = vault.decrypt(envelope).decodeToString()
            if ("\"schemaVersion\":3" in plain) {
                val old = vault.encrypt(plain.replace("\"schemaVersion\":3", "\"schemaVersion\":2").encodeToByteArray(), envelope.associatedData)
                vault.current[key] = app.vezuna.security.CipherEnvelopeCodec.encode(old)
            }
        }
        val reopened = VaultBackedRepository(vault).also { it.load() }
        assertTrue(reopened.readProfile("a").healthRecords.isEmpty())
        reopened.addHealthRecord(AllergyRecord("a", "a1", "Latex"))
        val stored = vault.current.getValue("repository-profile-a-v1")
        val text = vault.decrypt(app.vezuna.security.CipherEnvelopeCodec.decode(stored)).decodeToString()
        assertTrue("\"schemaVersion\":3" in text && "Latex" in text)
        assertEquals("Latex", VaultBackedRepository(vault).also { it.load() }.readProfile("a").allergies.single().substance)
    }

    @Test
    fun forgedRecordsAreRefused() = runTest {
        val base = sampleProfileData("A")
        val p = base.profile.profileId
        assertFalse(base.copy(allergies = listOf(AllergyRecord("other", "x1", "Latex"))).isInternallyConsistent(), "another profile's record")
        assertFalse(base.copy(allergies = listOf(AllergyRecord(p, "dup", "L")), vaccinations = listOf(VaccinationRecord(p, "dup", "V"))).isInternallyConsistent(), "shared id")
        assertFalse(base.copy(conditions = listOf(ConditionRecord(p, "../x", "C"))).isInternallyConsistent(), "hostile id")
        assertFailsWith<IllegalArgumentException> { AllergyRecord(p, "x", " ") }
        assertFailsWith<IllegalArgumentException> { PartialDate(2015, 2, 30) }
    }

    // ---- export, assistant, routes ----

    @Test
    fun sectionsAreSelectableAndVaccinationsFollowThePeriod() {
        val data = withRecords()
        assertTrue(ExportOptions().apply(data).healthRecords.isEmpty(), "off unless chosen")
        val all = ExportFamily.HEALTH_SUMMARY.defaults().apply(data)
        assertEquals(data.healthRecords.size, all.healthRecords.size)
        val bounded = ExportOptions(from = LocalDate(2026, 1, 1), to = LocalDate(2026, 12, 31), includeAllergies = true, includeVaccinations = true).apply(data)
        assertEquals(2, bounded.allergies.size)
        assertTrue(bounded.conditions.isEmpty())
        assertEquals(listOf("Influenza"), bounded.vaccinations.map { it.vaccine }, "out of period and undated are left out")
    }

    @Test
    fun aRecordOnlyProfileHasSomethingToExport() {
        val data = ProfileData(Profile("p", "Ana", createdAt = at), allergies = listOf(AllergyRecord("p", "a", "Latex")))
        assertFalse(ExportScope.hasContent(data, ExportOptions(), csv = false, zone = TimeZone.UTC))
        assertTrue(ExportScope.hasContent(data, ExportOptions(includeAllergies = true), csv = false, zone = TimeZone.UTC))
        assertTrue(ExportScope.hasContent(data, ExportOptions(includeAllergies = true), csv = true, zone = TimeZone.UTC))
    }

    @Test
    fun csvCarriesEachNonEmptyKindAsItsOwnBlockExactlyAsEntered() {
        val data = withRecords()
        val csv = CsvDoctorExporter().export(data, ContentLanguage.EN, ExportOptions(includeAllergies = true, includeConditions = true, includeVaccinations = true))
        assertTrue("substance,reaction,severity,notes\n\"Penicilina\"" in csv.replace("\r", "") || "\"Penicilina\",\"Urticária nos braços\",\"Severe\"" in csv, csv)
        assertTrue("\"Rhinitis\",\"2015-03\",\"Ongoing\",\"\"" in csv, csv)
        assertTrue("\"Migraine\",\"\",\"\",\"'=exact year unknown\"" in csv, "formula protection applies: $csv")
        assertTrue("\"Influenza\",\"2026-03-10\",\"1\",\"FLU-118\"" in csv, csv)
        assertTrue("\"Tetanus\",\"\",\"Booster\",\"\"" in csv, csv)
        val plain = CsvDoctorExporter().export(data, ContentLanguage.EN, ExportOptions())
        assertFalse("substance" in plain || "Penicilina" in plain)
        val onlyVaccines = CsvDoctorExporter().export(data, ContentLanguage.PT, ExportOptions(includeVaccinations = true))
        assertTrue("vacina,data,dose,lote" in onlyVaccines && "substancia" !in onlyVaccines)
    }

    /** The prompt's facts are records the user selected; there is no way to select these, so they stay out. */
    @Test
    fun theAssistantNeverReceivesThem() {
        val data = withRecords()
        val prompt = HealthPromptBuilder.build(data, data.observations.map { it.observationId }.toSet(), "What should I know?")
        data.healthRecords.forEach { assertFalse(it.title in prompt, it.title) }
    }

    @Test
    fun routesParse() {
        assertEquals(AppScreen.HealthRecords(HealthRecordKind.ALLERGY), routeFromName("HealthRecords:ALLERGY"))
        assertEquals(AppScreen.HealthRecordForm(HealthRecordKind.CONDITION), routeFromName("HealthRecordForm:CONDITION"))
        assertEquals(AppScreen.HealthRecordForm(HealthRecordKind.VACCINATION, "v1"), routeFromName("HealthRecordForm:VACCINATION:v1"))
        assertNull(routeFromName("HealthRecords:NOPE"))
    }
}
