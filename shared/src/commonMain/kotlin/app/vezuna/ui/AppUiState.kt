package app.vezuna.ui

import app.vezuna.domain.DashboardSnapshot
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.export.ExportedPdf
import app.vezuna.localization.UserText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class RestoreMode { REPLACE_EXISTING, RESTORE_AS_NEW }

/** Where a platform keeps the imported source file (encrypted). Optional: a platform without one stores no file. */
interface SourceFileStore {
    /** Stores [bytes] for the document and returns the key to record on it. */
    suspend fun save(profileId: String, documentId: String, bytes: ByteArray): String

    /** Removes a stored file when the import that owned it could not be recorded. */
    suspend fun discard(key: String)

    /** The stored original, or null when the store keeps none for this document. Used by household backup. */
    suspend fun load(profileId: String, documentId: String, key: String): ByteArray? = null
}

data class AppUiState(
    val loading: Boolean = true,
    val error: UserText? = null,
    val notice: UserText? = null,
    val profiles: List<Profile> = emptyList(),
    val data: ProfileData? = null,
    val dashboard: DashboardSnapshot? = null,
    val selectedObservationIds: Set<String> = emptySet(),
    val csvExport: String? = null,
    val pdfExport: ExportedPdf? = null,
    val healthLinkedProfileId: String? = null,
    /** True only on a fresh install, until the user finishes or skips the first-run screen. Never gates records. */
    val onboarding: Boolean = false,
    /** Assistant text received so far while an answer is being generated; null when idle. */
    val streamingAnswer: String? = null,
)

object DashboardRefreshSignal {
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes = mutableChanges.asSharedFlow()

    fun notifyRepositoryChanged() {
        mutableChanges.tryEmit(Unit)
    }
}
