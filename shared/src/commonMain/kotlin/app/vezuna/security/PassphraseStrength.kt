package app.vezuna.security

enum class PassphraseStrengthLevel { TOO_SHORT, FAIR, STRONG }

/** Length is what matters against offline brute force; character variety only raises the hint. */
object PassphraseStrength {
    const val MIN_LENGTH = 12
    private const val STRONG_LENGTH = 16

    fun isAcceptable(passphrase: CharArray): Boolean = passphrase.size >= MIN_LENGTH && passphrase.any { !it.isWhitespace() }

    fun evaluate(passphrase: CharArray): PassphraseStrengthLevel {
        if (!isAcceptable(passphrase)) return PassphraseStrengthLevel.TOO_SHORT
        val classes = listOf<(Char) -> Boolean>(Char::isLowerCase, Char::isUpperCase, Char::isDigit) .count { check -> passphrase.any(check) } +
            (if (passphrase.any { !it.isLetterOrDigit() }) 1 else 0)
        return if (passphrase.size >= STRONG_LENGTH || classes >= 3) PassphraseStrengthLevel.STRONG else PassphraseStrengthLevel.FAIR
    }
}
