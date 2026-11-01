package app.vezuna.localization

/** Languages Vezuna writes generated content in: AI answers and exports. */
enum class ContentLanguage(val tag: String, val nativeName: String) {
    EN("en", "English"),
    PT("pt", "Português"),
    ES("es", "Español"),
    ;

    companion object {
        /** Maps a BCP 47 / POSIX tag ("pt-BR", "es_MX") to a supported language; unknown tags fall back to English. */
        fun fromTag(tag: String?): ContentLanguage {
            val primary = tag.orEmpty().lowercase().replace('_', '-').substringBefore('-')
            return entries.firstOrNull { it.tag == primary } ?: EN
        }

        fun device(): ContentLanguage = fromTag(deviceLanguageTag())
    }
}
