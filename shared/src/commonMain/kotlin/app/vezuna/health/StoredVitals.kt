package app.vezuna.health

import app.vezuna.domain.ProfileVitals
import kotlinx.serialization.Serializable

/** Storage-only format 1, allocated independently of repository schemas 1-4 and backup versions 1-7. */
@Serializable
internal data class StoredVitals(
    val vitalsFormat: Int = 1,
    val commitId: String = "",
    val revision: Long = 0,
    val clinical: ProfileVitals = ProfileVitals(),
    val local: WeightLocalState = WeightLocalState(),
)

internal data class StoredVitalsRead(val value: StoredVitals, val previous: Boolean)

/** Observation is fenced by both the repository generation and the durable envelope revision. */
data class VitalsReadState(val context: app.vezuna.data.VitalsSyncContext, val revision: Long, val local: WeightLocalState)

sealed interface VitalsWriteOutcome {
    data class Rejected(val reason: String) : VitalsWriteOutcome
    data class Committed(val commitId: String, val revision: Long, val storedCount: Int, val next: VitalsReadState?) : VitalsWriteOutcome
    data class Indeterminate(val operationId: String) : VitalsWriteOutcome
}
