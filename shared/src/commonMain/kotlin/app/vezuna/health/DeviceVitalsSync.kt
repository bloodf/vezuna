package app.vezuna.health

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.VitalsWindow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** [replaceFrom] is the first local day to re-read (steps, heart rate, weight); weights are also read back to [weightsFrom]. */
data class VitalsReadRequest(val replaceFrom: LocalDate, val weightsFrom: LocalDate, val zone: TimeZone)

interface DeviceVitalsSource {
    suspend fun read(request: VitalsReadRequest): VitalsBatch
}

/**
 * Every sync re-reads a trailing window from the platform and replaces that window in the linked profile.
 * There is no change token or anchor to lose: the "synced up to" time is stored in the same write as the data,
 * so a failed save is retried over the same window without loss. Data older than the window that arrives late
 * (a device syncing after more than a week) is only picked up within the first-sync horizon of 30 days.
 */
class DeviceVitalsSync(
    private val source: DeviceVitalsSource,
    private val repository: VezunaRepository,
    private val clock: () -> Instant,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val beforeSync: suspend () -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun synchronize(): Int = mutex.withLock {
        beforeSync()
        val profileId = repository.linkedHealthProfileId() ?: throw IllegalStateException("No profile to link")
        val now = clock()
        val lastSync = repository.readProfile(profileId).vitals.lastSync
        val tz = zone()
        val batch = source.read(VitalsReadRequest(VitalsWindow.replaceFrom(lastSync, now, tz), VitalsWindow.weightsFrom(lastSync, now, tz), tz))
        repository.ingestDeviceVitals(batch, now)
    }
}
