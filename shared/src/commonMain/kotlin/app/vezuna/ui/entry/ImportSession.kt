package app.vezuna.ui.entry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/** The OS source the person chose on the Add a record screen. Each one opens the real system UI. */
enum class SourceKind { CAMERA, PHOTOS, FILE }

/** Explainable reasons a source could not be read; each has its own copy and recovery. */
enum class ImportFailureReason { TOO_LARGE, UNREADABLE, CAMERA_UNAVAILABLE }

/** Thrown by a platform picker for a specific failure the person can act on. Anything else counts as unreadable. */
class ImportFailure(val reason: ImportFailureReason, val fileName: String? = null) : Exception(reason.name)

/** How one page's text was obtained. Optional detail (image 149), never a required step. */
data class ExtractedPage(val pageNumber: Int, val embeddedText: Boolean, val text: String)

data class ExtractionDetails(val fileName: String, val pages: List<ExtractedPage>, val candidateCount: Int)

/** Where the current import stands. Waiting on the OS picker shows as [Reading] under the system UI. */
sealed interface ImportPhase {
    data object Idle : ImportPhase
    data class Reading(val fileName: String?) : ImportPhase
    data class Failed(val reason: ImportFailureReason, val fileName: String?, val kind: SourceKind) : ImportPhase
    data class NoResults(val fileName: String, val kind: SourceKind) : ImportPhase
}

/**
 * In-memory state of the import and review journey for the active profile. Nothing here is stored:
 * collection dates are drafts until a candidate is confirmed, and the session is cleared on profile change.
 */
class ImportSession {
    private val mutablePhase = MutableStateFlow<ImportPhase>(ImportPhase.Idle)
    val phase: StateFlow<ImportPhase> = mutablePhase.asStateFlow()

    private val mutableDetails = MutableStateFlow<ExtractionDetails?>(null)
    val details: StateFlow<ExtractionDetails?> = mutableDetails.asStateFlow()

    private val mutableDates = MutableStateFlow<Map<String, Instant>>(emptyMap())

    /** Collection dates chosen in review, by candidate ID. */
    val chosenDates: StateFlow<Map<String, Instant>> = mutableDates.asStateFlow()

    private val mutableConfirmed = MutableStateFlow(0)

    /** Results confirmed since the last import started; drives the Review complete count (never assumed). */
    val confirmedCount: StateFlow<Int> = mutableConfirmed.asStateFlow()

    /** The source the platform picker should open; read by the platform when the shared UI calls it. */
    var requestedKind: SourceKind = SourceKind.FILE
        private set

    fun start(kind: SourceKind) {
        requestedKind = kind
        mutableDetails.value = null
        mutableConfirmed.value = 0
        mutablePhase.value = ImportPhase.Reading(null)
    }

    /** The platform reports the chosen file's name once the OS picker returns and extraction begins. */
    fun reading(fileName: String) {
        mutablePhase.value = ImportPhase.Reading(fileName)
    }

    fun publish(details: ExtractionDetails) {
        mutableDetails.value = details
    }

    fun fail(reason: ImportFailureReason, fileName: String?) {
        mutablePhase.value = ImportPhase.Failed(reason, fileName, requestedKind)
    }

    fun noResults(fileName: String) {
        mutablePhase.value = ImportPhase.NoResults(fileName, requestedKind)
    }

    fun idle() {
        mutablePhase.value = ImportPhase.Idle
    }

    fun chooseDate(candidateIds: Collection<String>, date: Instant) {
        mutableDates.value = mutableDates.value + candidateIds.associateWith { date }
    }

    fun confirmed() {
        mutableConfirmed.value += 1
    }

    fun clear() {
        mutablePhase.value = ImportPhase.Idle
        mutableDetails.value = null
        mutableDates.value = emptyMap()
        mutableConfirmed.value = 0
        requestedKind = SourceKind.FILE
    }
}
