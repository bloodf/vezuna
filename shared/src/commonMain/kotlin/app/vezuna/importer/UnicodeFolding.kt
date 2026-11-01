package app.vezuna.importer

/** NFKD decomposition; [foldedText] strips the resulting combining marks for accent-insensitive matching. */
internal expect fun normalizeNfkd(value: String): String

internal fun foldedText(value: String): String =
    normalizeNfkd(value).filterNot { it.code in COMBINING_MARKS }.lowercase()

private val COMBINING_MARKS = 0x0300..0x036F
