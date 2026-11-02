package app.vezuna.ui

import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.ExportFamily
import app.vezuna.export.ExportOptions
import app.vezuna.export.PaperSize
import app.vezuna.ui.export.CSV_KIND
import app.vezuna.ui.export.CsvPreview
import app.vezuna.ui.export.ExportDraft
import app.vezuna.ui.export.ExportPeriod
import app.vezuna.ui.export.ExportScope
import app.vezuna.ui.export.ExportSession
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExportDraftTest {
    private val today = LocalDate(2026, 11, 1)
    private val data = DesignFixture.data()

    @Test
    fun familyDraftStartsFromItsSectionsAndBuildsOptions() {
        val draft = ExportDraft("p", ExportFamily.MEDICATION_RECORD.name, PaperSize.LETTER)
        val options = draft.options(today)!!
        assertEquals(ExportFamily.MEDICATION_RECORD, options.family)
        assertFalse(options.includeLabsPdf)
        assertTrue(options.includeDoseHistory)
        assertEquals(PaperSize.LETTER, options.paper)
        assertEquals(null to null, draft.bounds(today))
    }

    @Test
    fun periodsAndCustomDatesValidateBeforeAnyExport() {
        val draft = ExportDraft("p", CSV_KIND, PaperSize.A4)
        draft.period = ExportPeriod.LAST_3_MONTHS
        assertEquals(LocalDate(2026, 8, 1) to today, draft.bounds(today))
        draft.period = ExportPeriod.CUSTOM
        draft.fromText = "2026-13-01"
        assertTrue(draft.fromInvalid)
        assertNull(draft.options(today))
        draft.fromText = "2026-10-31"
        draft.toText = "2026-10-01"
        assertTrue(draft.reversed)
        assertNull(draft.options(today))
        draft.toText = "2026-11-01"
        assertEquals(LocalDate(2026, 10, 31), draft.options(today)!!.from)
    }

    @Test
    fun sessionKeepsOneDraftPerProfileAndKind() {
        val first = ExportSession.draft("ana", CSV_KIND, PaperSize.A4)
        assertSame(first, ExportSession.draft("ana", CSV_KIND, PaperSize.A4))
        assertNotSame(first, ExportSession.draft("leo", CSV_KIND, PaperSize.A4), "another profile never sees this draft")
        assertNull(ExportSession.existing("ana", CSV_KIND))
    }

    @Test
    fun scopeCountsConfirmedOnlyAndKnowsWhenNothingWouldPrint() {
        val zone = TimeZone.of("America/Sao_Paulo")
        assertEquals(8, ExportScope.confirmedCount(data, ExportOptions()))
        assertEquals(0, ExportScope.confirmedCount(data, ExportOptions(includeLabsPdf = false)))
        assertEquals(4, ExportScope.tests(data).size)
        assertTrue(ExportScope.hasContent(data, ExportFamily.DEVICE_MEASUREMENTS.defaults(), csv = false, zone = zone))
        val march = ExportOptions(from = LocalDate(2026, 3, 1), to = LocalDate(2026, 3, 31))
        assertFalse(ExportScope.hasContent(data, march.copy(includeMedicationsPdf = false), csv = false, zone = zone))
        assertFalse(ExportScope.hasContent(data, march, csv = true, zone = zone))
        assertTrue(ExportScope.hasContent(data, march, csv = false, zone = zone), "the medication still prints")
    }

    @Test
    fun csvPreviewReadsBackTheExactExportedText() {
        val csv = CsvDoctorExporter().export(data, options = ExportOptions(includeMedicationsCsv = true))
        val rows = CsvPreview.rows(csv)
        assertEquals(8, rows.size, "medication block is not a lab row")
        assertTrue(rows.any { it.test == "Hemoglobin" && it.value == "13.2" && it.unit == "g/dL" })
        assertTrue(rows.any { it.value == "Not detected" && it.unit == "" })
        assertEquals(listOf("a,b", "say \"hi\"", ""), CsvPreview.fields("\"a,b\",\"say \"\"hi\"\"\",\"\""))
    }
}
