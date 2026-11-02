package app.vezuna.ui.export

/** Outcome of handing a file to the system save sheet. Cancelling is not a failure and is never reported as one. */
enum class SaveOutcome { SAVED, CANCELLED }

/**
 * System save and share sheets, installed by each platform (Android activity, iOS bridge). Both receive the exact
 * bytes the preview rendered. Null hooks mean the platform offers no file delivery here (tests, previews), and the
 * screens say so instead of pretending.
 */
object ExportPlatform {
    /** Throws when writing fails; returns [SaveOutcome.CANCELLED] when the user closes the sheet. */
    var save: (suspend (fileName: String, mimeType: String, bytes: ByteArray) -> SaveOutcome)? = null

    /** Opens the system share sheet; throws when it cannot be opened. What the user does next belongs to the OS. */
    var share: (suspend (fileName: String, mimeType: String, bytes: ByteArray) -> Unit)? = null

    const val PDF = "application/pdf"
    const val CSV = "text/csv"
}
