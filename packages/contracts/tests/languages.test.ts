// The language registry and the rules every package applies to it (ADR 0028).
import { describe, expect, it } from 'vitest';
import {
  PSEUDO_LOCALES,
  REQUIRED_LANGUAGE_CODES,
  UI_LANGUAGE_CODES,
  createTranslate,
  directionOfLanguage,
  fallbackChain,
  intlLocaleOf,
  isUiLanguage,
  keyTag,
  matchLanguage,
  nearestLanguage,
  pickFromAcceptLanguage,
  pluralCategoriesOf,
  pluralCategory,
  pseudoize,
  readKeyTags,
} from '../src/index.js';

describe('the registry', () => {
  it('holds Arabic and English, both required, and test-only pseudo-locales', () => {
    expect(UI_LANGUAGE_CODES.slice(0, 2)).toEqual(['ar', 'en']);
    expect([...REQUIRED_LANGUAGE_CODES].sort()).toEqual(['ar', 'en']);
    expect(PSEUDO_LOCALES.map((each) => each.code)).toEqual(
      expect.arrayContaining(['en-XA', 'ar-XB', 'zh-XC', 'th-XD']),
    );
    // A pseudo-locale is never a language a person can pick.
    for (const pseudo of PSEUDO_LOCALES) expect(isUiLanguage(pseudo.code)).toBe(false);
    expect(isUiLanguage('ar')).toBe(true);
    expect(isUiLanguage('AR')).toBe(false);
  });

  it('gives each language its direction', () => {
    expect(directionOfLanguage('ar')).toBe('rtl');
    expect(directionOfLanguage('en')).toBe('ltr');
    expect(directionOfLanguage('ar-XB')).toBe('rtl');
    expect(directionOfLanguage('xx')).toBe('ltr');
  });
});

describe('fallback', () => {
  it('walks a language, its fallbacks, then English', () => {
    expect(fallbackChain('ar')).toEqual(['ar', 'en']);
    expect(fallbackChain('en')).toEqual(['en']);
    expect(fallbackChain('ar-XB')).toEqual(['ar-XB', 'ar', 'en']);
    // Not registered: English.
    expect(fallbackChain('fr')).toEqual(['en']);
    expect(nearestLanguage('ar-XB', ['ar', 'en'])).toBe('ar');
    expect(nearestLanguage('fr', ['ar', 'en'])).toBe('en');
  });

  it('never shows a raw key while any language in the chain has the words', () => {
    const catalogues: Record<string, Record<string, unknown>> = {
      en: { a: { b: 'Hello {name}' }, files: { one: 'One file', other: '{count} files' } },
      ar: { a: { b: 'أهلًا {name}', empty: '' }, files: { other: '{count} ملف' } },
    };
    const t = createTranslate((code) => catalogues[code]);
    expect(t('ar', 'a.b', { name: 'X' })).toBe('أهلًا X');
    // An empty string is not a translation: the next language speaks.
    catalogues.en!.a = { b: 'Hello {name}', empty: 'Nothing' };
    expect(t('ar', 'a.empty')).toBe('Nothing');
    // A plural form the language lacks falls to its own `other` before another language.
    expect(t('ar', 'files.few', { count: 3 })).toBe('3 ملف');
    expect(t('fr', 'a.b', { name: 'Y' })).toBe('Hello Y');
    expect(t('en', 'no.such.key')).toBe('no.such.key');
  });
});

describe('matching a tag to a registered language', () => {
  it('uses the exact tag, the likely script, the bare language, then a sibling', () => {
    expect(matchLanguage('ar-SA')).toBe('ar');
    expect(matchLanguage('en_US.UTF-8')).toBe('en');
    expect(matchLanguage('fr-FR')).toBeUndefined();
    expect(matchLanguage('C')).toBeUndefined();
    const available = ['en', 'zh-Hans', 'zh-Hant', 'pt-BR'];
    expect(matchLanguage('zh-TW', available)).toBe('zh-Hant');
    expect(matchLanguage('zh-CN', available)).toBe('zh-Hans');
    expect(matchLanguage('zh', available)).toBe('zh-Hans');
    expect(matchLanguage('pt-PT', available)).toBe('pt-BR');
  });

  it('reads an Accept-Language header by quality, then order', () => {
    expect(pickFromAcceptLanguage('fr-FR,fr;q=0.9,ar;q=0.8,en;q=0.7')).toBe('ar');
    expect(pickFromAcceptLanguage('en;q=0.5, ar')).toBe('ar');
    expect(pickFromAcceptLanguage('en, ar')).toBe('en');
    expect(pickFromAcceptLanguage('fr')).toBeUndefined();
    expect(pickFromAcceptLanguage(undefined)).toBeUndefined();
  });
});

describe('numbers and plurals', () => {
  it('keeps Latin digits in every language (DECISIONS §113)', () => {
    expect(intlLocaleOf('ar')).toBe('ar-u-nu-latn');
    expect(intlLocaleOf('en')).toBe('en');
    expect(intlLocaleOf('ar-XB')).toBe('ar-u-nu-latn');
    expect(new Intl.NumberFormat(intlLocaleOf('ar')).format(1234)).toMatch(/^1[,٬]?234$/);
  });

  it('knows each language’s plural forms (Arabic six, English two)', () => {
    expect(pluralCategoriesOf('ar')).toEqual(['zero', 'one', 'two', 'few', 'many', 'other']);
    expect(pluralCategoriesOf('en')).toEqual(['one', 'other']);
    expect(pluralCategoriesOf('zh')).toEqual(['other']);
    expect(pluralCategory('ar', 0)).toBe('zero');
    expect(pluralCategory('ar', 3)).toBe('few');
    expect(pluralCategory('ar', 11)).toBe('many');
    expect(pluralCategory('en', 1)).toBe('one');
    expect(pluralCategory('ar-XB', 2)).toBe('two');
  });
});

describe('pseudo-locales', () => {
  it('transform the words and keep placeholders', () => {
    const long = pseudoize('Settings saved for {name}', 'accented');
    expect(long).toMatch(/^\[.*\{name\}.*\]$/);
    expect(long.length).toBeGreaterThan('Settings saved for {name}'.length * 1.3);
    expect(pseudoize('غيّر {count} ملفات', 'long-rtl')).toContain('{count}');
    const cjk = pseudoize('New chat', 'cjk');
    expect(cjk).not.toMatch(/[A-Za-z ]/);
    expect(pseudoize('Tasks', 'tall')).toMatch(/[่-๋]/);
  });

  it('draw a pseudo-locale from its base language', () => {
    const t = createTranslate((code) => (code === 'en' ? { nav: { chat: 'Chat' } } : undefined));
    expect(t('en-XA', 'nav.chat')).toMatch(/^\[Çĥ/);
    expect(t('en-XK', 'nav.chat')).toMatch(/^Chat⁤/);
  });

  it('en-XK tags a string with its key in characters that take no room', () => {
    const tagged = `Chat${keyTag('nav.chat')} · Tasks${keyTag('tasks.title_ar')}`;
    expect(readKeyTags(tagged)).toEqual({
      keys: ['nav.chat', 'tasks.title_ar'],
      text: 'Chat · Tasks',
    });
    expect(keyTag('x')).toMatch(/^⁤(?:‌|‍|⁠|﻿)+$/);
  });
});
