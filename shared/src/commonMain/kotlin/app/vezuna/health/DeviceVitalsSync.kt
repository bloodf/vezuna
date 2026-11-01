package app.vezuna.health

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.VitalSample
import app.vezuna.domain.VitalsBatch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

/** Result of a full (re)import together with the cursor from which later changes are read. */
data class VitalsFetch(val batch: VitalsBatch, val nextCursor: String)

data class VitalsChangesPage(
    val batch: VitalsBatch,
    val nextCursor: String,
    val hasMore: Boolean,
    val cursorExpired: Boolean = false,
)

interface DeviceVitalsSource {
    suspend fun initial(): VitalsFetch
    suspend fun changes(cursor: String): VitalsChangesPage
}

interface VitalsCursorStore {
    suspend fun read(): String?
    suspend fun write(cursor: String)
}

/** The cursor is stored only after the samples were persisted, so a failed save is retried without data loss. */
class DeviceVitalsSync(
    private val source: DeviceVitalsSource,
    private val repository: VezunaRepository,
    private val cursors: VitalsCursorStore,
    private val clock: () -> Instant,
) {
    private val mutex = Mutex()

    suspend fun synchronize(): Int = mutex.withLock {
        val fetch = fetch(cursors.read())
        val count = repository.ingestDeviceVitals(fetch.batch, clock())
        cursors.write(fetch.nextCursor)
        count
    }

    private suspend fun fetch(cursor: String?): VitalsFetch {
        if (cursor == null) return source.initial()
        var current: String = cursor
        val samples = mutableListOf<VitalSample>()
        val deleted = mutableListOf<String>()
        do {
            val page = source.changes(current)
            if (page.cursorExpired) return source.initial()
            samples += page.batch.samples
            deleted += page.batch.deletedRecordIds
            current = page.nextCursor
        } while (page.hasMore)
        return VitalsFetch(VitalsBatch(samples, deleted), current)
    }
}
