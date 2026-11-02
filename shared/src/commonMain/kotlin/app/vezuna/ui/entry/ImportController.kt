package app.vezuna.ui.entry

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabReport
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.SourceDocument
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.failMessage
import app.vezuna.localization.message
import app.vezuna.localization.pluralMessage
import app.vezuna.platform.currentInstant
import app.vezuna.platform.randomId
import app.vezuna.security.backupFileId
import app.vezuna.ui.AppScreen
import app.vezuna.ui.ControllerCore
import app.vezuna.ui.ExtractedCandidate
import app.vezuna.ui.ExtractedValueKind
import app.vezuna.ui.PickedSource
import app.vezuna.ui.candidateFingerprint
import app.vezuna.ui.nav.Navigator
import app.vezuna.ui.sourceIdentity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class ImportController(private val core: ControllerCore, private val navigator: Navigator) {
    private val repository get() = core.repository
    private val sourceStore get() = core.sourceStore

    suspend fun importSource(source: PickedSource) = core.operation {
        if (source.candidates.isEmpty()) failMessage(L10nKey.NO_CLINICAL_CANDIDATES_EXTRACTED)
        val inserted = importExtracted(source, source.candidates)
        navigator.navigate(AppScreen.ImportReview)
        core.mutableState.value = core.mutableState.value.copy(
            notice = if (inserted == 0) message(L10nKey.NO_NEW_CANDIDATES)
            else pluralMessage(L10nKey.UNREVIEWED_CANDIDATES_ADDED, inserted),
        )
    }

    private suspend fun importExtracted(source: PickedSource, candidates: List<ExtractedCandidate>): Int {
        val data = core.requireData()
        if (source.fileName.isBlank() || source.bytes.isEmpty()) failMessage(L10nKey.READABLE_SOURCE_REQUIRED)
        if (candidates.isEmpty()) failMessage(L10nKey.REVIEW_CANDIDATE_REQUIRED)
        candidates.forEach { candidate ->
            if (candidate.testCode.isBlank() || candidate.displayName.isBlank() || candidate.rawValue.isBlank()) {
                failMessage(L10nKey.CANDIDATE_REQUIRED_FIELDS)
            }
            if (candidate.valueKind != ExtractedValueKind.QUALITATIVE && candidate.rawUnit.isBlank()) {
                failMessage(L10nKey.NUMERIC_CANDIDATE_UNIT_REQUIRED)
            }
        }
        val sourceHash = sourceIdentity(source)
        val existingDocument = data.documents.firstOrNull { it.sha256 == sourceHash }
        val document = existingDocument ?: newSourceDocument(data.profile.profileId, source, sourceHash)
        if (existingDocument == null) addDocumentKeepingFile(document) else reattachMissingSourceFile(existingDocument, source)
        if (data.reports.none { it.sourceDocumentId == document.documentId }) {
            repository.addReport(
                LabReport(
                    data.profile.profileId, randomId(), document.documentId,
                    laboratoryName = source.laboratoryName?.trim()?.takeIf { it.isNotEmpty() },
                    orderedAt = source.orderedAt,
                    importedAt = currentInstant(),
                ),
            )
        }
        val existingFingerprints = data.ocrCandidates.filter { it.sourceDocumentId == document.documentId }
            .mapTo(mutableSetOf()) { candidateFingerprint(it.testCode, it.displayName, it.rawValue, it.rawUnit, it.referenceRange, it.collectedAt) }
        var inserted = 0
        candidates.forEach { candidate ->
            val collectedAt = candidate.collectedAt ?: document.importedAt
            val fingerprint = candidateFingerprint(
                candidate.testCode,
                candidate.displayName,
                candidate.rawValue,
                candidate.rawUnit,
                candidate.referenceRange,
                collectedAt,
            )
            if (existingFingerprints.add(fingerprint)) {
                repository.addCandidate(
                    OcrCandidate(
                        profileId = data.profile.profileId,
                        candidateId = randomId(),
                        sourceDocumentId = document.documentId,
                        testCode = candidate.testCode.trim(),
                        displayName = candidate.displayName.trim(),
                        rawValue = candidate.rawValue.trim(),
                        rawUnit = candidate.rawUnit.trim(),
                        referenceRange = candidate.referenceRange,
                        collectedAt = collectedAt,
                        confidence = candidate.confidence,
                        status = ConfirmationStatus.UNREVIEWED,
                        qualitative = candidate.valueKind == ExtractedValueKind.QUALITATIVE,
                        rawToken = candidate.rawToken,
                        collectedAtKnown = candidate.collectedAt != null,
                    ),
                )
                inserted++
            }
        }
        core.loadProfile(data.profile.profileId)
        return inserted
    }

    private suspend fun newSourceDocument(profileId: String, source: PickedSource, sourceHash: String): SourceDocument {
        val documentId = randomId()
        val store = sourceStore
        val saved = mutableListOf<String>()
        try {
            store?.let { saved += it.save(profileId, documentId, source.bytes) }
            store?.let { s -> source.additionalPages.forEachIndexed { index, page -> saved += s.save(profileId, "$documentId-page-${index + 2}", page) } }
        } catch (error: Throwable) {
            withContext(NonCancellable) { saved.forEach { store?.discard(it) } }
            throw error
        }
        return SourceDocument(
            profileId, documentId, source.fileName.trim(), source.mediaType, sourceHash, currentInstant(),
            saved.firstOrNull(), saved.drop(1),
        )
    }

    /** A stored file must never outlive a document that was not recorded. */
    private suspend fun addDocumentKeepingFile(document: SourceDocument) {
        try {
            repository.addDocument(document)
        } catch (error: Throwable) {
            withContext(NonCancellable) { (listOfNotNull(document.vaultKey) + document.pageVaultKeys).forEach { sourceStore?.discard(it) } }
            throw error
        }
    }
    // region L3 import and entry
    /** Import journey state (chosen source, extraction phase, draft dates); cleared on profile change. */
    val importSession = ImportSession()

    /**
     * Opens the platform picker for [kind] and imports what it extracts. A cancelled picker returns silently;
     * a failure or a source without results stays on the source screen with its own explanation.
     */
    suspend fun importFrom(kind: SourceKind, picker: suspend () -> PickedSource?) {
        val session = importSession
        session.start(kind)
        val profileId = core.mutableState.value.data?.profile?.profileId
        val source = try {
            picker()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            session.idle()
            throw cancellation
        } catch (failure: ImportFailure) {
            return session.fail(failure.reason, failure.fileName)
        } catch (error: Throwable) {
            return session.fail(ImportFailureReason.UNREADABLE, null)
        } ?: return session.idle()
        // A source picked for one profile never lands in another: switching while the picker was open drops it.
        if (profileId == null || core.mutableState.value.data?.profile?.profileId != profileId) {
            session.idle()
            return core.announce(message(L10nKey.ENTRY_PROFILE_CHANGED))
        }
        if (source.candidates.isEmpty()) return session.noResults(source.fileName)
        // Stopped before the save began: nothing is stored. Once saving starts it always finishes, so a document
        // never exists with only some of its candidates.
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            session.idle()
            throw cancellation
        }
        session.saving()
        val onSourceScreen = navigator.current == AppScreen.ImportSource || navigator.current == AppScreen.ImportExtract
        withContext(NonCancellable) { importSource(source) }
        // The source step is finished: back from the review returns to where the import began, not to the picker.
        if (onSourceScreen && core.mutableState.value.error == null && navigator.current == AppScreen.ImportReview) {
            navigator.back()
            navigator.finishAndGo(AppScreen.ImportReview)
        }
        session.idle()
    }

    /** True when the last operation failed because the result is already recorded (the draft stays editable). */
    val lastErrorWasDuplicate: Boolean
        get() = (core.mutableState.value.error as? UserText.Key)?.key == L10nKey.DUPLICATE_CLINICAL_RESULT
    // endregion L3 import and entry
    /** Importing the same file again stores it for a document that lost its file (portable restore keeps no files). */
    private suspend fun reattachMissingSourceFile(document: SourceDocument, source: PickedSource) {
        val store = sourceStore
        if (document.vaultKey != null || store == null) return
        val saved = mutableListOf<String>()
        try {
            saved += store.save(document.profileId, document.documentId, source.bytes)
            source.additionalPages.forEachIndexed { index, page -> saved += store.save(document.profileId, backupFileId(document.documentId, index + 2), page) }
            repository.updateDocument(document.copy(vaultKey = saved.first(), pageVaultKeys = saved.drop(1)))
        } catch (error: Throwable) {
            withContext(NonCancellable) { saved.forEach { store.discard(it) } }
            throw error
        }
    }
}
