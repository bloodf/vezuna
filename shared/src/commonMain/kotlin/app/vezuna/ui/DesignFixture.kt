package app.vezuna.ui

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.AllergySeverity
import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.HeartRateDay
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.PartialDate
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.Recurrence
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VaccinationRecord
import app.vezuna.domain.WeightReading
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * The synthetic household from docs/redesign/specs/DATA_AND_COPY.md, for screenshots and design checks only.
 * Debug builds load it into an in-memory repository when launched with the fixture flag (Android intent extra
 * `fixture`, iOS launch argument `-VezunaFixture`). Fixture screens never read or write the encrypted vault or
 * model files, and release builds never call it.
 */
object DesignFixture {
    const val PROFILE_ID = "demo-ana"
    private const val P = PROFILE_ID

    /** Local 08:00 in America/Sao_Paulo (UTC-03:00) on [date]. */
    private fun at8(date: String): Instant = Instant.parse("${date}T11:00:00Z")

    private fun at(text: String): Instant = Instant.parse(text)

    private data class Report(val id: String, val file: String, val date: String)

    private val reports = listOf(
        Report("report-apr", "april-blood-work-2026.pdf", "2026-04-12"),
        Report("report-jul", "july-blood-work-2026.pdf", "2026-07-15"),
        Report("report-lipid-oct", "lipid-panel-2026-10-14.pdf", "2026-10-14"),
        Report("report-cbc-oct", "blood-count-2026-10-28.pdf", "2026-10-28"),
        Report("report-thyroid-oct", "thyroid-function-2026-10-28.pdf", "2026-10-28"),
        Report("report-hbsag-oct", "hepatitis-b-screening-2026-10-28.pdf", "2026-10-28"),
    )

    private fun numeric(id: String, report: String, date: String, code: String, name: String, value: Double, unit: String, normalized: Double, normalizedUnit: String, range: ReferenceRange, decimals: Int) =
        LabObservation(P, id, report, code, name, value, unit, normalized, normalizedUnit, range, at8(date), ConfirmationStatus.CONFIRMED, valueDecimals = decimals)

    fun data(): ProfileData {
        val hgbRange = ReferenceRange(12.0, 16.0, unit = "g/dL")
        val ldlRange = ReferenceRange(upper = 130.0, text = "Under 130 mg/dL", unit = "mg/dL")
        val reviewDocument = SourceDocument(P, "doc-october-review", "october-blood-work-review.pdf", "application/pdf", "fixture-october-review", at("2026-11-01T12:10:00Z"))
        val schedule = DoseSchedule(P, "schedule-vitamin-d", "med-vitamin-d", Recurrence.DAILY, startDate = LocalDate(2026, 4, 1), timesLocal = listOf("08:00"))
        val doseHistory = listOf(
            "25" to DoseEventStatus.TAKEN to "08:03", "26" to DoseEventStatus.TAKEN to "08:06", "27" to DoseEventStatus.SKIPPED to "08:10",
            "28" to DoseEventStatus.TAKEN to "08:02", "29" to DoseEventStatus.MISSED to null, "30" to DoseEventStatus.TAKEN to "08:04",
            "31" to DoseEventStatus.TAKEN to "08:05",
        )
        return ProfileData(
            profile = Profile(P, "Ana Silva", birthDate = LocalDate(1988, 5, 18), createdAt = at8("2026-04-01")),
            documents = reports.map { SourceDocument(P, "doc-${it.id}", it.file, "application/pdf", "fixture-${it.id}", at("${it.date}T21:00:00Z")) } + reviewDocument,
            reports = reports.map { LabReport(P, it.id, "doc-${it.id}", laboratoryName = "Example Laboratory", importedAt = at("${it.date}T21:00:00Z")) },
            observations = listOf(
                numeric("hgb-2026-04", "report-apr", "2026-04-12", "HGB", "Hemoglobin", 13.0, "g/dL", 130.0, "g/L", hgbRange, 1),
                numeric("hgb-2026-07", "report-jul", "2026-07-15", "HGB", "Hemoglobin", 12.8, "g/dL", 128.0, "g/L", hgbRange, 1),
                numeric("hgb-2026-10", "report-cbc-oct", "2026-10-28", "HGB", "Hemoglobin", 13.2, "g/dL", 132.0, "g/L", hgbRange, 1),
                numeric("ldl-2026-04", "report-apr", "2026-04-12", "LDL", "LDL cholesterol", 146.0, "mg/dL", 1460.0, "mg/L", ldlRange, 0),
                numeric("ldl-2026-07", "report-jul", "2026-07-15", "LDL", "LDL cholesterol", 140.0, "mg/dL", 1400.0, "mg/L", ldlRange, 0),
                numeric("ldl-2026-10", "report-lipid-oct", "2026-10-14", "LDL", "LDL cholesterol", 132.0, "mg/dL", 1320.0, "mg/L", ldlRange, 0),
                numeric("tsh-2026-10", "report-thyroid-oct", "2026-10-28", "TSH", "TSH", 2.1, "mIU/L", 2.1, "mIU/L", ReferenceRange(0.4, 4.0, unit = "mIU/L"), 1),
                LabObservation(
                    P, "hbsag-2026-10", "report-hbsag-oct", "HBsAg", "Hepatitis B surface antigen", 0.0, "", 0.0, "",
                    ReferenceRange(text = "Not detected"), at8("2026-10-28"), ConfirmationStatus.CONFIRMED, qualitativeValue = "Not detected",
                ),
            ),
            ocrCandidates = listOf(
                Triple("GLU", "Fasting glucose", Triple("96", "mg/dL", ReferenceRange(70.0, 99.0, unit = "mg/dL"))),
                Triple("FERR", "Ferritin", Triple("42", "ng/mL", ReferenceRange(15.0, 150.0, unit = "ng/mL"))),
                Triple("VITD", "Vitamin D", Triple("31", "ng/mL", ReferenceRange(20.0, 50.0, unit = "ng/mL"))),
            ).map { (code, name, value) ->
                OcrCandidate(P, "candidate-${code.lowercase()}", reviewDocument.documentId, code, name, value.first, value.second, value.third, at8("2026-10-30"), collectedAtKnown = true)
            },
            medications = listOf(Medication(P, "med-vitamin-d", "Vitamin D", strength = "1,000 IU", instructions = "1 capsule", schedules = listOf(schedule))),
            doseEvents = doseHistory.map { (dayStatus, time) ->
                val (day, status) = dayStatus
                DoseEvent(
                    P, "dose-2026-10-$day", "med-vitamin-d", schedule.scheduleId, at8("2026-10-$day"), status,
                    recordedAt = time?.let { at("2026-10-${day}T${(it.substringBefore(':').toInt() + 3).toString().padStart(2, '0')}:${it.substringAfter(':')}:00Z") },
                )
            },
            vitals = vitals(),
            allergies = listOf(
                AllergyRecord(P, "allergy-penicillin", "Penicillin", reaction = "Hives on both arms", severity = AllergySeverity.MODERATE),
                AllergyRecord(P, "allergy-lactose", "Lactose", reaction = "Bloating after milk", notes = "Yogurt is fine"),
            ),
            conditions = listOf(
                ConditionRecord(P, "condition-rhinitis", "Seasonal rhinitis", onset = PartialDate(2015), status = ConditionStatus.ONGOING),
                ConditionRecord(P, "condition-appendectomy", "Appendectomy", onset = PartialDate(2012, 6), status = ConditionStatus.RESOLVED),
                ConditionRecord(P, "condition-migraine", "Migraine", notes = "Started in my teens, exact year unknown"),
            ),
            vaccinations = listOf(
                VaccinationRecord(P, "vaccination-flu", "Influenza 2026", at("2026-04-20T12:00:00Z"), doseNumber = "1", lot = "FLU-2026-118"),
                VaccinationRecord(P, "vaccination-hepb", "Hepatitis B", at("2019-03-02T12:00:00Z"), doseNumber = "3"),
                VaccinationRecord(P, "vaccination-tetanus", "Tetanus", doseNumber = "Booster"),
            ),
        )
    }

    /** Seven daily summaries: steps, daily average heart rate and three weights (gaps stay gaps). */
    private fun vitals(): ProfileVitals {
        val days = (25..31).map { LocalDate(2026, 10, it) }
        val steps = listOf(5870L, 7120, 6845, 5930, 7480, 6100, 6240)
        val heart = listOf(73.0, 72.0, 71.0, 74.0, 72.0, 73.0, 72.0)
        return ProfileVitals(
            steps = days.zip(steps) { day, count -> StepsDay(day, count) },
            heartRateDaily = days.zip(heart) { day, avg -> HeartRateDay(day, avg - 12, avg, avg + 30, 24) },
            weights = listOf("25" to 68.8, "28" to 68.6, "31" to 68.4).map { (day, kg) -> WeightReading("weight-2026-10-$day", at("2026-10-${day}T10:30:00Z"), kg) },
            lastSync = at("2026-11-01T12:35:00Z"),
        )
    }

    /** Model storage for the fixture: empty, and nothing is ever written, so real model files stay untouched. */
    object NoModelFiles : app.vezuna.llm.ModelFileStore {
        override suspend fun installed(): List<app.vezuna.llm.InstalledModel> = emptyList()
        override suspend fun install(model: app.vezuna.llm.InstalledModel, sourcePath: String) = Unit
        override suspend fun verifyIntegrity(model: app.vezuna.llm.InstalledModel): Boolean = false
        override suspend fun delete(model: app.vezuna.llm.InstalledModel) = Unit
        override suspend fun saveSelection(modelId: String?) = Unit
        override suspend fun savedSelection(): String? = null
    }

    /** Loads the household into [repository], which must be a throwaway in-memory repository. */
    suspend fun install(repository: VezunaRepository) {
        val data = data()
        repository.restoreProfile(data.copy(vitals = ProfileVitals()))
        repository.replaceVitals(PROFILE_ID, data.vitals)
        repository.setLinkedHealthProfile(PROFILE_ID)
    }
}
