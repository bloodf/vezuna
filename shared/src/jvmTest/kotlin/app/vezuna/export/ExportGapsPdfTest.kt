package app.vezuna.export

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.localization.ContentLanguage
import app.vezuna.ui.DesignFixture
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** pdf-01/06/07/08/09 gaps: summary graphs, schedule strip, dose timeline, device graphs and the source appendix. */
class ExportGapsPdfTest {
    private val zone = TimeZone.of("America/Sao_Paulo")
    private val week = Pair(LocalDate(2026, 10, 25), LocalDate(2026, 10, 31))

    private fun document(
        family: ExportFamily,
        language: ContentLanguage = ContentLanguage.EN,
        options: ExportOptions = family.defaults(),
        data: ProfileData = DesignFixture.data(),
    ) = DoctorExportContent.document(data, ExportSectionText.titleOf(family, language), language, options.copy(family = family), zone, PdfFixtures.generatedAt)

    private fun weekOf(family: ExportFamily, paper: PaperSize = PaperSize.A4) = family.defaults(paper).copy(from = week.first, to = week.second)

    private fun text(bytes: ByteArray) = PdfFixtures.pageTexts(bytes).joinToString(" ").replace(Regex("\\s+"), " ")

    private fun pages(bytes: ByteArray) = PdfFixtures.open(bytes) { it.numberOfPages }

    @Test
    fun healthSummaryDrawsEachMultiDateTestWithFirstAndPreviousChanges() {
        val bytes = PdfFixtures.pdfBytes(document(ExportFamily.HEALTH_SUMMARY))
        val text = text(bytes)
        listOf(
            "Hemoglobin g/dL", "LDL cholesterol mg/dL", "13.0", "12.8", "146", "140", "12 Apr 2026", "15 Jul 2026",
            "Change since first result +0.2 g/dL (from 12 Apr 2026)", "Change since previous result +0.4 g/dL (from 15 Jul 2026)",
            "-14 mg/dL (from 12 Apr 2026)", "-8 mg/dL (from 15 Jul 2026)", "Dots are recorded results", "Latest results",
        ).forEach { assertTrue(it in text, "missing: $it") }
        assertFalse("%" in text)
        assertEquals(2, pages(bytes), "charts plus the full latest-results table paginate instead of shrinking")
    }

    @Test
    fun medicationRecordPrintsEndDateAndASevenDayScheduleStripSeparateFromDoses() {
        val document = document(ExportFamily.MEDICATION_RECORD, options = weekOf(ExportFamily.MEDICATION_RECORD))
        val text = text(PdfFixtures.pdfBytes(document))
        listOf(
            "No end date", "Schedule for the next seven days", "Planned times from the schedule, not dose history.",
            "Sun 01 Nov", "Mon 02 Nov", "Sat 07 Nov",
        ).forEach { assertTrue(it in text, "missing: $it") }
        val row = document.medicationRows.single()
        assertEquals((1..7).map { LocalDate(2026, 11, it) }, row.week.map { it.date })
        assertTrue(row.week.all { it.times == listOf("08:00") })
    }

    @Test
    fun scheduleStripFollowsRecurrenceAndEndDateAndSkipsInactiveMedications() {
        val base = DesignFixture.data()
        val weekly = DoseSchedule(base.profile.profileId, "weekly", "weekly-med", Recurrence.WEEKLY, daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), startDate = LocalDate(2026, 10, 1), endDate = LocalDate(2026, 11, 5), timesLocal = listOf("21:00", "09:00"))
        val data = base.copy(
            medications = base.medications + Medication(base.profile.profileId, "weekly-med", "Weekly tablet", schedules = listOf(weekly)) +
                Medication(base.profile.profileId, "old", "Old cream", schedules = listOf(weekly.copy(scheduleId = "old-s", medicationId = "old")), active = false),
        )
        val document = document(ExportFamily.MEDICATION_RECORD, data = data)
        val weeklyRow = document.medicationRows.first { it.displayName == "Weekly tablet" }
        assertEquals(LocalDate(2026, 11, 5), weeklyRow.endDate)
        assertEquals(listOf("09:00", "21:00"), weeklyRow.week.first { it.date == LocalDate(2026, 11, 2) }.times)
        assertEquals(emptyList(), weeklyRow.week.first { it.date == LocalDate(2026, 11, 6) }.times, "past the end date")
        assertTrue(document.medicationRows.first { it.displayName == "Old cream" }.week.isEmpty())
        val text = text(PdfFixtures.pdfBytes(document))
        assertTrue("End date: 05 Nov 2026" in text && "–" in text)
    }

    @Test
    fun doseTimelineShowsEachDayCategoricallyWithoutPercentages() {
        val bytes = PdfFixtures.pdfBytes(document(ExportFamily.MEDICATION_RECORD, options = weekOf(ExportFamily.MEDICATION_RECORD)))
        val text = text(bytes)
        val timeline = text.substringAfter("Daily dose timeline").substringBefore("Date Scheduled")
        assertEquals(
            "25 Oct Taken 26 Oct Taken 27 Oct Skipped 28 Oct Taken 29 Oct Missed 30 Oct Taken 31 Oct Taken",
            timeline.trim(),
        )
        assertFalse("%" in text || "adherence" in text.lowercase())
    }

    @Test
    fun doseTimelineWrapsWeeksStacksSeveralDosesAndSeparatesMedications() {
        val base = DesignFixture.data()
        val evening = DoseSchedule(base.profile.profileId, "iron-s", "iron", Recurrence.DAILY, startDate = LocalDate(2026, 10, 1), timesLocal = listOf("08:00", "20:00"))
        fun event(day: Int, hourUtc: Int, status: DoseEventStatus) = DoseEvent(
            base.profile.profileId, "iron-$day-$hourUtc", "iron", "iron-s", Instant.parse("2026-10-${day.toString().padStart(2, '0')}T${hourUtc.toString().padStart(2, '0')}:00:00Z"), status,
            recordedAt = if (status == DoseEventStatus.MISSED) null else Instant.parse("2026-10-${day.toString().padStart(2, '0')}T${hourUtc.toString().padStart(2, '0')}:05:00Z"),
        )
        val data = base.copy(
            medications = base.medications + Medication(base.profile.profileId, "iron", "Iron", schedules = listOf(evening)),
            doseEvents = base.doseEvents + listOf(event(20, 11, DoseEventStatus.TAKEN), event(20, 23, DoseEventStatus.SKIPPED), event(31, 23, DoseEventStatus.MISSED)),
        )
        val bytes = PdfFixtures.pdfBytes(document(ExportFamily.MEDICATION_RECORD, data = data))
        val text = text(bytes)
        listOf("Iron", "20 Oct 08:00 Taken 20:00 Skipped", "21 Oct –", "26 Oct", "31 Oct Missed").forEach { assertTrue(it in text, "missing: $it") }
        assertTrue(text.indexOf("Iron") < text.indexOf("Vitamin D"), "one timeline per medication, sorted with the doses")
    }

    @Test
    fun deviceMeasurementsDrawGraphsWithLatestValueAndBaselineDatedChanges() {
        val bytes = PdfFixtures.pdfBytes(document(ExportFamily.DEVICE_MEASUREMENTS, options = weekOf(ExportFamily.DEVICE_MEASUREMENTS)))
        val text = text(bytes)
        listOf(
            "Days with device data in this export: 7", "Latest: 6240 steps (31 Oct 2026)", "+370 steps (from 25 Oct 2026)", "+140 steps (from 30 Oct 2026)",
            "Latest: 72 bpm (31 Oct 2026)", "-1 bpm (from 25 Oct 2026)", "-1 bpm (from 30 Oct 2026)",
            "Latest: 68.4 kg (31 Oct 2026)", "-0.4 kg (from 25 Oct 2026)", "-0.2 kg (from 28 Oct 2026)",
            "5870 7120 6845 5930 7480 6100 6240", "25 Oct 26 Oct 27 Oct", "68.8", "68.6",
        ).forEach { assertTrue(it in text, "missing: $it") }
        assertFalse("resting heart rate" in text.replace("not a resting heart rate", ""), "never labelled resting heart rate")
    }

    @Test
    fun deviceGraphsOfALongSelectionUseTheReadingsSpanAndOmitLabelsHonestly() {
        val year = ExportFamily.DEVICE_MEASUREMENTS.defaults().copy(from = LocalDate(2026, 1, 1), to = LocalDate(2026, 12, 31))
        val one = DoctorExportContent.document(DesignFixture.data(), "x", ContentLanguage.EN, year, zone).let { doc ->
            doc.copy(deviceRows = doc.deviceRows + doc.deviceRows.filter { it.metric == app.vezuna.domain.VitalMetric.STEPS }.flatMap { row ->
                (1..9).map { offset -> row.copy(date = row.date.minus(7 * offset, DateTimeUnit.DAY), value = 100000.0 + offset) }
            }.sortedBy { it.date })
        }
        val text = text(PdfFixtures.pdfBytes(one))
        assertTrue("Some labels are left out for space; every value and date is in the table." in text, "crowded labels are dropped and said so")
        assertTrue("100009 steps" in text, "the table keeps every value")
        val single = ExportFamily.DEVICE_MEASUREMENTS.defaults().copy(from = LocalDate(2026, 10, 31), to = LocalDate(2026, 10, 31))
        val singleText = text(PdfFixtures.pdfBytes(document(ExportFamily.DEVICE_MEASUREMENTS, options = single)))
        assertTrue("Not enough readings to compare" in singleText || "Not available" in singleText, singleText)
    }

    @Test
    fun sourceAppendixListsEveryReportAndExcludedDocumentWithoutTheirValues() {
        val options = ExportFamily.HEALTH_SUMMARY.defaults().copy(includeSourceAppendix = true)
        val document = document(ExportFamily.HEALTH_SUMMARY, options = options)
        val bytes = PdfFixtures.pdfBytes(document)
        val pages = PdfFixtures.pageTexts(bytes)
        val appendix = pages.last().replace(Regex("\\s+"), " ")
        listOf(
            "Sources included", "Original files are listed, not embedded. Only confirmed results are included.", "Collected Report Original file",
            "12 Apr 2026 april-blood-work-2026.pdf Example Laboratory Confirmed results included: 2", "lipid-panel-2026-10-14.pdf",
            "hepatitis-b-screening-2026-10-28.pdf", "Original not stored on this device",
            "Not included", "Imported 01 Nov 2026 october-blood-work-review.pdf Unreviewed values: 3",
        ).forEach { assertTrue(it in appendix, "missing: $it") }
        listOf("Fasting glucose", "Ferritin", "96", "42 ng/mL", "30 Oct").forEach { assertFalse(it in appendix, "unconfirmed data printed: $it") }
        assertEquals(6, document.sourceRows.count { it.results > 0 })
        assertEquals(8, document.sourceRows.sumOf { it.results })
        assertFalse("Sources included" in pages.dropLast(1).joinToString(" "), "the appendix starts its own page")
    }

    @Test
    fun sourceAppendixNamesStoredOriginalsPagesManualEntriesAndEmptySelections() {
        val base = PdfFixtures.canonical()
        val photo = SourceDocument(PdfFixtures.PROFILE, "doc-photo", "clinic-photo.jpg", "image/jpeg", "1".repeat(64), Instant.parse("2026-09-01T10:00:00Z"), vaultKey = "v1", pageVaultKeys = listOf("v2", "v3"))
        fun obs(id: String, report: String) = LabObservation(
            PdfFixtures.PROFILE, id, report, "TSH", "TSH", 1.5, "mIU/L", 1.5, "mIU/L", ReferenceRange(0.4, 4.0, unit = "mIU/L"),
            Instant.parse("2026-09-01T10:00:00Z"), ConfirmationStatus.CONFIRMED, valueDecimals = 1,
        )
        val data = base.copy(
            documents = base.documents + photo,
            reports = base.reports + LabReport(PdfFixtures.PROFILE, "r-photo", "doc-photo", null, Instant.parse("2026-08-30T10:00:00Z"), Instant.parse("2026-09-01T10:00:00Z")) +
                LabReport(PdfFixtures.PROFILE, "r-manual", null, null, null, Instant.parse("2026-09-02T10:00:00Z")),
            observations = base.observations + obs("photo-1", "r-photo") + obs("manual-1", "r-manual").copy(collectedAt = Instant.parse("2026-09-02T10:00:00Z")),
        )
        val withSources = ExportOptions(includeSourceAppendix = true)
        val text = text(PdfFixtures.pdfBytes(PdfFixtures.document(data, options = withSources)))
        listOf(
            "clinic-photo.jpg ordered 30 Aug 2026 Pages: 3 Confirmed results included: 1 Original stored in Vezuna",
            "Entered manually Confirmed results included: 1 No file: entered manually",
        ).forEach { assertTrue(it in text, "missing: $it") }
        val noLabs = text(PdfFixtures.pdfBytes(PdfFixtures.document(data, options = withSources.copy(includeLabsPdf = false))))
        assertTrue("No source reports: no confirmed results are included." in noLabs)
        assertFalse("Sources included" in text(PdfFixtures.pdfBytes(PdfFixtures.document(data))), "the appendix is opt-in")
    }

    @Test
    fun newSectionsAreLocalized() {
        val pt = text(PdfFixtures.pdfBytes(document(ExportFamily.MEDICATION_RECORD, ContentLanguage.PT, weekOf(ExportFamily.MEDICATION_RECORD).copy(includeSourceAppendix = true, includeLabsPdf = true))))
        listOf("Linha do tempo diária das doses", "Horários dos próximos sete dias", "Dom 01 Nov", "Sem data de término", "Fontes incluídas", "Não incluídos").forEach {
            assertTrue(it in pt, "missing PT: $it")
        }
        val es = text(PdfFixtures.pdfBytes(document(ExportFamily.DEVICE_MEASUREMENTS, ContentLanguage.ES, weekOf(ExportFamily.DEVICE_MEASUREMENTS))))
        listOf("Días con datos del dispositivo en esta exportación: 7", "Cambio desde el primer resultado", "-0,4 kg").forEach { assertTrue(it in es, "missing ES: $it") }
        val esSummary = text(PdfFixtures.pdfBytes(document(ExportFamily.HEALTH_SUMMARY, ContentLanguage.ES)))
        assertTrue("+0,2 g/dL" in esSummary && "13,0" in esSummary)
    }

    @Test
    fun newFamiliesKeepElevenPointTextAndGrayscaleOnLetter() {
        val documents = listOf(
            document(ExportFamily.HEALTH_SUMMARY, options = ExportFamily.HEALTH_SUMMARY.defaults(PaperSize.LETTER).copy(grayscale = true, includeSourceAppendix = true)),
            document(ExportFamily.MEDICATION_RECORD, options = weekOf(ExportFamily.MEDICATION_RECORD, PaperSize.LETTER).copy(grayscale = true)),
            document(ExportFamily.DEVICE_MEASUREMENTS, options = weekOf(ExportFamily.DEVICE_MEASUREMENTS, PaperSize.LETTER).copy(grayscale = true)),
        )
        documents.forEach { document ->
            val bytes = PdfFixtures.pdfBytes(document)
            PdfFixtures.open(bytes) { pdf ->
                pdf.pages.forEach { page ->
                    assertEquals(612f, page.mediaBox.width)
                    val tokens = PDFStreamParser(page).parse()
                    tokens.forEachIndexed { index, token ->
                        if (token is Operator && token.name in setOf("rg", "RG")) {
                            val (r, g, b) = tokens.subList(index - 3, index).map { (it as COSNumber).floatValue() }
                            assertTrue(r == g && g == b, "non-gray color $r $g $b in ${document.family}")
                        }
                    }
                }
            }
            positions(bytes).forEach { (pageHeight, position) ->
                if (pageHeight - position.yDirAdj > 25 * PdfStyle.MM) assertTrue(position.fontSizeInPt >= 11f, "'${position.unicode}' at ${position.fontSizeInPt}pt in ${document.family}")
                else assertTrue(position.fontSizeInPt < 11f, "content '${position.unicode}' in the footer band in ${document.family}")
            }
        }
    }

    @Test
    fun mixedUnitNotesStayWithTheirGraphAndNeverRunIntoTheFooter() {
        val base = PdfFixtures.canonical()
        // Crowded daily readings in two units: normalized-unit note plus omitted-labels note under the graph.
        val albumin = (0 until 40).map { index ->
            val grams = index % 2 == 1 // latest in g/dL, so the g/L graph needs its unit note
            LabObservation(
                PdfFixtures.PROFILE, "alb-$index", "report-apr", "ALB", "Albumin", if (grams) 4.0 + index / 100.0 else 40.0 + index / 10.0,
                if (grams) "g/dL" else "g/L", 40.0 + index / 10.0, "g/L", ReferenceRange(35.0, 50.0, unit = "g/L"),
                Instant.fromEpochSeconds(Instant.parse("2026-01-01T10:00:00Z").epochSeconds + index * 86_400L), ConfirmationStatus.CONFIRMED, valueDecimals = 1,
            )
        }
        // Fillers move the section start in uneven steps; a hard-wrapped reference moves it one body line at a time.
        // Content must end above the content bottom (footer band 25 mm + 2 mm gap); a baseline below it overflowed.
        val contentBottom = 27 * PdfStyle.MM - 1.0
        listOf(ContentLanguage.EN, ContentLanguage.PT).forEach { language -> (1..3).forEach { fillers ->
            (0..30).forEach { lines ->
                val singles = (0 until fillers).map { index ->
                    val range = if (index == 0 && lines > 0) ReferenceRange(0.5, 2.0, text = (1..lines).joinToString("\n") { "note $it" }, unit = "U/L") else ReferenceRange(0.5, 2.0, unit = "U/L")
                    LabObservation(
                        PdfFixtures.PROFILE, "a-$index", "report-apr", "A$index", "Aa test $index", 1.0, "U/L", 1.0, "U/L",
                        range, Instant.parse("2026-02-01T10:00:00Z"), ConfirmationStatus.CONFIRMED, valueDecimals = 1,
                    )
                }
                val bytes = PdfFixtures.pdfBytes(PdfFixtures.document(base.copy(observations = singles + albumin), language))
                // The kept height covers both notes, so the graph's changes never get pushed onto the next page.
                assertFalse("Albumin · ${ExportText.of(language).changesContinued}" in text(bytes), "changes split from their graph ($language, $fillers fillers, $lines lines)")
                positions(bytes).forEach { (pageHeight, position) ->
                    if (position.fontSizeInPt >= 11f) {
                        assertTrue(pageHeight - position.yDirAdj > contentBottom, "'${position.unicode}' below the content area ($language, $fillers fillers, $lines lines)")
                    }
                }
            }
        } }
        val sample = PdfFixtures.pdfBytes(PdfFixtures.document(base.copy(observations = albumin)))
        assertTrue("The graph uses g/L because" in text(sample), "mixed units draw the normalized series with its note")
        assertTrue("Some labels are left out for space" in text(sample))
    }

    @Test
    fun medicationListBrokenAcrossPagesCarriesItsTitle() {
        val base = PdfFixtures.canonical()
        val many = (1..40).map { Medication(PdfFixtures.PROFILE, "m$it", "Medication ${it.toString().padStart(2, '0')}", "10 mg", "1 tablet") }
        val text = PdfFixtures.pageTexts(PdfFixtures.pdfBytes(PdfFixtures.document(base.copy(medications = many)))).joinToString(" ").replace(Regex("\\s+"), " ")
        assertTrue("Recorded medication · continued" in text)
        assertTrue((1..40).all { "Medication ${it.toString().padStart(2, '0')}" in text }, "no medication lost at the break")
    }

    @Test
    fun graphKeyNamesPerDateReferenceBars() {
        listOf(ContentLanguage.EN to "a bar at each date", ContentLanguage.PT to "uma barra em cada data", ContentLanguage.ES to "una barra en cada fecha").forEach { (language, phrase) ->
            assertTrue(phrase in ExportText.of(language).graphKey, "$language")
            assertTrue("Some labels" in ExportText.of(ContentLanguage.EN).labelsOmitted)
        }
    }

    @Test
    fun summaryGraphWithDroppedLabelsListsItsReadingsAndTheKeyFitsThePage() {
        val base = PdfFixtures.canonical()
        val daily = (0 until 30).map { index ->
            LabObservation(
                PdfFixtures.PROFILE, "hgb-d$index", "report-apr", "HGB", "Hemoglobin", 12.0 + index / 10.0, "g/dL", 120.0 + index, "g/L",
                ReferenceRange(12.0, 16.0, unit = "g/dL"), Instant.fromEpochSeconds(Instant.parse("2026-05-01T11:00:00Z").epochSeconds + index * 86_400L),
                ConfirmationStatus.CONFIRMED, valueDecimals = 1,
            )
        }
        val data = base.copy(observations = base.observations.filterNot { it.testCode == "HGB" } + daily)
        val document = DoctorExportContent.document(data, "Health summary", ContentLanguage.EN, ExportFamily.HEALTH_SUMMARY.defaults(), zone, PdfFixtures.generatedAt)
        val bytes = PdfFixtures.pdfBytes(document)
        val text = text(bytes)
        assertTrue("Some labels are left out for space" in text)
        assertTrue("Hemoglobin · all readings" in text)
        assertTrue((0 until 30).all { "${(120 + it) / 10}.${(120 + it) % 10} g/dL" in text }, "every reading is printed as text")
        positions(bytes).forEach { (pageHeight, position) ->
            if (position.fontSizeInPt >= 11f) assertTrue(pageHeight - position.yDirAdj > 25 * PdfStyle.MM, "'${position.unicode}' in the footer band")
        }
    }

    @Test
    fun scheduleStripNeverLeavesItsMedicationAndContinuesAsSchedule() {
        val base = DesignFixture.data()
        val schedule = base.medications.single().schedules.single()
        val many = (1..12).map { index ->
            Medication(base.profile.profileId, "m$index", "Medicine ${index.toString().padStart(2, '0')}", "5 mg", "1 tablet", listOf(schedule.copy(scheduleId = "s$index", medicationId = "m$index")))
        }
        val bytes = PdfFixtures.pdfBytes(document(ExportFamily.MEDICATION_RECORD, data = base.copy(medications = many, doseEvents = emptyList())))
        PdfFixtures.pageTexts(bytes).forEach { page ->
            val strip = page.indexOf("Schedule for the next seven days")
            if (strip >= 0) assertTrue(Regex("Medicine \\d\\d").find(page)?.range?.first?.let { it < strip } == true, "a strip opens a page without its medication:\n$page")
        }
        assertFalse("results, continued" in text(bytes).substringBefore("Dose history"))
    }

    @Test
    fun crowdedDayCapsItsStackWeightKeepsStoredPrecisionAndUntitledDocumentsAreNamed() {
        val base = DesignFixture.data()
        val schedule = base.medications.single().schedules.single()
        val crowded = (0 until 10).map { index ->
            DoseEvent(base.profile.profileId, "c$index", "med-vitamin-d", schedule.scheduleId, Instant.parse("2026-10-20T1${index}:00:00Z"), DoseEventStatus.TAKEN, recordedAt = Instant.parse("2026-10-20T1${index}:01:00Z"))
        }
        val doses = text(PdfFixtures.pdfBytes(document(ExportFamily.MEDICATION_RECORD, data = base.copy(doseEvents = base.doseEvents + crowded))))
        assertTrue("+6" in doses, "doses past the stack are counted, the table lists each")
        val precise = base.copy(vitals = base.vitals.copy(weights = base.vitals.weights.map { it.copy(kg = it.kg + 0.05) }))
        val device = text(PdfFixtures.pdfBytes(document(ExportFamily.DEVICE_MEASUREMENTS, options = weekOf(ExportFamily.DEVICE_MEASUREMENTS), data = precise)))
        assertTrue("68.45 kg" in device && "-0.40 kg (from 25 Oct 2026)" in device, device)
        val untitled = base.copy(documents = base.documents.map { if (it.documentId == "doc-october-review") it.copy(displayName = " ") else it })
        val appendix = text(PdfFixtures.pdfBytes(document(ExportFamily.HEALTH_SUMMARY, options = ExportFamily.HEALTH_SUMMARY.defaults().copy(includeSourceAppendix = true), data = untitled)))
        assertTrue("Untitled document Unreviewed values: 3" in appendix)
    }

    private fun positions(bytes: ByteArray): List<Pair<Float, TextPosition>> = PdfFixtures.open(bytes) { pdf ->
        val found = mutableListOf<Pair<Float, TextPosition>>()
        object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                textPositions.forEach { found += currentPage.mediaBox.height to it }
            }
        }.getText(pdf)
        found
    }
}
