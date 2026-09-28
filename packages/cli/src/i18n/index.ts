// Every string the CLI shows lives in its catalogues; the languages come from the registry
// (locales/languages.json, ADR 0028). `pnpm i18n:check` holds Arabic and English to parity and
// reports every other language's coverage. Keys are dotted, placeholders are `{name}`; a key a
// language lacks falls back along its chain to English, never to the bare key.
import {
  UI_LANGUAGE_CODES,
  createTranslate,
  interpolate as fill,
  matchLanguage,
  type TranslationParams,
} from '@corehub/contracts';
import { CATALOGUES } from './catalogues.js';

/** A registered UI language code: `ar`, `en`, or any language added to the registry. */
export type Language = string;
export const LANGUAGES: readonly Language[] = UI_LANGUAGE_CODES;
export const DEFAULT_LANGUAGE: Language = 'en';

export type Params = TranslationParams;
export type Translator = (key: string, params?: Params) => string;

const translateIn = createTranslate((code) => CATALOGUES[code]);

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

function fromTag(tag: string | undefined): Language | undefined {
  if (!tag || /^(c|posix)$/i.test(tag.trim().split('.')[0] ?? '')) return undefined;
  return matchLanguage(tag, LANGUAGES);
}

/** `--lang`, then COREHUB_LANG, then LC_ALL / LC_MESSAGES / LANG, then English. */
export function resolveLanguage(explicit: string | undefined, env: NodeJS.ProcessEnv): Language {
  return (
    fromTag(explicit) ??
    fromTag(env.COREHUB_LANG) ??
    fromTag(env.LC_ALL) ??
    fromTag(env.LC_MESSAGES) ??
    fromTag(env.LANG) ??
    DEFAULT_LANGUAGE
  );
}
