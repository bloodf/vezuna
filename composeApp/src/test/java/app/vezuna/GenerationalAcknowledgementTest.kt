package app.vezuna

import app.vezuna.security.GenerationalFileStore
import app.vezuna.security.VaultWriteResult
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*

class GenerationalAcknowledgementTest {
    private fun fixture(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("vezuna-owned-ack-").toFile()
        try { action(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun realReplacementsAcknowledgeBothWholeGenerations() = fixture { dir ->
        val store = GenerationalFileStore(dir)
        assertEquals(VaultWriteResult.Acknowledged, store.writeAcknowledged("vitals", "old-data+T0".toByteArray()))
        assertEquals(VaultWriteResult.Acknowledged, store.writeAcknowledged("vitals", "new-data+T1".toByteArray()))
        assertEquals("new-data+T1", store.readGeneration("vitals")!!.bytes.decodeToString())
        assertFalse(store.readGeneration("vitals")!!.previous)
        assertEquals("old-data+T0", store.readPrevious("vitals")!!.decodeToString())
    }

    @Test fun faultsBeforeReplacementRejectAndAfterRotationExposeWholeRecovery() {
        for (stage in GenerationalFileStore.WriteStage.entries) fixture { dir ->
            val normal = GenerationalFileStore(dir)
            normal.write("vitals", "old-data+T0".toByteArray())
            val faulty = GenerationalFileStore(dir, beforeAcknowledgedStage = { if (it == stage) throw IOException("injected $stage") })
            val outcome = faulty.writeAcknowledged("vitals", "new-data+T1".toByteArray())
            if (stage <= GenerationalFileStore.WriteStage.BEFORE_ROTATION) assertIs<VaultWriteResult.Rejected>(outcome)
            else assertIs<VaultWriteResult.Indeterminate>(outcome)
            val recovered = normal.readGeneration("vitals")!!
            assertEquals(if (stage == GenerationalFileStore.WriteStage.AFTER_INSTALL) "new-data+T1" else "old-data+T0", recovered.bytes.decodeToString())
            assertEquals(stage == GenerationalFileStore.WriteStage.AFTER_ROTATION, recovered.previous)
        }
    }

    @Test fun directorySyncFailureIsUncertainAfterVisibleReplacement() = fixture { dir ->
        GenerationalFileStore(dir).write("vitals", "old-data+T0".toByteArray())
        val store = GenerationalFileStore(dir, acknowledgeDirectory = { throw IOException("fsync directory failed") })
        assertIs<VaultWriteResult.Indeterminate>(store.writeAcknowledged("vitals", "new-data+T1".toByteArray()))
        assertEquals("new-data+T1", File(dir, "vitals").readText())
        assertEquals("old-data+T0", File(dir, "vitals.prev").readText())
    }
}
