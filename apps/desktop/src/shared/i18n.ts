/**
 * The desktop app's own words: the first-run screen, the tray, the menu, the dialogs.
 * Everything inside the window after that is the web client's, in its own catalogue.
 * The languages come from the registry (locales/languages.json, ADR 0028); `pnpm i18n:check`
 * holds Arabic and English to the same keys and placeholders and reports every other
 * language's coverage. A key a language lacks falls back along its chain to English.
 */
import { createTranslate, directionOfLanguage } from '@corehub/contracts/languages';
import { CATALOGUES } from '../i18n/catalogues.js';
import type { Language } from './config.js';

const translateIn = createTranslate((code) => CATALOGUES[code]);

export function translate(
  language: Language,
  key: string,
  params: Record<string, string | number> = {},
): string {
  return translateIn(language, key, params);
}

export const direction = (language: Language): 'rtl' | 'ltr' => directionOfLanguage(language);

/**
 * A Latin value (an address, a version) inside an Arabic sentence, isolated so the bidi
 * algorithm does not reorder the sentence around it (FSI … PDI).
 */
export const isolate = (value: string): string => `⁨${value}⁩`;
