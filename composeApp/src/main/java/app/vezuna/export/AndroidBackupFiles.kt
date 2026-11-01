package app.vezuna.export

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.vezuna.NativeKey
import app.vezuna.failNative
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.security.BackupService
import app.vezuna.ui.BackupFileAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Storage Access Framework pick and save for `.vhbak` files. Must be created before the activity starts. */
class AndroidBackupFiles(private val activity: ComponentActivity, private val exports: AndroidExportCoordinator) {
    private var pending: CompletableDeferred<Uri?>? = null

    private val opener = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pending?.complete(uri)
        pending = null
    }

    val access = BackupFileAccess(pick = ::pick, save = ::save)

    private suspend fun pick(): ByteArray? {
        if (pending != null) failNative(NativeKey.SAVE_ALREADY_ACTIVE)
        val chosen = CompletableDeferred<Uri?>()
        pending = chosen
        try {
            // Backups have no registered MIME type, so every document is offered.
            opener.launch(arrayOf("*/*"))
        } catch (error: Throwable) {
            pending = null
            throw error
        }
        val uri = chosen.await() ?: return null
        return withContext(Dispatchers.IO) { read(uri) }
    }

    private fun read(uri: Uri): ByteArray {
        val limit = BackupService.MAX_BACKUP_BYTES
        declaredSize(uri)?.let { if (it > limit) failMessage(L10nKey.BACKUP_FILE_UNREADABLE) }
        val input = activity.contentResolver.openInputStream(uri) ?: failMessage(L10nKey.BACKUP_FILE_UNREADABLE)
        // Chunked, bounded read (readNBytes needs API 33). The declared size can lie, so the cap is enforced here too.
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                if (out.size() + count > limit) failMessage(L10nKey.BACKUP_FILE_UNREADABLE)
                out.write(chunk, 0, count)
            }
            return out.toByteArray()
        }
    }

    private fun declaredSize(uri: Uri): Long? = activity.contentResolver
        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    private suspend fun save(fileName: String, bytes: ByteArray): Boolean =
        exports.save(NativeExport(fileName, "application/octet-stream", bytes)).fold(
            onSuccess = { true },
            onFailure = { if (it is UserCancelledExport) false else throw it },
        )

    private companion object { const val CHUNK_BYTES = 64 * 1024 }
}
