package app.vezuna

import app.vezuna.importer.ReportHeaderParser
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportHeaderTest {
    @Test
    fun readsLaboratoryNameAndOrderDate() {
        val header = ReportHeaderParser.parse("Laboratório Santa Luz\nPaciente Ana\nData do pedido: 20/04/2026\nColeta 22/04/2026\nGlicose 90 mg/dL 70 a 99")
        assertEquals("Laboratório Santa Luz", header.laboratoryName)
        assertEquals(Instant.parse("2026-04-20T12:00:00Z"), header.orderedAt)
    }

    @Test
    fun stripsALabelAndAcceptsOtherLanguages() {
        assertEquals("Fleury", ReportHeaderParser.parse("Laboratório: Fleury").laboratoryName)
        assertEquals(Instant.parse("2026-01-05T12:00:00Z"), ReportHeaderParser.parse("Fecha de solicitud 05/01/2026").orderedAt)
        assertNull(ReportHeaderParser.parse("Order date: 05/01/2026").orderedAt)
        assertNull(ReportHeaderParser.parse("Exames laboratoriais\nResponsável técnico do laboratório").laboratoryName)
    }

    @Test
    fun noHeaderMeansNothingInvented() {
        val header = ReportHeaderParser.parse("Glicose 90 mg/dL 70 a 99\nColeta 22/04/2026")
        assertNull(header.laboratoryName)
        assertNull(header.orderedAt)
        assertNull(ReportHeaderParser.parse("Data do pedido 31/02/2026").orderedAt)
    }
}
