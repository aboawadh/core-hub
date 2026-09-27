package hub.core.android

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * Latin digits (123) in both languages, also in the Arabic UI (owner, 2026-09-26, DECISIONS §113):
 * numbers, durations, sizes, dates, counts and percentages keep their Arabic words, plural forms
 * and RTL, but never show Arabic-Indic digits (٠١٢…). Done once, on the locale: every
 * `stringResource(…, n)`, `pluralStringResource`, `String.format`, `Formatter.formatShortFileSize`
 * and `DateUtils` call reads its digits from the Unicode `nu-latn` keyword set here.
 */
object Digits {
    /** [locale] with the Latin numbering system; its language, region and script stay. */
    fun latin(locale: Locale): Locale =
        runCatching { Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build() }.getOrDefault(locale)

    /** The locales the app formats in: the in-app language when one is chosen, else the phone's list. */
    fun locales(language: AppLanguage?, system: LocaleList): LocaleList {
        val chosen = language?.let { listOf(Locale.forLanguageTag(it.tag)) } ?: List(system.size()) { system[it] }
        return LocaleList(*chosen.ifEmpty { listOf(Locale.getDefault()) }.map(::latin).toTypedArray())
    }

    /**
     * The phone's own languages, whatever the app chose: the system's configuration, which
     * [Locale.setDefault] does not touch (unlike [LocaleList.getDefault], whose first entry follows it).
     */
    fun phoneLocales(): LocaleList =
        runCatching { Resources.getSystem().configuration.locales }.getOrNull()?.takeIf { !it.isEmpty } ?: LocaleList.getDefault()

    /**
     * The process default becomes the app's language with Latin digits, so every date, month name
     * and number formatted with [Locale.getDefault] (`localTime`, the pages' `d MMM yyyy`) reads in
     * the app's language — Arabic months in an Arabic app on an English phone, and the reverse.
     */
    fun useAppLocale(language: AppLanguage?, system: LocaleList = phoneLocales()): Locale =
        locales(language, system)[0].also(Locale::setDefault)

    /**
     * [base] configured for [language] (null follows the phone) with Latin digits; the process
     * default becomes the same locale, so `"%d".format(n)`, dates and month names agree with the
     * resources.
     */
    fun wrap(base: Context, language: AppLanguage?): Context {
        val config = Configuration(base.resources.configuration)
        val locales = locales(language, config.locales)
        Locale.setDefault(locales[0])
        config.setLocales(locales)
        config.setLayoutDirection(locales[0])
        return base.createConfigurationContext(config)
    }
}
