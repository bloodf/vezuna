package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.keepingOnlyAvailableSourceFiles
import app.vezuna.ui.ExtractedCandidate
import app.vezuna.ui.PickedSource
import app.vezuna.ui.SourceFileRead
import app.vezuna.ui.SourceFileStore
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** R12 source lifecycle: stored, read back for its profile only, missing after a restore, re-attached by re-import. */
class SourceFileReadTest {
    private val day = Instant.parse("2026-03-10T12:00:00Z")
    private val candidates = listOf(ExtractedCandidate("HGB", "Hemoglobin", "13", "g/dL", collectedAt = day))

    private class MemoryStore : SourceFileStore {
        val files = mutableMapOf<String, Pair<String, ByteArray>>()
        var failReads = false

        override suspend fun save(profileId: String, documentId: String, bytes: ByteArray): String =
            "source-document-$documentId".also { files[it] = profileId to bytes }

        override suspend fun discard(key: String) {
            files.remove(key)
        }

        override suspend fun load(profileId: String, documentId: String, key: String): ByteArray? {
            check(!failReads) { "vault unavailable" }
            val (owner, bytes) = files[key] ?: return null
            check(owner == profileId) { "other profile" }
            check(key == "source-document-$documentId") { "other document" }
            return bytes
        }
    }

    @Test
    fun storedPagesReadBackInOrder() = runTest {
        val store = MemoryStore()
        val controller = labController(InMemoryVezunaRepository(), store)
        controller.initialize()
        controller.importSource(PickedSource("scan.jpg", "image/jpeg", "page1".encodeToByteArray(), candidates, additionalPages = listOf("page2".encodeToByteArray())))
        val document = controller.state.value.data!!.documents.single()

        val read = assertIs<SourceFileRead.Available>(controller.readSourceFile(document.documentId))

        assertEquals("image/jpeg", read.mediaType)
        assertEquals(listOf("page1", "page2"), read.pages.map { it.decodeToString() })
    }

    @Test
    fun missingWithoutStoredFileAndUnreadableWhenDecryptFails() = runTest {
        val store = MemoryStore()
        val controller = labController(InMemoryVezunaRepository(), store)
        controller.initialize()
        controller.importSource(PickedSource("a.pdf", "application/pdf", "pdf".encodeToByteArray(), candidates))
        val documentId = controller.state.value.data!!.documents.single().documentId

        assertEquals(SourceFileRead.Missing, controller.readSourceFile("not-a-document"))
        store.failReads = true
        assertEquals(SourceFileRead.Unreadable, controller.readSourceFile(documentId))
        store.failReads = false
        store.files.clear()
        assertEquals(SourceFileRead.Missing, controller.readSourceFile(documentId))
        assertEquals(SourceFileRead.Missing, labController(InMemoryVezunaRepository()).readSourceFile(documentId))
    }

    @Test
    fun reimportingTheSameFileReattachesItAfterARestoreWithoutFiles() = runTest {
        val repository = InMemoryVezunaRepository()
        val store = MemoryStore()
        val controller = labController(repository, store)
        controller.initialize()
        val source = PickedSource("a.pdf", "application/pdf", "pdf".encodeToByteArray(), candidates)
        controller.importSource(source)
        val profileId = repository.profiles.value.single().profileId
        repository.restoreProfile(repository.readProfile(profileId).keepingOnlyAvailableSourceFiles(null))
        controller.refreshDashboard()
        val documentId = controller.state.value.data!!.documents.single().documentId
        assertNull(controller.state.value.data!!.documents.single().vaultKey)
        assertEquals(SourceFileRead.Missing, controller.readSourceFile(documentId))

        controller.importSource(source)

        val data = repository.readProfile(profileId)
        assertEquals(1, data.documents.size)
        assertEquals(1, data.ocrCandidates.size)
        assertNotNull(data.documents.single().vaultKey)
        assertContentEquals("pdf".encodeToByteArray(), assertIs<SourceFileRead.Available>(controller.readSourceFile(documentId)).pages.single())
    }
}
