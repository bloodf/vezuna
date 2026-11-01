package app.vezuna.domain

/** A lone "d.ddd" token can be a pt-BR thousands group (1.020 = 1020) or a decimal (1.020 = 1.02); the reviewer must check it. */
object NumberAmbiguity {
    const val LOW_CONFIDENCE = 0.4f
    private val SINGLE_GROUP = Regex("[+-]?[1-9]\\d{0,2}\\.\\d{3}")
    private val COUNT_UNIT = Regex("(?:10\\d?)?/(?:mm3|µl|ul|mcl|l)")
    private val SCALED_COUNT_UNIT = Regex("10\\d?/(?:mm3|µl|ul|mcl|l)")

    fun isAmbiguousThousands(token: String): Boolean = SINGLE_GROUP.matches(token.trim())

    /** Ambiguous only when the unit leaves both readings possible: a per-volume count unit always means thousands. */
    fun isAmbiguousThousands(token: String, unit: String): Boolean =
        isAmbiguousThousands(token) && !isCountUnit(unit)

    /** "/mm3", "/µL", "10^3/µL", "10³/mm³" and spelling variants. */
    fun isCountUnit(unit: String): Boolean = COUNT_UNIT.matches(compact(unit))

    /** "10^3/µL"-style units already carry the thousands factor, so "7.500" there is a decimal 7.5, not 7500. */
    fun isScaledCountUnit(unit: String): Boolean = SCALED_COUNT_UNIT.matches(compact(unit))

    private fun compact(unit: String): String =
        unit.lowercase().replace(" ", "").replace('μ', 'µ').replace("³", "3").replace("⁶", "6").replace("^", "")
}
