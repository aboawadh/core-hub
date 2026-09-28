// Server-side strings: only what the server itself says to a human (error envelopes).
// The languages come from the registry (locales/languages.json, ADR 0028); `pnpm i18n:check`
// holds Arabic and English to parity and reports every other language's coverage. A key a
// language lacks falls back along its chain to English, never to the bare key.
import {
  UI_LANGUAGE_CODES,
  createTranslate,
  nearestLanguage,
  pickFromAcceptLanguage,
} from '@corehub/contracts';
import { CATALOGUES } from './catalogues.js';

/**
 * The two languages the hub writes its own sentences in outside the catalogue (notices, titles,
 * stored per-user `locale`, the contract's `Locale`). Every other UI language is served in the
 * nearest of these two for those; the catalogue's error messages follow the full language.
 */
export type Language = 'ar' | 'en';
export const LANGUAGES: readonly Language[] = ['ar', 'en'];
export const DEFAULT_LANGUAGE: Language = 'en';

/** A registered UI language code (`ar`, `en`, and any language added to the registry). */
export type UiLanguage = string;

const translate = createTranslate((code) => CATALOGUES[code]);

/** Translate a dotted key; falls back along the language's chain to English, then the key. */
export function t(key: string, language: UiLanguage = DEFAULT_LANGUAGE): string {
  return translate(language, key);
}

/** Pick ar or en from an Accept-Language header (highest q wins, order breaks ties). */
export function pickLanguage(acceptLanguage: string | undefined): Language {
  if (!acceptLanguage) return DEFAULT_LANGUAGE;
  let best: { language: Language; q: number; index: number } | undefined;
  acceptLanguage.split(',').forEach((entry, index) => {
    const [tag = '', ...params] = entry.trim().split(';');
    const qParam = params.find((p) => p.trim().startsWith('q='));
    const q = qParam ? Number.parseFloat(qParam.trim().slice(2)) : 1;
    const primary = tag.trim().toLowerCase().split('-')[0] as Language;
    if (!LANGUAGES.includes(primary) || Number.isNaN(q) || q <= 0) return;
    if (!best || q > best.q) best = { language: primary, q, index };
  });
  if (best) return best.language;
  // Neither Arabic nor English asked for: the nearest of the two to a registered language.
  const ui = pickFromAcceptLanguage(acceptLanguage, UI_LANGUAGE_CODES);
  return ui ? nearestLanguage(ui, LANGUAGES) : DEFAULT_LANGUAGE;
}

/** The registered UI language an Accept-Language header asks for; English when none is. */
export function pickUiLanguage(acceptLanguage: string | undefined): UiLanguage {
  return pickFromAcceptLanguage(acceptLanguage, UI_LANGUAGE_CODES) ?? DEFAULT_LANGUAGE;
}
