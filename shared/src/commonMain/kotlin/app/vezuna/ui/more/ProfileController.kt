package app.vezuna.ui.more

import app.vezuna.domain.DefaultProfileName
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileAge
import app.vezuna.llm.InstalledModelManager
import app.vezuna.localization.ContentLanguage
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.platform.currentInstant
import app.vezuna.platform.randomId
import app.vezuna.ui.ControllerCore
import kotlinx.datetime.LocalDate

internal class ProfileController(
    private val core: ControllerCore,
    private val modelManager: InstalledModelManager,
    /** Clears everything bound to the previous profile; see AppController. */
    private val onProfileChanged: () -> Unit,
) {
    private val repository get() = core.repository
    private val state get() = core.mutableState

    suspend fun initialize() = core.operation {
        // A profile only exists once the first run began, so an existing profile means onboarding is done (or not needed).
        val freshInstall = repository.profiles.value.isEmpty()
        if (freshInstall) {
            repository.createProfile(Profile(randomId(), DefaultProfileName.of(ContentLanguage.device()), createdAt = currentInstant()))
        }
        state.value = state.value.copy(profiles = repository.profiles.value, onboarding = freshInstall || state.value.onboarding)
        val profileId = state.value.data?.profile?.profileId ?: repository.profiles.value.first().profileId
        core.loadProfile(profileId)
        // A re-init mid-first-run keeps onboarding (and the typed name) unless real records or a second profile exist.
        val loaded = state.value.data
        if (state.value.onboarding && (repository.profiles.value.size > 1 || loaded == null || loaded.documents.isNotEmpty() || loaded.observations.isNotEmpty() || loaded.medications.isNotEmpty())) {
            state.value = state.value.copy(onboarding = false)
        }
        modelManager.refresh()
    }

    suspend fun switchProfile(profileId: String) = core.operation {
        if (repository.profiles.value.none { it.profileId == profileId }) failMessage(L10nKey.SELECT_PROFILE_FIRST)
        onProfileChanged()
        core.loadProfile(profileId)
    }

    suspend fun createProfile(displayName: String) = core.operation {
        if (displayName.isBlank()) failMessage(L10nKey.PROFILE_NAME_REQUIRED)
        val profile = Profile(randomId(), displayName.trim(), createdAt = currentInstant())
        repository.createProfile(profile)
        state.value = state.value.copy(profiles = repository.profiles.value)
        switchProfile(profile.profileId)
    }

    /** Sets or clears (null) the birth date. Rejects future dates and ages above [ProfileAge.MAX_YEARS]. */
    suspend fun setBirthDate(profileId: String, birthDate: LocalDate?) = core.operation {
        val profile = repository.profiles.value.firstOrNull { it.profileId == profileId } ?: failMessage(L10nKey.SELECT_PROFILE_FIRST)
        if (birthDate != null && !ProfileAge.isValid(birthDate, ProfileAge.today(core.clock(), core.timeZone()))) failMessage(L10nKey.BIRTH_DATE_INVALID)
        repository.updateProfile(profile.copy(birthDate = birthDate))
        state.value = state.value.copy(profiles = repository.profiles.value)
        core.reloadCurrentProfile()
    }

    /** Ends first-run onboarding, applying the chosen name and birth date when given. A blank name keeps the default. */
    suspend fun completeOnboarding(displayName: String, birthDate: LocalDate?) {
        val current = state.value.data?.profile?.profileId
        val profile = repository.profiles.value.let { all -> all.firstOrNull { it.profileId == current } ?: all.firstOrNull() } ?: return skipOnboarding()
        core.clearMessages()
        if (displayName.isNotBlank() && displayName.trim() != profile.displayName) renameProfile(profile.profileId, displayName)
        if (birthDate != null) setBirthDate(profile.profileId, birthDate)
        if (state.value.error == null) skipOnboarding()
    }

    fun skipOnboarding() {
        state.value = state.value.copy(onboarding = false)
    }

    suspend fun renameProfile(profileId: String, displayName: String) = core.operation {
        if (displayName.isBlank()) failMessage(L10nKey.PROFILE_NAME_REQUIRED)
        val profile = repository.profiles.value.firstOrNull { it.profileId == profileId } ?: failMessage(L10nKey.SELECT_PROFILE_FIRST)
        repository.updateProfile(profile.copy(displayName = displayName.trim()))
        core.reloadCurrentProfile()
    }

    /** The caller must type the exact profile name; the last remaining profile can never be deleted. */
    suspend fun deleteProfile(profileId: String, typedName: String) = core.operation {
        val profiles = repository.profiles.value
        val profile = profiles.firstOrNull { it.profileId == profileId } ?: failMessage(L10nKey.SELECT_PROFILE_FIRST)
        if (profiles.size <= 1) failMessage(L10nKey.LAST_PROFILE_CANNOT_BE_DELETED)
        if (typedName.trim() != profile.displayName.trim()) failMessage(L10nKey.PROFILE_NAME_MISMATCH)
        repository.deleteProfile(profileId)
        val remaining = repository.profiles.value
        val currentId = state.value.data?.profile?.profileId
        if (currentId == null || currentId == profileId) {
            onProfileChanged()
            core.loadProfile(remaining.first().profileId)
        } else {
            core.loadProfile(currentId)
        }
    }

    suspend fun setLinkedHealthProfile(profileId: String) = core.operation {
        repository.setLinkedHealthProfile(profileId)
        core.reloadCurrentProfile()
    }
}
