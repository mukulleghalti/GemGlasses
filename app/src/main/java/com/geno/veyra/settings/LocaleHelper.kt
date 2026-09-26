package com.geno.veyra.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the user's chosen app language by wrapping a [Context] with an
 * updated [Configuration] locale.
 *
 * A `null` or blank language tag means "follow the system language", in
 * which case the base context is returned unchanged.
 */
object LocaleHelper {

    fun wrapForLocale(base: Context, languageTag: String?): Context {
        if (languageTag.isNullOrBlank()) return base
        val locale = Locale.forLanguageTag(languageTag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Display name of a language tag in its own language, e.g. "Español". */
    fun displayName(languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        return locale.getDisplayLanguage(locale)
            .replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(locale) else it.toString()
            }
    }
}
