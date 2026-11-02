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

    suspend fun reloadCurrentProfile() {
        val profileId = mutableState.value.data?.profile?.profileId ?: return
        loadProfile(profileId)
    }

    suspend fun loadProfile(profileId: String) {
        val data = repository.readProfile(profileId)
        mutableState.value = mutableState.value.copy(
            profiles = repository.profiles.value,
            data = data,
            healthLinkedProfileId = repository.linkedHealthProfileId(),
            dashboard = DashboardSnapshotFactory.create(data, clock(), timeZone()),
            selectedObservationIds = mutableState.value.selectedObservationIds.intersect(
                data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }.map { it.observationId }.toSet(),
            ),
        )
    }

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
