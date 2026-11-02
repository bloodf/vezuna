package app.vezuna

import app.vezuna.ui.entry.ImportPhase
import app.vezuna.ui.entry.ImportSession
import app.vezuna.ui.entry.MAX_PDF_PAGES
import app.vezuna.ui.entry.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportProgressTest {
    @Test
    fun progressUpdatesOnlyWhileReading() {
        val session = ImportSession()
        session.progress(1, 4)
        assertEquals(ImportPhase.Idle, session.phase.value)
        session.start(SourceKind.FILE)
        session.reading("lab.pdf")
        session.progress(2, 4)
        assertEquals(ImportPhase.Reading("lab.pdf", 2, 4), session.phase.value)
        session.idle()
        session.progress(3, 4)
        assertEquals(ImportPhase.Idle, session.phase.value)
    }

    @Test
    fun readingAgainResetsProgress() {
        val session = ImportSession()
        session.start(SourceKind.FILE)
        session.reading("a.pdf")
        session.progress(3, 4)
        session.reading("b.pdf")
        assertEquals(ImportPhase.Reading("b.pdf", 0, 0), session.phase.value)
    }

    @Test
    fun pageCapIsPositive() {
        assertTrue(MAX_PDF_PAGES > 0)
    }
}
