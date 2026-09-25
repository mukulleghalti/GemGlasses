package com.geno.veyra.translate

/**
 * A language the translator can listen to or speak.
 *
 * @param code BCP-47 tag used for the speech config (e.g. "hi").
 */
data class TranslateLanguage(
    val code: String,
    val displayName: String,
) {
    companion object {

        /** Let Gemini detect whatever language it hears. */
        val AUTO =
            TranslateLanguage(
                "auto",
                "Auto detect",
            )

        val ALL: List<TranslateLanguage> =
            listOf(
                TranslateLanguage("en", "English"),
                TranslateLanguage("hi", "Hindi"),
                TranslateLanguage("es", "Spanish"),
                TranslateLanguage("fr", "French"),
                TranslateLanguage("de", "German"),
                TranslateLanguage("it", "Italian"),
                TranslateLanguage("pt", "Portuguese"),
                TranslateLanguage("ru", "Russian"),
                TranslateLanguage("ja", "Japanese"),
                TranslateLanguage("ko", "Korean"),
                TranslateLanguage("zh", "Chinese"),
                TranslateLanguage("ar", "Arabic"),
                TranslateLanguage("bn", "Bengali"),
                TranslateLanguage("ta", "Tamil"),
                TranslateLanguage("te", "Telugu"),
                TranslateLanguage("mr", "Marathi"),
                TranslateLanguage("gu", "Gujarati"),
                TranslateLanguage("kn", "Kannada"),
                TranslateLanguage("ml", "Malayalam"),
                TranslateLanguage("pa", "Punjabi"),
                TranslateLanguage("ur", "Urdu"),
                TranslateLanguage("tr", "Turkish"),
                TranslateLanguage("nl", "Dutch"),
                TranslateLanguage("th", "Thai"),
                TranslateLanguage("vi", "Vietnamese"),
                TranslateLanguage("id", "Indonesian"),
            )

        fun fromCode(code: String): TranslateLanguage =
            if (code == AUTO.code) {
                AUTO
            } else {
                ALL.firstOrNull { it.code == code }
                    ?: TranslateLanguage("en", "English")
            }
    }
}
