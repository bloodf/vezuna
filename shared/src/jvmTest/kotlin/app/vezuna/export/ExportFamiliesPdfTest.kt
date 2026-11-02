package app.vezuna.export

import app.vezuna.localization.ContentLanguage
import app.vezuna.ui.DesignFixture
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The four hub families rendered from the redesign fixture (Ana Silva): titles, sections and their text. */
class ExportFamiliesPdfTest {
    private val zone = TimeZone.of("America/Sao_Paulo")

    private fun document(family: ExportFamily, language: ContentLanguage = ContentLanguage.EN, options: ExportOptions = family.defaults()) =
        DoctorExportContent.document(DesignFixture.data(), ExportSectionText.titleOf(family, language), language, options.copy(family = family), zone)
            .copy(generatedAt = PdfFixtures.generatedAt)

    private fun text(document: DoctorPdfDocument) = PdfFixtures.pageTexts(PdfFixtures.pdfBytes(document)).joinToString(" ").replace(Regex("\\s+"), " ")

    @Test
    fun healthSummaryIsALatestResultsTableWithMedications() {
        val document = document(ExportFamily.HEALTH_SUMMARY)
        val text = text(document)
        listOf(
            "Health summary", "Ana Silva", "Latest results", "Test", "Latest result", "Comparison with reference",
            "13.2 g/dL", "132 mg/dL", "2.1 mIU/L", "Not detected", "Above report range", "Within report range",
            "Confirmed results included: 8 · Tests: 4", "Unreviewed imported values excluded: 3", "Vitamin D",
        ).forEach { assertTrue(it in text, "missing: $it") }
        assertFalse("12.8 g/dL" in text, "the summary shows the latest value only")
        assertFalse("Dose history" in text || "Device measurements" in text, "sections the family did not select stay out")
        assertEquals("vezuna-ana-silva-health-summary-2026-11-01.pdf", DoctorExportContent.fileName(document))
    }

    @Test
    fun medicationRecordPrintsEveryRecordedDoseWithCountsAndNoPercentage() {
        val document = document(ExportFamily.MEDICATION_RECORD)
        val text = text(document)
        listOf(
            "Medication record", "Vitamin D", "Strength: 1,000 IU", "Dose history",
            "Taken: 5 · Skipped: 1 · Missed: 1", "Missed means no dose was recorded", "25 Oct 2026", "31 Oct 2026",
            "08:00", "recorded 08:03", "Skipped", "Missed",
        ).forEach { assertTrue(it in text, "missing: $it") }
        assertFalse("%" in text, "dose history is descriptive: no adherence percentage")
        assertFalse("Laboratory results" in text || "13.2 g/dL" in text, "labs are not part of the medication record by default")
        assertEquals(7, document.doseRows.size)
        assertEquals(null, document.doseRows.first { it.status == app.vezuna.domain.DoseEventStatus.MISSED }.recordedTime)
        assertTrue(DoctorExportContent.fileName(document).endsWith("medication-record-2026-11-01.pdf"))
    }

    @Test
    fun deviceMeasurementsListsDailyStepsAverageHeartRateAndOnlyRecordedWeights() {
        val document = document(ExportFamily.DEVICE_MEASUREMENTS)
        val text = text(document)
        listOf(
            "Device measurements", "Steps (daily total)", "6240 steps", "5870 steps", "Heart rate (daily average)", "72 bpm",
            "not a resting heart rate", "Weight", "68.4 kg", "68.8 kg", "Last import: 01 Nov 2026",
        ).forEach { assertTrue(it in text, "missing: $it") }
        assertEquals(3, document.deviceRows.count { it.metric == app.vezuna.domain.VitalMetric.WEIGHT_KG }, "no weight on days without a reading")
        assertFalse("Recorded medication" in text, "a device export leaves medications out")
        assertFalse("Collection period" in text, "no lab collection period without labs")
    }

    @Test
    fun dosesAndDeviceRowsFollowThePeriodAndSayWhenNothingIsInIt() {
        val march = ExportOptions(from = LocalDate(2026, 3, 1), to = LocalDate(2026, 3, 31))
        val text = text(document(ExportFamily.MEDICATION_RECORD, options = ExportFamily.MEDICATION_RECORD.defaults().copy(from = march.from, to = march.to, includeDeviceData = true)))
        assertTrue("No doses recorded in this period." in text)
        assertTrue("No device measurements in this period." in text)
        val oneDay = ExportFamily.DEVICE_MEASUREMENTS.defaults().copy(from = LocalDate(2026, 10, 28), to = LocalDate(2026, 10, 28))
        val rows = DoctorExportContent.deviceRows(DesignFixture.data(), oneDay, zone)
        assertEquals(setOf(LocalDate(2026, 10, 28)), rows.map { it.date }.toSet())
    }

    @Test
    fun portugueseFamilyTitlesAndDoseLabels() {
        val text = text(document(ExportFamily.MEDICATION_RECORD, ContentLanguage.PT))
        listOf("Registro de medicamentos", "Histórico de doses", "Tomadas: 5 · Puladas: 1 · Perdidas: 1", "Tomada", "Pulada", "Perdida").forEach {
            assertTrue(it in text, "missing: $it")
        }
    }

    @Test
    fun eachFamilyStartsFromItsOwnSections() {
        assertTrue(ExportFamily.HEALTH_SUMMARY.defaults().let { it.includeLabsPdf && it.includeMedicationsPdf && !it.includeDoseHistory })
        assertTrue(ExportFamily.MEDICATION_RECORD.defaults().let { !it.includeLabsPdf && it.includeMedicationsPdf && it.includeDoseHistory })
        assertTrue(ExportFamily.DEVICE_MEASUREMENTS.defaults().let { !it.includeLabsPdf && !it.includeMedicationsPdf && it.includeDeviceData })
        assertEquals("vezuna-ana-silva-lab-results-2026-11-01.csv", DoctorExportContent.csvFileName("Ana Silva", PdfFixtures.generatedAt))
    }
}

/**
 * Writes the hub-family samples from the redesign fixture when VEZUNA_PDF_SAMPLES_DIR is set (lane L9 evidence,
 * compared against images/pdf). A no-op in normal runs. Synthetic data only.
 */
class ExportFamiliesSampleWriterTest {
    @Test
    fun writeFamilySamples() {
        val directory = System.getenv("VEZUNA_PDF_SAMPLES_DIR")?.let(::File) ?: return
        directory.mkdirs()
        val zone = TimeZone.of("America/Sao_Paulo")
        val data = DesignFixture.data()
        fun doc(family: ExportFamily, language: ContentLanguage = ContentLanguage.EN, options: ExportOptions = family.defaults()) =
            DoctorExportContent.document(data, ExportSectionText.titleOf(family, language), language, options.copy(family = family), zone)
                .copy(generatedAt = PdfFixtures.generatedAt)
        mapOf(
            "pdf-01-health-summary.pdf" to doc(ExportFamily.HEALTH_SUMMARY),
            "pdf-02-03-lab-history.pdf" to doc(ExportFamily.LAB_HISTORY),
            "pdf-04-single-test.pdf" to doc(ExportFamily.LAB_HISTORY, options = ExportFamily.LAB_HISTORY.defaults().copy(testCodes = setOf("HGB"))),
            "pdf-05-one-collection-date.pdf" to doc(ExportFamily.LAB_HISTORY, options = ExportFamily.LAB_HISTORY.defaults().copy(from = LocalDate(2026, 10, 28), to = LocalDate(2026, 10, 28))),
            "pdf-06-07-medication-record-dose-history.pdf" to doc(ExportFamily.MEDICATION_RECORD, options = ExportFamily.MEDICATION_RECORD.defaults().copy(from = LocalDate(2026, 10, 25), to = LocalDate(2026, 10, 31))),
            "pdf-08-device-measurements.pdf" to doc(ExportFamily.DEVICE_MEASUREMENTS, options = ExportFamily.DEVICE_MEASUREMENTS.defaults().copy(from = LocalDate(2026, 10, 25), to = LocalDate(2026, 10, 31))),
            "pdf-10-sparse-period.pdf" to doc(ExportFamily.HEALTH_SUMMARY, options = ExportFamily.HEALTH_SUMMARY.defaults().copy(from = LocalDate(2026, 3, 1), to = LocalDate(2026, 3, 31), includeMedicationsPdf = false)),
            "pdf-11-portuguese-history.pdf" to doc(ExportFamily.LAB_HISTORY, ContentLanguage.PT),
            "pdf-12-letter-grayscale.pdf" to doc(ExportFamily.LAB_HISTORY, options = ExportFamily.LAB_HISTORY.defaults(PaperSize.LETTER).copy(grayscale = true)),
            "es-health-summary.pdf" to doc(ExportFamily.HEALTH_SUMMARY, ContentLanguage.ES),
        ).forEach { (name, document) -> File(directory, name).writeBytes(PdfFixtures.pdfBytes(document)) }
    }
}
