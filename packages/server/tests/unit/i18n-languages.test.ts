// The hub's own words in every registered language (ADR 0028): the language a request asks for
// is matched against the registry, a key its catalogue lacks falls back along its chain to
// English, and the hub's inline Arabic/English sentences take the nearest of the two.
import { describe, expect, it } from 'vitest';
import { pickLanguage, pickUiLanguage, t } from '../../src/i18n/index.js';

describe('server languages', () => {
  it('matches Accept-Language to a registered language, English otherwise', () => {
    expect(pickUiLanguage('ar-SA,en;q=0.5')).toBe('ar');
    expect(pickUiLanguage('fr-FR,fr;q=0.9')).toBe('en');
    expect(pickUiLanguage('fr-FR,ar;q=0.4')).toBe('ar');
    expect(pickUiLanguage(undefined)).toBe('en');
  });

  it('keeps the two-language pick exactly as before', () => {
    expect(pickLanguage('ar,en;q=0.5')).toBe('ar');
    expect(pickLanguage('en;q=0.5, ar')).toBe('ar');
    expect(pickLanguage('fr')).toBe('en');
    expect(pickLanguage(undefined)).toBe('en');
  });

  it('never answers with a raw key for a language without the words', () => {
    expect(t('errors.not_found', 'ar')).toBe('العنصر المطلوب غير موجود.');
    expect(t('errors.not_found', 'fr')).toBe('The requested item does not exist.');
    expect(t('errors.not_found', 'en-XA')).toMatch(/^\[Ţĥéé ŕééǫûûéé/);
  });
});
