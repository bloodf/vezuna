package app.vezuna.export

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.vezuna.NativeKey
import app.vezuna.failNative
import app.vezuna.security.BackupService
import app.vezuna.ui.BackupFileAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readNBytes(limit + 1) }
            ?: failNative(NativeKey.DESTINATION_OPEN_FAILED)
        // An oversized file is handed on as-is; BackupService.kind refuses it as not a backup.
        return bytes
    }

    private suspend fun save(fileName: String, bytes: ByteArray): Boolean =
        exports.save(NativeExport(fileName, "application/octet-stream", bytes)).fold(
            onSuccess = { true },
            onFailure = { if (it is UserCancelledExport) false else throw it },
        )
}
