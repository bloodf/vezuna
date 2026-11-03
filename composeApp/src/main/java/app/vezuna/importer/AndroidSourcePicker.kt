package app.vezuna.importer

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.vezuna.NativeKey
import app.vezuna.nativeMessage
import app.vezuna.resolve
import app.vezuna.ui.PickedSource
import app.vezuna.ui.entry.ExtractedPage
import app.vezuna.ui.entry.ExtractionDetails
import app.vezuna.ui.entry.ImportFailure
import app.vezuna.ui.entry.ImportFailureReason
import app.vezuna.ui.entry.ImportSession
import app.vezuna.ui.entry.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opens the system camera, photo picker or document picker for the source the shared Add a record screen asked
 * for, then extracts text on the device (unless the session is store-only: the document vault keeps the file and
 * reads nothing). Cancelling any system UI returns null; failures are [ImportFailure]s.
 */
class AndroidSourcePicker(
    private val activity: ComponentActivity,
    private val extraction: NativeOcrExtractionService,
) {
    private var pending: CompletableDeferred<PickedSource?>? = null
    private var session: ImportSession? = null
    private var cameraUri: Uri? = null
    /** Photos of one report, in capture order; cleared whenever a pick ends. */
    private val photos = mutableListOf<ByteArray>()

    private val photoLauncher = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        if (uris.isEmpty()) complete(null) else importPhotos(uris)
    }
    private val fileLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) complete(null) else importFile(uri)
    }
    private val cameraLauncher = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (!success || uri == null) finishPhotos() else addPhoto(uri)
    }

    suspend fun pick(session: ImportSession): PickedSource? {
        if (pending != null) return null
        val result = CompletableDeferred<PickedSource?>()
        pending = result
        this.session = session
        try {
            when (session.requestedKind) {
                SourceKind.CAMERA -> launchCamera()
                SourceKind.PHOTOS -> photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                SourceKind.FILE -> fileLauncher.launch(arrayOf("application/pdf", "image/*"))
            }
        } catch (error: ActivityNotFoundException) {
            fail(ImportFailure(if (session.requestedKind == SourceKind.CAMERA) ImportFailureReason.CAMERA_UNAVAILABLE else ImportFailureReason.UNREADABLE))
        } catch (error: Exception) {
            fail(error)
        }
        try {
            return result.await()
        } catch (cancelled: CancellationException) {
            // The person stopped the import: end extraction (it deletes its own temporary copy) and keep nothing.
            extracting?.cancel()
            extracting = null
            clearCameraCache()
            if (pending === result) {
                pending = null
                this.session = null
                cameraUri = null
                photos.clear()
            }
            throw cancelled
        }
    }

    private var extracting: Job? = null

    /** Drops an in-flight pick so a retained caller does not wait on a destroyed Activity. */
    fun close() {
        pending?.cancel()
        pending = null
        session = null
        photos.clear()
        clearCameraCache()
    }

    private fun launchCamera() {
        val directory = File(activity.cacheDir, "camera").apply { mkdirs() }
        clearCameraCache()
        val file = File.createTempFile("shot-", ".jpg", directory)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        cameraUri = uri
        cameraLauncher.launch(uri)
    }

    /** A shot is plaintext until it is read into memory; none may stay on disk after that. */
    private fun clearCameraCache() {
        File(activity.cacheDir, "camera").listFiles()?.forEach { it.delete() }
    }

    private fun addPhoto(uri: Uri) {
        if (pending == null) return
        activity.lifecycleScope.launch {
            try {
                photos += withContext(Dispatchers.IO) { readBytes(uri, PHOTO_NAME) }
                if (photos.size >= MAX_PHOTOS) finishPhotos() else askForAnotherPhoto()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    /** System dialog between camera shots: another page of the same report, or done. */
    private fun askForAnotherPhoto() {
        if (activity.isFinishing || activity.isDestroyed) return finishPhotos()
        AlertDialog.Builder(activity)
            .setTitle(nativeMessage(NativeKey.TAKE_PHOTO).resolve(activity))
            .setMessage(nativeMessage(NativeKey.PHOTO_ADDED_MESSAGE, photos.size).resolve(activity))
            .setPositiveButton(nativeMessage(NativeKey.ADD_ANOTHER_PHOTO).resolve(activity)) { dialog, _ ->
                dialog.dismiss()
                try {
                    launchCamera()
                } catch (error: Throwable) {
                    finishPhotos()
                }
            }
            .setNegativeButton(nativeMessage(NativeKey.PHOTOS_DONE).resolve(activity)) { dialog, _ ->
                dialog.dismiss()
                finishPhotos()
            }
            .setOnCancelListener { finishPhotos() }
            .show()
    }

    /** One report from every photo taken so far; every page is stored with the document. */
    private fun finishPhotos() {
        clearCameraCache()
        val taken = photos.toList()
        photos.clear()
        if (taken.isEmpty()) return complete(null)
        val owner = pending
        val picked = session
        extracting = activity.lifecycleScope.launch { extractPhotos(taken, owner, picked) }
    }

    private fun importPhotos(uris: List<Uri>) {
        val owner = pending
        val picked = session
        extracting = activity.lifecycleScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) { uris.take(MAX_PHOTOS).map { readBytes(it, displayName(it) ?: PHOTO_NAME) } }
                extractPhotos(pages, owner, picked)
            } catch (error: Throwable) {
                fail(error, owner = owner)
            }
        }
    }

    /** [owner] is the pick this extraction belongs to: a stopped extraction can never complete or fail a later pick. */
    private suspend fun extractPhotos(taken: List<ByteArray>, owner: CompletableDeferred<PickedSource?>?, picked: ImportSession?) {
        if (picked?.storeOnly == true) {
            val name = if (taken.size == 1) "document.jpg" else "document-${taken.size}-pages.jpg"
            return complete(PickedSource(name, "image/jpeg", taken.first(), emptyList(), additionalPages = taken.drop(1)), owner)
        }
        val name = if (taken.size == 1) PHOTO_NAME else "lab-photos-${taken.size}.jpg"
        try {
            if (pending === owner) picked?.reading(name)
            val pages = taken.flatMapIndexed { index, bytes ->
                if (pending === owner) picked?.progress(index + 1, taken.size)
                extraction.extractImage(bytes).pages.map { it.copy(pageNumber = index + 1) }
            }
            if (pending !== owner) return
            complete(source(name, "image/jpeg", taken.first(), pages, taken.drop(1)), owner)
        } catch (error: Throwable) {
            fail(error, name, owner)
        }
    }

    private fun importFile(uri: Uri) {
        val owner = pending
        val picked = session
        extracting = activity.lifecycleScope.launch {
            var name: String? = null
            try {
                val type = activity.contentResolver.getType(uri)
                val isPdf = type == "application/pdf"
                val storeOnly = picked?.storeOnly == true
                name = displayName(uri) ?: when {
                    storeOnly -> if (isPdf) "document.pdf" else "document.jpg"
                    isPdf -> "lab.pdf"
                    else -> PHOTO_NAME
                }
                val bytes = withContext(Dispatchers.IO) { readBytes(uri, name) }
                if (storeOnly) {
                    return@launch complete(PickedSource(name, if (isPdf) "application/pdf" else (type ?: "image/jpeg"), bytes, emptyList()), owner)
                }
                if (pending === owner) picked?.reading(name)
                val extracted = if (isPdf) {
                    extraction.extractPdf(bytes, name) { page, count -> if (pending === owner) picked?.progress(page, count) }
                } else {
                    extraction.extractImage(bytes)
                }
                if (pending !== owner) return@launch
                complete(source(name, if (isPdf) "application/pdf" else (type ?: "image/jpeg"), bytes, extracted.pages, emptyList()), owner)
            } catch (error: Throwable) {
                fail(error, name, owner)
            }
        }
    }

    /** Builds the source and publishes how each page was read (optional details in review). */
    private fun source(name: String, type: String, first: ByteArray, pages: List<OcrPageText>, more: List<ByteArray>): PickedSource {
        val text = pages.joinToString("\n\n") { it.text }.trim()
        val header = parseReportHeader(text)
        val candidates = pages.flatMap { parseExtractedCandidates(it.text) }
            .distinctBy { listOf(it.testCode, it.rawValue, it.rawUnit, it.collectedAt) }
        session?.publish(
            ExtractionDetails(
                name,
                pages.map { ExtractedPage(it.pageNumber, it.source == OcrPageText.Source.EMBEDDED_PDF_TEXT, it.text) },
                candidates.size,
            ),
        )
        return PickedSource(name, type, first, candidates, header.laboratoryName, header.orderedAt, more)
    }

    private fun readBytes(uri: Uri, name: String): ByteArray = activity.contentResolver.openInputStream(uri)?.use { input ->
        val data = input.readBytes()
        if (data.size > MAX_SOURCE_BYTES) throw ImportFailure(ImportFailureReason.TOO_LARGE, name)
        data
    } ?: throw ImportFailure(ImportFailureReason.UNREADABLE, name)

    private fun fail(error: Throwable, name: String? = null, owner: CompletableDeferred<PickedSource?>? = pending) {
        val result = pending ?: return
        if (owner !== result) return
        clearCameraCache()
        pending = null
        session = null
        cameraUri = null
        photos.clear()
        when (error) {
            is CancellationException -> result.cancel(error)
            is ImportFailure -> result.completeExceptionally(error)
            else -> result.completeExceptionally(ImportFailure(ImportFailureReason.UNREADABLE, name))
        }
    }

    private fun complete(source: PickedSource?, owner: CompletableDeferred<PickedSource?>? = pending) {
        if (owner == null || pending !== owner) return
        owner.complete(source)
        pending = null
        session = null
        cameraUri = null
        photos.clear()
    }

    private fun displayName(uri: Uri): String? = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
        const val MAX_PHOTOS = 10
        const val PHOTO_NAME = "lab-image.jpg"
    }
}
