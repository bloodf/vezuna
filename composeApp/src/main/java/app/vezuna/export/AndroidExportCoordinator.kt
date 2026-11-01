package app.vezuna.export

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import app.vezuna.NativeKey
import app.vezuna.failNative
import app.vezuna.nativeMessage
import app.vezuna.resolve
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class NativeExport(val fileName: String, val mimeType: String, val bytes: ByteArray)

class AndroidExportCoordinator(private val activity: ComponentActivity) {
    private var pendingSave: CompletableDeferred<Result<Unit>>? = null
    private var pendingExport: NativeExport? = null

    private val csvSaver = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { saveTo(it) }
    private val pdfSaver = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { saveTo(it) }
    private val backupSaver = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { saveTo(it) }

    suspend fun save(export: NativeExport): Result<Unit> {
        if (pendingSave != null) failNative(NativeKey.SAVE_ALREADY_ACTIVE)
        val result = CompletableDeferred<Result<Unit>>()
        pendingSave = result
        pendingExport = export
        try {
            when (export.mimeType) {
                "text/csv" -> csvSaver.launch(export.fileName)
                "application/pdf" -> pdfSaver.launch(export.fileName)
                else -> backupSaver.launch(export.fileName)
            }
        } catch (error: Throwable) {
            pendingSave = null
            pendingExport = null
            result.complete(Result.failure(error))
        }
        return result.await()
    }

    suspend fun share(export: NativeExport): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(activity.cacheDir, "exports").apply { mkdirs() }
            directory.listFiles()?.forEach { file -> if (file.isFile && file.age() > SHARE_CACHE_TTL_MS) file.delete() }
            val safeName = export.fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "vezuna-export" }
            val file = File(directory, "${System.currentTimeMillis()}-$safeName")
            file.writeBytes(export.bytes)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = export.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, safeName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, nativeMessage(NativeKey.SHARE_CHOOSER, safeName).resolve(activity)))
        }
    }

    private fun saveTo(uri: Uri?) {
        val result = pendingSave ?: return
        val export = pendingExport
        pendingSave = null
        pendingExport = null
        if (uri == null || export == null) {
            result.complete(Result.failure(UserCancelledExport()))
            return
        }
        try {
            activity.contentResolver.openOutputStream(uri, "rwt")?.use { it.write(export.bytes) }
                ?: failNative(NativeKey.DESTINATION_OPEN_FAILED)
            result.complete(Result.success(Unit))
        } catch (error: Throwable) {
            result.complete(Result.failure(error))
        }
    }

    private fun File.age(): Long = System.currentTimeMillis() - lastModified()

    private companion object { const val SHARE_CACHE_TTL_MS = 24 * 60 * 60 * 1000L }
}

class UserCancelledExport : Exception("Save cancelled")
