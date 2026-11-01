package app.vezuna.domain

/** A lone "d.ddd" token can be a pt-BR thousands group (1.020 = 1020) or a decimal (1.020 = 1.02); the reviewer must check it. */
object NumberAmbiguity {
    const val LOW_CONFIDENCE = 0.4f
    private val SINGLE_GROUP = Regex("[+-]?[1-9]\\d{0,2}\\.\\d{3}")

    fun isAmbiguousThousands(token: String): Boolean = SINGLE_GROUP.matches(token.trim())
}
