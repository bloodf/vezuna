package app.vezuna.importer

import app.vezuna.domain.NumberAmbiguity
import app.vezuna.domain.ReferenceRange
import app.vezuna.ui.ExtractedValueKind
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import app.vezuna.security.normalizeNfc

data class ParsedOcrMeasurement(
    val displayName: String,
    val value: String,
    val unit: String,
    val referenceRange: ReferenceRange = ReferenceRange(),
    val collectedAt: Instant? = null,
    val valueKind: ExtractedValueKind = ExtractedValueKind.NUMERIC,
    /** Original OCR token, set only when its reading is ambiguous (see [NumberAmbiguity]). */
    val rawToken: String? = null,
)

/** Conservative Portuguese clinical-row parsing. Ambiguous and historical rows stay out of review. */
object OcrTextParser {
    private const val NUMBER = "[+-]?(?:\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+\\.\\d+|\\d+(?:,\\d+)?)"
    private val UNITS = listOf(
        "ng/mL FEU", "µUI/mL", "μUI/mL", "uUI/mL", "mUI/mL", "UI/mL", "U/mL",
        "ng/dL", "ng/mL", "pg/mL", "µg/dL", "μg/dL", "ug/dL", "µg/L", "μg/L", "ug/L",
        "mg/dL", "mg/L", "g/dL", "g/L", "mmol/L", "µmol/L", "μmol/L", "umol/L", "nmol/L",
        "10^12/L", "10^9/L", "10^6/µL", "10^6/μL", "10^3/µL", "10^3/μL",
        "U/L", "UI/L", "/µL", "/μL", "/uL", "/mL", "fL", "%",
        "/mm3", "/mm³", "10^3/mm3", "10^3/mm³", "10^6/mm3", "10^6/mm³", "10³/µL", "10³/μL", "10³/mm³", "10⁶/µL", "mEq/L", "mIU/mL", "mIU/L", "IU/mL", "IU/L", "mm/h",
    ).sortedByDescending { it.length }
    private val UNIT_PATTERN = UNITS.joinToString("|") { Regex.escape(it) }
    private val UNIT = Regex("\\s*($UNIT_PATTERN)(?=\\s|$|[,.;)\\]])", RegexOption.IGNORE_CASE)
    private val NUMBER_AT_END = Regex("($NUMBER)$")
    private val NUMBER_UNIT_AT_END = Regex("$NUMBER\\s*(?:$UNIT_PATTERN)$", RegexOption.IGNORE_CASE)
    private val QUALIFIED_NUMERIC = Regex(
        "^(.+?)\\s+(inferior(?:\\s+[àa])?|menor\\s+que|superior(?:\\s+[àa])?|maior\\s+que)\\s*[:=]?\\s*($NUMBER)\\s*($UNIT_PATTERN)(?=\\s|$|[,.;)\\]])(.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val RANGE = Regex(
        "($NUMBER)\\s*(?:a|até|ate|to|–|—|-)\\s*($NUMBER)(?:\\s*($UNIT_PATTERN))?",
        RegexOption.IGNORE_CASE,
    )
    private val THRESHOLD = Regex(
        "(inferior(?:\\s+[àa])?|menor\\s+que|até|ate|no\\s+m[áa]ximo|superior(?:\\s+[àa])?|maior\\s+que|acima\\s+de|<=|>=|<|>|≤|≥)\\s*[:=]?\\s*($NUMBER)(?:\\s*($UNIT_PATTERN))?",
        RegexOption.IGNORE_CASE,
    )
    private val QUALITATIVE_VALUES = listOf("Não reagente", "Nao reagente", "Ausente", "Presente", "Negativo", "Positivo", "Reagente")
    private val QUALITATIVE = Regex(
        "^(.+?)\\s+(${QUALITATIVE_VALUES.joinToString("|") { Regex.escape(it) }})(?:\\s+(${QUALITATIVE_VALUES.joinToString("|") { Regex.escape(it) }}))?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_DATE = Regex("\\s+(\\d{2}/\\d{2}/\\d{4})\\s*$")
    private val LEADING_DATE = Regex("^(\\d{2}/\\d{2}/\\d{4})\\s+(.+)$")
    private val SKIP_PREFIXES = listOf(
        "resultado", "resultados", "material", "metodo", "nota", "notas", "observacao", "referencia",
        "historico", "tabela de referencia", "pagina", "assinatura", "validacao", "autenticacao", "cnes",
        "dasa", "rua ", "avenida ", "av. ", "paciente", "nascimento", "laboratorio", "medico", "crm",
    )
    private val SKIP_CONTAINS = listOf(
        "a conclusao diagnostica", "valores de referencia", "bibliogra", "codigo de validacao",
    )
    private val NON_ANALYTE_PREFIXES = listOf(
        "desejavel", "normal", "baixo", "alto", "risco", "homens", "mulheres", "adultos", "prematuros",
        "populacao saudavel", "com jejum", "extremo", "quando os niveis", "os valores de", "sensibilidade do metodo",
        "valor de referencia", "inferior a", "superior a", "* valor padrao",
    )

    fun normalizeText(value: String): String {
        var normalized = value
        mapOf("ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl", "ﬅ" to "st", "ﬆ" to "st")
            .forEach { (ligature, replacement) -> normalized = normalized.replace(ligature, replacement) }
        normalized = normalized.replace(Regex("[\u00a0\u202f\u2007\u2009]"), " ").replace("\u00ad", "")
        normalized = normalizeNfc(normalized).replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split('\n').joinToString("\n") { line -> line.replace(Regex("[\t\u000c\u000b ]+"), " ").trim() }
    }

    fun parseMeasurementLine(line: String): ParsedOcrMeasurement? {
        val normalized = normalizeText(line)
        if (normalized.isEmpty() || administrative(normalized)) return null
        val (row, date) = extractConfidentDate(normalized)
        return parseQualifiedNumeric(row, date) ?: parseNumeric(row, date) ?: parseQualitative(row, date)
    }

    fun parseMeasurements(text: String): List<ParsedOcrMeasurement> {
        val results = mutableListOf<ParsedOcrMeasurement>()
        var historical = false
        normalizeText(text).split('\n').forEach { line ->
            val flat = folded(line)
            if (flat.startsWith("historico")) {
                historical = true
                return@forEach
            }
            val candidate = parseMeasurementLine(line)
            if (historical) {
                if (candidate == null || flat.startsWith("resultado")) return@forEach
                historical = false
            }
            if (candidate != null) results += candidate
        }
        return results
    }

    private fun ambiguousToken(token: String): String? = token.trim().takeIf(NumberAmbiguity::isAmbiguousThousands)

    internal fun canonicalNumber(raw: String): String {
        var value = raw.trim().removePrefix("+")
        value = if (',' in value) {
            value.replace(".", "").replace(',', '.')
        } else if (Regex("[+-]?[1-9]\\d{0,2}(?:\\.\\d{3})+").matches(value)) {
            value.replace(".", "")
        } else value
        if (value.startsWith(".")) value = "0$value"
        if (value.startsWith("-.")) value = value.replaceFirst("-.", "-0.")
        return value
    }

    fun canonicalTestCode(value: String): String = normalizeNfc(value)
        .uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    private fun parseQualifiedNumeric(line: String, date: Instant?): ParsedOcrMeasurement? {
        val match = QUALIFIED_NUMERIC.matchEntire(line) ?: return null
        val name = match.groupValues[1].trim(' ', '.', ';', ':', '-')
        if (!validAnalyte(name) || NUMBER_UNIT_AT_END.containsMatchIn(name)) return null
        val value = canonicalNumber(match.groupValues[3])
        val unit = match.groupValues[4].replace('μ', 'µ')
        return ParsedOcrMeasurement(name, value, unit, parseReference(match.groupValues[5], unit), date, rawToken = ambiguousToken(match.groupValues[3]))
    }

    private data class NumberUnit(
        val numberMatch: MatchResult,
        val unitMatch: MatchResult,
        val value: String,
        val unit: String,
    )

    private fun parseNumeric(line: String, date: Instant?): ParsedOcrMeasurement? {
        val candidates = mutableListOf<NumberUnit>()
        UNIT.findAll(line).forEach { unitMatch ->
            val prefix = line.substring(0, unitMatch.range.first).trimEnd()
            val numberMatch = NUMBER_AT_END.find(prefix) ?: return@forEach
            candidates += NumberUnit(numberMatch, unitMatch, canonicalNumber(numberMatch.groupValues[1]), unitMatch.groupValues[1].replace('μ', 'µ'))
        }
        if (candidates.isEmpty()) return null
        val first = candidates.first()
        val name = line.substring(0, first.numberMatch.range.first).trim(' ', '.', ';', ':', '-')
        if (!validAnalyte(name)) return null
        var primaryIndex = 0
        if (candidates.size >= 2) {
            val second = candidates[1]
            val between = line.substring(first.unitMatch.range.last + 1, second.numberMatch.range.first).trim()
            if (between.isEmpty()) primaryIndex = 1
        }
        val primary = candidates[primaryIndex]
        val rest = line.substring(primary.unitMatch.range.last + 1).trim(' ', ';')
        return ParsedOcrMeasurement(name, primary.value, primary.unit, parseReference(rest, primary.unit), date, rawToken = ambiguousToken(primary.numberMatch.groupValues[1]))
    }

    private fun parseQualitative(line: String, date: Instant?): ParsedOcrMeasurement? {
        val match = QUALITATIVE.matchEntire(line) ?: return null
        val name = match.groupValues[1].trim(' ', '.', ';', ':', '-')
        if (!validAnalyte(name)) return null
        return ParsedOcrMeasurement(
            displayName = name,
            value = match.groupValues[2],
            unit = "",
            referenceRange = ReferenceRange(text = match.groupValues[3].ifBlank { null }),
            collectedAt = date,
            valueKind = ExtractedValueKind.QUALITATIVE,
        )
    }

    private data class FoundReference(val range: ReferenceRange, val start: Int, val unit: String?)

    /**
     * Bounds for reference text a reviewer typed: only when it holds exactly one range or one-sided
     * `<`, `>`, `≤`, `≥` threshold. Several ranges (by sex or age) or a deferral give no bounds, never a guess.
     */
    fun referenceFromText(text: String, resultUnit: String): ReferenceRange {
        val clean = normalizeText(text).trim()
        if (deferredReference(clean)) return ReferenceRange(text = clean)
        val single = findReferences(clean).singleOrNull() ?: return ReferenceRange(text = clean.ifBlank { null })
        return single.range
    }

    private fun deferredReference(text: String): Boolean {
        val flat = folded(text)
        return text.isNotEmpty() && (
            Regex("vide.*intervalo|confira\\s+na\\s+tabela|tabela\\s+de\\s+referencia|ver\\s+referencia").containsMatchIn(flat) ||
                Regex("nao\\s+apresenta.*referencia.*definid|sem\\s+valor\\s+de\\s+referencia\\s+definido").containsMatchIn(flat)
            )
    }

    private fun parseReference(rest: String, resultUnit: String): ReferenceRange {
        val text = rest.trim()
        if (deferredReference(text)) return ReferenceRange(text = text)
        val found = findReferences(text)
        val selected = found.lastOrNull { it.unit == resultUnit } ?: found.lastOrNull()
        return selected?.range ?: ReferenceRange(text = text.ifBlank { null })
    }

    private fun findReferences(text: String): List<FoundReference> {
        val references = mutableListOf<FoundReference>()
        RANGE.findAll(text).forEach { match ->
            val lower = numericValue(match.groupValues[1])
            val upper = numericValue(match.groupValues[2])
            if (lower <= upper) {
                val unit = match.groupValues[3].ifBlank { null }?.replace('μ', 'µ')
                references += FoundReference(ReferenceRange(lower, upper, match.value.trim(), unit), match.range.first, unit)
            }
        }
        THRESHOLD.findAll(text).forEach { match ->
            if (references.any { match.range.first >= it.start && match.range.last <= it.start + it.range.text.orEmpty().length - 1 }) return@forEach
            val operator = folded(match.groupValues[1])
            val value = numericValue(match.groupValues[2])
            val unit = match.groupValues[3].ifBlank { null }?.replace('μ', 'µ')
            val upperBound = operator.startsWith("inferior") || operator.startsWith("menor") || operator.startsWith("ate") ||
                operator.startsWith("no maximo") || operator.startsWith("<") || operator.startsWith("≤")
            val range = if (upperBound) {
                ReferenceRange(upper = value, text = match.value.trim(), unit = unit)
            } else {
                ReferenceRange(lower = value, text = match.value.trim(), unit = unit)
            }
            references += FoundReference(range, match.range.first, unit)
        }
        return references.sortedBy { it.start }
    }

    private fun numericValue(raw: String): Double = canonicalNumber(raw).toDouble().also { require(it.isFinite()) }

    private fun extractConfidentDate(line: String): Pair<String, Instant?> {
        TRAILING_DATE.find(line)?.let { match ->
            parseDate(match.groupValues[1])?.let { return line.substring(0, match.range.first).trimEnd() to it }
        }
        LEADING_DATE.matchEntire(line)?.let { match ->
            parseDate(match.groupValues[1])?.let { return match.groupValues[2] to it }
        }
        return line to null
    }

    private fun parseDate(raw: String): Instant? = try {
        val parts = raw.split('/').map(String::toInt)
        val date = LocalDate(parts[2], parts[1], parts[0])
        if (date.year in 1900..2200) date.atStartOfDayIn(TimeZone.UTC).plus(12, DateTimeUnit.HOUR) else null
    } catch (_: Throwable) {
        null
    }

    private fun administrative(line: String): Boolean {
        val flat = folded(line)
        return flat.startsWithAny(SKIP_PREFIXES) || SKIP_CONTAINS.any(flat::contains)
    }

    private fun validAnalyte(name: String): Boolean {
        val flat = folded(name)
        if (name.isBlank() || name.length > 100 || name.none(Char::isLetter)) return false
        if (flat.startsWithAny(SKIP_PREFIXES) || flat.startsWithAny(NON_ANALYTE_PREFIXES)) return false
        if (SKIP_CONTAINS.any(flat::contains) || NUMBER_UNIT_AT_END.containsMatchIn(name)) return false
        if (Regex("^$NUMBER\\s*(?:a|–|—)").containsMatchIn(name)) return false
        return true
    }

    private fun folded(value: String): String = foldedText(value)

    private fun String.startsWithAny(prefixes: List<String>): Boolean = prefixes.any(::startsWith)
}
