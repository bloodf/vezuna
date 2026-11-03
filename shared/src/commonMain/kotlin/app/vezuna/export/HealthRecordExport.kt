package app.vezuna.export

import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.AllergySeverity
import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.PartialDate
import app.vezuna.domain.VaccinationRecord
import app.vezuna.localization.ContentLanguage
import kotlinx.datetime.LocalDate

/** A record as the export prints it: its name, then detail lines. Values appear exactly as entered; only labels are translated. */
internal data class HealthRecordBlock(val name: String, val details: List<String>)

/** Words of the allergy, condition and vaccination sections of a PDF and of their CSV blocks. */
internal data class HealthRecordText(
    val allergies: String,
    val conditions: String,
    val vaccinations: String,
    val note: String,
    /** Allergies and conditions are not events inside a period. */
    val notPeriodBound: String,
    val onlyInPeriod: String,
    val reaction: String,
    val severity: String,
    val mild: String,
    val moderate: String,
    val severe: String,
    val notes: String,
    val onset: String,
    val onsetUnknown: String,
    val status: String,
    val ongoing: String,
    val resolved: String,
    val date: String,
    val dateNotRecorded: String,
    val dose: String,
    val lot: String,
    val csvAllergies: String,
    val csvConditions: String,
    val csvVaccinations: String,
) {
    fun label(value: AllergySeverity) = when (value) {
        AllergySeverity.MILD -> mild
        AllergySeverity.MODERATE -> moderate
        AllergySeverity.SEVERE -> severe
    }

    fun label(value: ConditionStatus) = when (value) {
        ConditionStatus.ONGOING -> ongoing
        ConditionStatus.RESOLVED -> resolved
    }

    companion object {
        fun of(language: ContentLanguage) = when (language) {
            ContentLanguage.EN -> EN
            ContentLanguage.PT -> PT
            ContentLanguage.ES -> ES
        }

        private val EN = HealthRecordText(
            allergies = "Allergies and intolerances",
            conditions = "Conditions and history",
            vaccinations = "Vaccinations",
            note = "Entered by the person and printed as written. Not verified by Vezuna and not derived from laboratory results.",
            notPeriodBound = "Not limited to the selected period.",
            onlyInPeriod = "Only vaccinations dated inside the selected period are listed.",
            reaction = "Reaction",
            severity = "Severity, as entered",
            mild = "Mild",
            moderate = "Moderate",
            severe = "Severe",
            notes = "Notes",
            onset = "Onset",
            onsetUnknown = "Onset unknown",
            status = "Status, as entered",
            ongoing = "Ongoing",
            resolved = "Resolved",
            date = "Date",
            dateNotRecorded = "Date not recorded",
            dose = "Dose",
            lot = "Lot",
            csvAllergies = "substance,reaction,severity,notes",
            csvConditions = "condition,onset,status,notes",
            csvVaccinations = "vaccine,date,dose,lot",
        )

        private val PT = HealthRecordText(
            allergies = "Alergias e intolerâncias",
            conditions = "Condições e histórico",
            vaccinations = "Vacinas",
            note = "Informado pela própria pessoa e impresso como foi escrito. Não verificado pelo Vezuna e não derivado de resultados laboratoriais.",
            notPeriodBound = "Não limitado ao período selecionado.",
            onlyInPeriod = "Somente vacinas com data dentro do período selecionado são listadas.",
            reaction = "Reação",
            severity = "Gravidade, como informada",
            mild = "Leve",
            moderate = "Moderada",
            severe = "Grave",
            notes = "Observações",
            onset = "Início",
            onsetUnknown = "Início desconhecido",
            status = "Situação, como informada",
            ongoing = "Em curso",
            resolved = "Resolvida",
            date = "Data",
            dateNotRecorded = "Data não registrada",
            dose = "Dose",
            lot = "Lote",
            csvAllergies = "substancia,reacao,gravidade,observacoes",
            csvConditions = "condicao,inicio,situacao,observacoes",
            csvVaccinations = "vacina,data,dose,lote",
        )

        private val ES = HealthRecordText(
            allergies = "Alergias e intolerancias",
            conditions = "Afecciones e historial",
            vaccinations = "Vacunas",
            note = "Introducido por la propia persona e impreso tal como se escribió. No verificado por Vezuna y no derivado de resultados de laboratorio.",
            notPeriodBound = "No limitado al período seleccionado.",
            onlyInPeriod = "Solo se enumeran las vacunas con fecha dentro del período seleccionado.",
            reaction = "Reacción",
            severity = "Gravedad, según lo introducido",
            mild = "Leve",
            moderate = "Moderada",
            severe = "Grave",
            notes = "Notas",
            onset = "Inicio",
            onsetUnknown = "Inicio desconocido",
            status = "Estado, según lo introducido",
            ongoing = "En curso",
            resolved = "Resuelta",
            date = "Fecha",
            dateNotRecorded = "Fecha no registrada",
            dose = "Dosis",
            lot = "Lote",
            csvAllergies = "sustancia,reaccion,gravedad,notas",
            csvConditions = "afeccion,inicio,estado,notas",
            csvVaccinations = "vacuna,fecha,dosis,lote",
        )
    }
}

internal object HealthRecordExport {
    /** At the precision the owner gave, never more. */
    private fun onset(value: PartialDate, language: ContentLanguage): String = when {
        value.month == null -> value.year.toString()
        value.day == null -> "${ExportText.of(language).months[value.month - 1]} ${value.year}"
        else -> DoctorReportFormat.date(LocalDate(value.year, value.month, value.day), language)
    }

    private fun details(summary: List<String>, notes: String?, notesLabel: String) =
        listOfNotNull(summary.joinToString(" · ").ifEmpty { null }, notes?.let { "$notesLabel: $it" })

    fun block(record: AllergyRecord, language: ContentLanguage): HealthRecordBlock {
        val t = HealthRecordText.of(language)
        return HealthRecordBlock(
            record.substance,
            details(
                listOfNotNull(record.reaction?.let { "${t.reaction}: $it" }, record.severity?.let { "${t.severity}: ${t.label(it)}" }),
                record.notes, t.notes,
            ),
        )
    }

    fun block(record: ConditionRecord, language: ContentLanguage): HealthRecordBlock {
        val t = HealthRecordText.of(language)
        return HealthRecordBlock(
            record.name,
            details(
                listOfNotNull(record.onset?.let { "${t.onset}: ${onset(it, language)}" } ?: t.onsetUnknown, record.status?.let { "${t.status}: ${t.label(it)}" }),
                record.notes, t.notes,
            ),
        )
    }

    fun block(record: VaccinationRecord, language: ContentLanguage): HealthRecordBlock {
        val t = HealthRecordText.of(language)
        return HealthRecordBlock(
            record.vaccine,
            details(
                listOfNotNull(
                    record.date?.let { "${t.date}: ${DoctorReportFormat.date(it, language)}" } ?: t.dateNotRecorded,
                    record.doseNumber?.let { "${t.dose}: $it" },
                    record.lot?.let { "${t.lot}: $it" },
                ),
                null, t.notes,
            ),
        )
    }
}
