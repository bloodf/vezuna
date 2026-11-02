package app.vezuna.ui.records

/** The JVM target only runs tests; it has no PDF renderer. */
actual suspend fun renderPdfPage(bytes: ByteArray, index: Int, widthPx: Int): RenderedPdfPage? = null
