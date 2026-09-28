// Every string the web client shows lives in its catalogues, one per registered language
// (locales/languages.json, ADR 0028). `pnpm i18n:check` holds Arabic and English to parity and
// reports every other language's coverage; tests/i18n.test.ts checks that every `t('key')`
// literal in src exists. Keys are dotted, placeholders are `{name}`. Arabic and English are in
// the bundle; any other language is fetched when chosen, and until then — and for every key it
// lacks — its words fall back along its chain to English, never to the bare key.
import {
  UI_LANGUAGES,
  UI_LANGUAGE_CODES,
  createTranslate,
  derived,
  directionOfLanguage,
  interpolate as fill,
  intlLocaleOf,
  isUiLanguage,
  matchLanguage,
  nearestLanguage,
  pluralCategory,
  pseudoLocaleInfo,
  type Catalogue,
  type LanguageInfo,
  type PluralCategory,
  type TranslationParams,
} from '@corehub/contracts';
import { BUNDLED, LOADERS } from './catalogues.js';

/** A registered UI language code: `ar`, `en`, or any language added to the registry. */
export type Language = string;
export const LANGUAGES: readonly Language[] = UI_LANGUAGE_CODES;
/** Every language a person can pick, with its own name, in registry order. */
export const LANGUAGE_CHOICES: readonly LanguageInfo[] = UI_LANGUAGES;
export const DEFAULT_LANGUAGE: Language = 'ar';

export type Params = TranslationParams;
export type Translator = (key: string, params?: Params) => string;

/** The catalogues loaded so far: Arabic and English from the start, others once chosen. */
export const catalogues: Record<string, Catalogue> = { ...BUNDLED };

const translateIn = createTranslate((code) => catalogues[code]);

export function interpolate(text: string, params: Params = {}): string {
  return fill(text, params);
}

/** Translate a dotted key; falls back along the language's chain to English, then the key. */
export function translate(language: Language, key: string, params?: Params): string {
  return translateIn(language, key, params);
}

export function createTranslator(language: Language): Translator {
  return (key, params) => translate(language, key, params);
}

export function isLanguage(value: unknown): value is Language {
  return isUiLanguage(value);
}

/** Whether a language's own catalogue is here (Arabic and English always are). */
export function isLoaded(language: Language): boolean {
  return language in catalogues || !(language in LOADERS);
}

/** Fetches a language's catalogue once; a failure leaves it on its fallback chain. */
export async function loadLanguage(language: Language): Promise<void> {
  if (isLoaded(language)) return;
  const loader = LOADERS[language];
  if (!loader) return;
  catalogues[language] = await loader();
}

/**
 * The locale every number, date, time, size and percentage is formatted in: Latin digits (123)
 * in every language, also in the Arabic UI — Arabic words, plural forms and RTL stay (owner,
 * 2026-09-26, DECISIONS §113). Never pass the bare UI language to `Intl` or `toLocale*String`:
 * some engines give `ar` Arabic-Indic digits (tests/latin-digits.test.ts guards the call sites).
 */
export function intlLocale(language: string): string {
  return intlLocaleOf(language);
}

/** UI direction follows the UI language; content direction is decided per string (dir="auto"). */
export function directionOf(language: Language): 'rtl' | 'ltr' {
  return directionOfLanguage(language);
}

/** The CLDR plural category of `count` in the language: a key's last segment (`….few`). */
export function pluralOf(language: Language, count: number): PluralCategory {
  return pluralCategory(language, count);
}

/**
 * The contract's `Locale` (`ar` | `en`) nearest to a UI language — what the hub stores for a
 * person and writes its own notices in. Any other language is still the one the UI shows.
 */
export function serverLocale(language: Language): 'ar' | 'en' {
  return nearestLanguage(language, ['ar', 'en'] as const);
}

export function browserLanguage(
  navigatorLanguages: readonly string[] = navigator.languages,
): Language {
  for (const tag of navigatorLanguages) {
    const found = matchLanguage(tag, LANGUAGES);
    if (found) return found;
  }
  return DEFAULT_LANGUAGE;
}

/**
 * Tests only: a pseudo-locale (`en-XA`, `ar-XB`, `zh-XC`, `th-XD`) set in this browser's storage
 * under `<prefix>pseudo-locale` replaces the UI language, so a test can see every screen with
 * longer, wider and taller words. No picker ever lists one (ADR 0028).
 */
export const PSEUDO_STORAGE_KEY = `${derived.storagePrefix}pseudo-locale`;

export function pseudoLocale(
  storage: Pick<Storage, 'getItem'> | null = safeStorage(),
): string | null {
  try {
    const value = storage?.getItem(PSEUDO_STORAGE_KEY) ?? null;
    return pseudoLocaleInfo(value)?.code ?? null;
  } catch {
    return null;
  }
}

function safeStorage(): Pick<Storage, 'getItem'> | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

/** The language the UI is drawn in: the chosen one, or a test's pseudo-locale. */
export function shownLanguage(chosen: Language): Language {
  return pseudoLocale() ?? chosen;
}
