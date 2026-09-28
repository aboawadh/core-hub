// GENERATED from locales/languages.json by scripts/i18n/generate.mjs (ADR 0028). Do not edit:
// `pnpm i18n:new <code>` adds a language here and on every other platform.
// Arabic and English are in the bundle; every other language is fetched when chosen.
import ar from './ar.json' with { type: 'json' };
import en from './en.json' with { type: 'json' };

export const BUNDLED: Readonly<Record<string, Record<string, unknown>>> = {
  ar,
  en,
};

export const LOADERS: Readonly<Record<string, () => Promise<Record<string, unknown>>>> = {};
