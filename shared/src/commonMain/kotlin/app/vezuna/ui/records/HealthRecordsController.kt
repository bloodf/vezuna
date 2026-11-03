package app.vezuna.ui.records

import app.vezuna.domain.healthRecords
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.localization.message
import app.vezuna.platform.randomId
import app.vezuna.ui.ControllerCore
import kotlinx.datetime.toLocalDateTime

/** Add, edit and delete of allergies, conditions and vaccinations for the current profile. */
internal class HealthRecordsController(private val core: ControllerCore) {
    private val repository get() = core.repository

    /** Null [recordId] adds a new record. */
    suspend fun save(recordId: String?, draft: HealthRecordDraft) = core.operation {
        val data = core.requireData()
        val today = core.clock().toLocalDateTime(core.timeZone()).date
        if (draft.nameMissing) failMessage(L10nKey.HR_NAME_REQUIRED)
        if (draft.onsetInvalid(today)) failMessage(L10nKey.HR_ONSET_INVALID)
        if (draft.dateInFuture(today)) failMessage(L10nKey.HR_DATE_FUTURE)
        val profileId = data.profile.profileId
        if (recordId == null) {
            repository.addHealthRecord(draft.toRecord(profileId, randomId()))
        } else {
            if (data.healthRecords.none { it.id == recordId && it.kind == draft.kind }) failMessage(L10nKey.HR_NOT_FOUND)
            repository.updateHealthRecord(draft.toRecord(profileId, recordId))
        }
        core.loadProfile(profileId)
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.HR_SAVED))
    }

    suspend fun delete(recordId: String) = core.operation {
        val data = core.requireData()
        if (data.healthRecords.none { it.id == recordId }) failMessage(L10nKey.HR_NOT_FOUND)
        repository.deleteHealthRecord(data.profile.profileId, recordId)
        core.loadProfile(data.profile.profileId)
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.HR_DELETED))
    }
}
