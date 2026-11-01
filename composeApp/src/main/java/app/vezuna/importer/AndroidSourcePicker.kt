package app.vezuna.importer

import android.app.AlertDialog
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.vezuna.NativeKey
import app.vezuna.failNative
import app.vezuna.nativeMessage
import app.vezuna.resolve
import app.vezuna.ui.PickedSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AndroidSourcePicker(
    private val activity: ComponentActivity,
    private val extraction: NativeOcrExtractionService,
) {
    private var pending: CompletableDeferred<PickedSource?>? = null
    private var cameraUri: Uri? = null
    private val mutableLastExtraction = MutableStateFlow<OcrExtractionResult?>(null)
    val lastExtraction: StateFlow<OcrExtractionResult?> = mutableLastExtraction.asStateFlow()

    private val imageLauncher = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) complete(null) else importUri(uri, "image/*")
    }
    private val pdfLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) complete(null) else importUri(uri, "application/pdf")
    }
    private val cameraLauncher = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (!success || uri == null) complete(null) else importUri(uri, "image/jpeg")
    }

    suspend fun pick(): PickedSource? {
        if (pending != null) failNative(NativeKey.SOURCE_IMPORT_ACTIVE)
        val result = CompletableDeferred<PickedSource?>()
        pending = result
        AlertDialog.Builder(activity)
            .setTitle(nativeMessage(NativeKey.IMPORT_SOURCE_TITLE).resolve(activity))
            .setMessage(nativeMessage(NativeKey.IMPORT_SOURCE_MESSAGE).resolve(activity))
            .setItems(arrayOf(
                nativeMessage(NativeKey.TAKE_PHOTO).resolve(activity),
                nativeMessage(NativeKey.CHOOSE_IMAGE).resolve(activity),
                nativeMessage(NativeKey.CHOOSE_PDF).resolve(activity),
            )) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> launchCamera(result)
                    1 -> imageLauncher.launch("image/*")
                    2 -> pdfLauncher.launch(arrayOf("application/pdf"))
                }
            }
            .setOnCancelListener { complete(null) }
            .show()
        return result.await()
    }

    fun clearExtraction() { mutableLastExtraction.value = null }

    private fun launchCamera(result: CompletableDeferred<PickedSource?>) = try {
        val directory = File(activity.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("lab-", ".jpg", directory)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        cameraUri = uri
        cameraLauncher.launch(uri)
    } catch (error: Throwable) {
        pending = null
        result.completeExceptionally(error)
    }

    private fun importUri(uri: Uri, requestedType: String) {
        val result = pending ?: return
        activity.lifecycleScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { input ->
                        val data = input.readBytes()
                        if (data.size > MAX_SOURCE_BYTES) failNative(NativeKey.SOURCE_TOO_LARGE)
                        data
                    } ?: failNative(NativeKey.SOURCE_OPEN_FAILED)
                    val name = displayName(uri) ?: if (requestedType == "application/pdf") "lab.pdf" else "lab-image.jpg"
                    Triple(name, requestedType, bytes)
                }
                val (name, type, bytes) = source
                val extracted = if (type == "application/pdf") {
                    extraction.extractPdf(bytes, name)
                } else {
                    extraction.extractImage(bytes)
                }
                mutableLastExtraction.value = extracted
                val candidates = extracted.pages.flatMap { page -> parseExtractedCandidates(page.text) }
                complete(PickedSource(name, type, bytes, candidates))
            } catch (error: Throwable) {
                pending = null
                result.completeExceptionally(error)
            }
        }
    }

    private fun complete(source: PickedSource?) {
        pending?.complete(source)
        pending = null
        cameraUri = null
    }

    private fun displayName(uri: Uri): String? = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

    private companion object { const val MAX_SOURCE_BYTES = 64 * 1024 * 1024 }
}
