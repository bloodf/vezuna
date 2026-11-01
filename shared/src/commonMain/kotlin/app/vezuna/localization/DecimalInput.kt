package app.vezuna.localization

/**
 * Parses a user-entered decimal without changing the canonical Double stored by the domain.
 * Both decimal separators are accepted; grouping separators and non-finite values are not.
 */
fun parseDecimalInput(raw: String): Double? {
    val normalized = raw.trim().replace(',', '.')
    val value = normalized.toDoubleOrNull() ?: return null
    return value.takeIf { it.isFinite() }
}
