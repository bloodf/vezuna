package app.vezuna.importer

import kotlinx.datetime.Instant

/** Report-level facts read from the page text: both are drafts for the reviewer to confirm or edit. */
data class ReportHeader(val laboratoryName: String? = null, val orderedAt: Instant? = null)

object ReportHeaderParser {
    private const val MAX_NAME_LENGTH = 80
    private const val HEADER_LINES = 15
    private val NAME_START = Regex("^(?:laboratorio|laboratory)\\b")
    private val LABEL_PREFIX = Regex("^\\s*laborat[oó]rio\\s*[:\\-–]\\s*", RegexOption.IGNORE_CASE)
    private val ORDER_DATE = Regex(
        "(?:data\\s+(?:do\\s+|da\\s+|de\\s+)?(?:pedido|solicitacao|requisicao)|solicitado\\s+em|pedido\\s+em|" +
            "fecha\\s+(?:del\\s+|de\\s+)?(?:pedido|solicitud))\\D{0,15}(\\d{2}/\\d{2}/\\d{4})",
    )

    fun parse(text: String): ReportHeader {
        val lines = OcrTextParser.normalizeText(text).split('\n').filter { it.isNotBlank() }
        return ReportHeader(laboratoryName(lines), orderedAt(lines))
    }

    private fun laboratoryName(lines: List<String>): String? = lines.take(HEADER_LINES).firstNotNullOfOrNull { line ->
        // Only a line that begins with the word, near the top: "Exames laboratoriais" or "Responsável do laboratório" are not names.
        if (line.length > MAX_NAME_LENGTH || !NAME_START.containsMatchIn(foldedText(line))) return@firstNotNullOfOrNull null
        line.replace(LABEL_PREFIX, "").trim().takeIf { name -> name.length >= 3 && name.any(Char::isLetter) }
    }

    private fun orderedAt(lines: List<String>): Instant? = lines.firstNotNullOfOrNull { line ->
        ORDER_DATE.find(foldedText(line))?.let { OcrTextParser.parseDate(it.groupValues[1]) }
    }
}
