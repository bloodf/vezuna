package app.vezuna.export

import app.vezuna.localization.ContentLanguage

/** Words of the family titles and the summary, dose-history and device sections, next to [ExportText]. */
data class ExportSectionText(
    val healthSummary: String,
    val medicationRecord: String,
    val deviceMeasurements: String,
    val latestResults: String,
    val test: String,
    val latestResult: String,
    val comparison: String,
    val doseHistory: String,
    /** {taken} {skipped} {missed}: plain counts, never a share or a score. */
    val doseCounts: String,
    val missedNote: String,
    val noDoses: String,
    val scheduled: String,
    val medication: String,
    val status: String,
    val taken: String,
    val skipped: String,
    val missed: String,
    /** {time}: local time the action was recorded. */
    val recordedAt: String,
    val deviceNote: String,
    /** {date}: last import from the device health app. */
    val lastImport: String,
    val noDeviceData: String,
    val steps: String,
    val heartRate: String,
    val weight: String,
    val value: String,
    val time: String,
    val stepsUnit: String,
) {
    fun title(family: ExportFamily, language: ContentLanguage): String = when (family) {
        ExportFamily.HEALTH_SUMMARY -> healthSummary
        ExportFamily.LAB_HISTORY -> ExportText.of(language).title
        ExportFamily.MEDICATION_RECORD -> medicationRecord
        ExportFamily.DEVICE_MEASUREMENTS -> deviceMeasurements
    }

    companion object {
        fun of(language: ContentLanguage): ExportSectionText = when (language) {
            ContentLanguage.EN -> EN
            ContentLanguage.PT -> PT
            ContentLanguage.ES -> ES
        }

        /** The document title of [family] in [language]. */
        fun titleOf(family: ExportFamily, language: ContentLanguage) = of(language).title(family, language)

        private val EN = ExportSectionText(
            healthSummary = "Health summary",
            medicationRecord = "Medication record",
            deviceMeasurements = "Device measurements",
            latestResults = "Latest results",
            test = "Test",
            latestResult = "Latest result",
            comparison = "Comparison with reference",
            doseHistory = "Dose history",
            doseCounts = "Taken: {taken} · Skipped: {skipped} · Missed: {missed}",
            missedNote = "Missed means no dose was recorded within 24 hours of its time; it is not proof that the dose was not taken.",
            noDoses = "No doses recorded in this period.",
            scheduled = "Scheduled",
            medication = "Medication",
            status = "Status",
            taken = "Taken",
            skipped = "Skipped",
            missed = "Missed",
            recordedAt = "recorded {time}",
            deviceNote = "Daily summaries imported from the device health app. Heart rate is the daily average, not a resting heart rate.",
            lastImport = "Last import: {date}",
            noDeviceData = "No device measurements in this period.",
            steps = "Steps (daily total)",
            heartRate = "Heart rate (daily average)",
            weight = "Weight",
            value = "Value",
            time = "Time",
            stepsUnit = "steps",
        )

        private val PT = ExportSectionText(
            healthSummary = "Resumo de saúde",
            medicationRecord = "Registro de medicamentos",
            deviceMeasurements = "Medições do dispositivo",
            latestResults = "Resultados mais recentes",
            test = "Exame",
            latestResult = "Resultado mais recente",
            comparison = "Comparação com a referência",
            doseHistory = "Histórico de doses",
            doseCounts = "Tomadas: {taken} · Puladas: {skipped} · Perdidas: {missed}",
            missedNote = "Perdida significa que nenhuma dose foi registrada até 24 horas após o horário; não prova que a dose não foi tomada.",
            noDoses = "Nenhuma dose registrada neste período.",
            scheduled = "Horário",
            medication = "Medicamento",
            status = "Situação",
            taken = "Tomada",
            skipped = "Pulada",
            missed = "Perdida",
            recordedAt = "registrada {time}",
            deviceNote = "Resumos diários importados do app de saúde do dispositivo. A frequência cardíaca é a média diária, não a frequência em repouso.",
            lastImport = "Última importação: {date}",
            noDeviceData = "Nenhuma medição do dispositivo neste período.",
            steps = "Passos (total diário)",
            heartRate = "Frequência cardíaca (média diária)",
            weight = "Peso",
            value = "Valor",
            time = "Hora",
            stepsUnit = "passos",
        )

        private val ES = ExportSectionText(
            healthSummary = "Resumen de salud",
            medicationRecord = "Registro de medicamentos",
            deviceMeasurements = "Mediciones del dispositivo",
            latestResults = "Resultados más recientes",
            test = "Prueba",
            latestResult = "Resultado más reciente",
            comparison = "Comparación con la referencia",
            doseHistory = "Historial de dosis",
            doseCounts = "Tomadas: {taken} · Omitidas: {skipped} · Perdidas: {missed}",
            missedNote = "Perdida significa que no se registró ninguna dosis en las 24 horas siguientes a su hora; no prueba que no se tomara.",
            noDoses = "No hay dosis registradas en este período.",
            scheduled = "Hora prevista",
            medication = "Medicamento",
            status = "Estado",
            taken = "Tomada",
            skipped = "Omitida",
            missed = "Perdida",
            recordedAt = "registrada {time}",
            deviceNote = "Resúmenes diarios importados de la app de salud del dispositivo. La frecuencia cardiaca es el promedio diario, no la frecuencia en reposo.",
            lastImport = "Última importación: {date}",
            noDeviceData = "No hay mediciones del dispositivo en este período.",
            steps = "Pasos (total diario)",
            heartRate = "Frecuencia cardiaca (promedio diario)",
            weight = "Peso",
            value = "Valor",
            time = "Hora",
            stepsUnit = "pasos",
        )
    }
}
