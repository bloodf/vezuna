package app.vezuna.ui

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DashboardSnapshotFactory
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.failMessage
import app.vezuna.localization.toUserText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Single source of the shared mutable state (the [AppUiState] flow, the loaded profile) that every feature
 * controller works on. [AppController] owns one and hands it to each of them.
 */
internal class ControllerCore(
    val repository: VezunaRepository,
    val clock: () -> Instant,
    val timeZone: () -> TimeZone,
    val sourceStore: SourceFileStore?,
) {
    val mutableState = MutableStateFlow(AppUiState())

    fun requireData(): ProfileData = mutableState.value.data ?: failMessage(L10nKey.SELECT_PROFILE_FIRST)

    fun clearMessages() { mutableState.value = mutableState.value.copy(error = null, notice = null) }

    fun announce(notice: UserText) {
        mutableState.value = mutableState.value.copy(notice = notice, error = null)
    }

    // Counts loads so a read that finished after a newer one began cannot overwrite it. Controllers run on the main
    // dispatcher, so a plain counter is enough.
    private var latestLoad = 0

    /** The profile a switch is loading right now, so a concurrent first load follows it instead of undoing it. */
    var openingProfileId: String? = null
        private set

    /**
     * Opens [profileId] (profile switch, delete, first load) and remembers it as the saved selection. A newer load makes
     * this one a no-op; returns false then.
     */
    suspend fun openProfile(profileId: String): Boolean {
        val ticket = ++latestLoad
        openingProfileId = profileId
        try {
            val link = repository.linkedHealthProfileId()
            val data = repository.readProfile(profileId)
            if (ticket != latestLoad) return false
            apply(data, link)
            return true
        } finally {
            // Also on failure: a stuck id would make every Retry reopen the same failing profile.
            if (ticket == latestLoad) openingProfileId = null
        }
    }

    /**
     * Refreshes whatever profile is open now, after a save or on focus. Callers never pass an id: one captured before a
     * suspension may no longer be the open profile. Does nothing while a profile is opening, and never moves the saved selection.
     */
    suspend fun reloadCurrentProfile() {
        val profileId = mutableState.value.data?.profile?.profileId ?: return
        if (repository.profiles.value.none { it.profileId == profileId }) return
        val ticket = ++latestLoad
        val link = repository.linkedHealthProfileId()
        val data = try {
            repository.peekProfile(profileId)
        } catch (gone: NoSuchElementException) {
            return // deleted while this refresh was waiting
        }
        // No suspension from here to the write, so the check cannot go stale.
        if (ticket == latestLoad && mutableState.value.data?.profile?.profileId == profileId) apply(data, link)
    }

    private fun apply(data: ProfileData, link: String?) {
        mutableState.value = mutableState.value.copy(
            profiles = repository.profiles.value,
            data = data,
            healthLinkedProfileId = link,
            dashboard = DashboardSnapshotFactory.create(data, clock(), timeZone()),
            selectedObservationIds = mutableState.value.selectedObservationIds.intersect(
                data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }.map { it.observationId }.toSet(),
            ),
        )
    }

    /** True while [profileId] is still the open profile: a save that outlived a switch must not navigate or announce on the new one. */
    fun isOpen(profileId: String) = mutableState.value.data?.profile?.profileId == profileId

    suspend fun operation(block: suspend () -> Unit) {
        mutableState.value = mutableState.value.copy(loading = true, error = null, notice = null)
        try {
            block()
            mutableState.value = mutableState.value.copy(loading = false)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            mutableState.value = mutableState.value.copy(loading = false)
            throw cancellation
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(loading = false, error = error.toUserText())
        }
    }
}
