package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ReferenceRange
import app.vezuna.importer.OcrTextParser
import app.vezuna.ui.ExtractedCandidate
import app.vezuna.ui.PickedSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LabReferenceTextTest {
    private val day = Instant.parse("2026-03-10T12:00:00Z")

    private suspend fun confirm(
        range: ReferenceRange,
        lower: String,
        upper: String,
        editedText: String?,
    ): ReferenceRange {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        controller.importSource(PickedSource("lab.pdf", "application/pdf", "x".encodeToByteArray(), listOf(
            ExtractedCandidate("LDL", "LDL", "92", "mg/dL", range, day),
        )))
        val profileId = repository.profiles.value.single().profileId
        val candidate = repository.readProfile(profileId).ocrCandidates.single()
        controller.confirmCandidate(candidate.candidateId, "92", "mg/dL", lower, upper, referenceText = editedText)
        assertNull(controller.state.value.error)
        return repository.readProfile(profileId).observations.single().referenceRange
    }

    @Test
    fun readRangeTextSurvivesConfirmation() = runTest {
        val range = confirm(ReferenceRange(upper = 200.0, text = "Inferior a 200 mg/dL", unit = "mg/dL"), "", "200", null)
        assertEquals("Inferior a 200 mg/dL", range.text)
        assertEquals(200.0, range.upper)
    }

    @Test
    fun textTheReviewerLeftAloneIsNeverTurnedIntoBounds() = runTest {
        val range = confirm(ReferenceRange(text = "Desejável: conforme tabela"), "", "", null)
        assertEquals("Desejável: conforme tabela", range.text)
        assertNull(range.lower)
        assertNull(range.upper)
    }

    @Test
    fun editedTextThatIsAThresholdFillsOneSidedBounds() = runTest {
        val upper = confirm(ReferenceRange(), "", "", "< 130 mg/dL")
        assertEquals("< 130 mg/dL", upper.text)
        assertEquals(130.0, upper.upper)
        assertNull(upper.lower)
        val lower = confirm(ReferenceRange(), "", "", "≥ 0,4")
        assertEquals(0.4, lower.lower)
        assertNull(lower.upper)
        assertEquals(5.0, confirm(ReferenceRange(), "", "", "≤5").upper)
        assertEquals(5.0, confirm(ReferenceRange(), "", "", ">5").lower)
    }

    @Test
    fun editedTextThatIsNotAThresholdKeepsTextOnly() = runTest {
        val range = confirm(ReferenceRange(), "", "", "ver tabela do laboratório")
        assertEquals("ver tabela do laboratório", range.text)
        assertNull(range.lower)
        assertNull(range.upper)
    }

    @Test
    fun explicitBoundsWinOverEditedText() = runTest {
        val range = confirm(ReferenceRange(), "10", "20", "< 130")
        assertEquals(10.0, range.lower)
        assertEquals(20.0, range.upper)
    }

    @Test
    fun parserStoresSymbolThresholdsAsOneSidedBounds() {
        val rows = OcrTextParser.parseMeasurements(
            "Colesterol LDL 92 mg/dL < 130 mg/dL\nTSH 2,1 uUI/mL ≥ 0,4 uUI/mL\nPCR 3,2 mg/L ≤ 5,0 mg/L",
        )
        assertEquals(130.0, rows[0].referenceRange.upper)
        assertNull(rows[0].referenceRange.lower)
        assertEquals(0.4, rows[1].referenceRange.lower)
        assertNull(rows[1].referenceRange.upper)
        assertEquals(5.0, rows[2].referenceRange.upper)
        assertEquals("≤ 5,0 mg/L", rows[2].referenceRange.text)
    }
}
