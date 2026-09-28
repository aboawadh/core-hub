// The web client's languages (ADR 0028): the registry's list, the two-language switch that looks
// exactly as it did, a test-only pseudo-locale nobody can pick, the hub's `ar`/`en` locale for
// any UI language, and the full words of a label cut with an ellipsis on hover.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  LANGUAGES,
  PSEUDO_STORAGE_KEY,
  browserLanguage,
  catalogues,
  directionOf,
  isLanguage,
  isLoaded,
  loadLanguage,
  pluralOf,
  pseudoLocale,
  serverLocale,
  shownLanguage,
  translate,
} from '../src/i18n/index.js';
import { LanguageSwitch, languageName } from '../src/i18n/LanguageSwitch.js';
import { I18nProvider } from '../src/i18n/context.js';
import { titleIfTruncated } from '../src/i18n/truncation.js';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

const storage = (value: string | null) => ({ getItem: () => value });

describe('the registry in the web client', () => {
  it('lists Arabic and English, in their own names, and nothing test-only', () => {
    expect(LANGUAGES).toEqual(['ar', 'en']);
    expect(languageName('ar')).toBe('العربية');
    expect(languageName('en')).toBe('English');
    expect(isLanguage('en-XA')).toBe(false);
    expect(Object.keys(catalogues)).toEqual(['ar', 'en']);
    expect(isLoaded('ar')).toBe(true);
  });

  it('matches the browser’s languages and falls back to Arabic', () => {
    expect(browserLanguage(['fr-FR', 'en-US'])).toBe('en');
    expect(browserLanguage(['ar-EG'])).toBe('ar');
    expect(browserLanguage(['fr'])).toBe('ar');
  });

  it('stores the nearest of the hub’s two locales for any UI language', () => {
    expect(serverLocale('ar')).toBe('ar');
    expect(serverLocale('en')).toBe('en');
    expect(serverLocale('ar-XB')).toBe('ar');
    expect(serverLocale('fr')).toBe('en');
  });

  it('plural forms follow each language', () => {
    expect(pluralOf('ar', 2)).toBe('two');
    expect(pluralOf('en', 2)).toBe('other');
    expect(translate('ar', 'changes.summary.two')).toBe('غيّر ملفين');
  });

  it('loads nothing for a language that is already here', async () => {
    await loadLanguage('ar');
    expect(Object.keys(catalogues)).toEqual(['ar', 'en']);
  });
});

describe('pseudo-locales (tests only)', () => {
  it('are read from storage, and only real pseudo-locale codes count', () => {
    expect(pseudoLocale(storage('en-XA'))).toBe('en-XA');
    expect(pseudoLocale(storage('ar'))).toBeNull();
    expect(pseudoLocale(storage(null))).toBeNull();
    localStorage.setItem(PSEUDO_STORAGE_KEY, 'ar-XB');
    expect(shownLanguage('en')).toBe('ar-XB');
    expect(directionOf('ar-XB')).toBe('rtl');
    localStorage.removeItem(PSEUDO_STORAGE_KEY);
    expect(shownLanguage('en')).toBe('en');
  });

  it('draw every string from the base language, transformed', () => {
    expect(translate('en-XA', 'nav.tasks')).toMatch(/^\[Ţáášķš ẋ\]$/);
    expect(translate('ar-XB', 'nav.tasks')).toMatch(/^«.*»$/);
  });
});

describe('the language switch', () => {
  it('with two languages is the one-press switch it always was', () => {
    const choose = vi.fn();
    render(
      <I18nProvider language="ar">
        <LanguageSwitch language="ar" shows="other" label="اللغة" onChoose={choose} />
      </I18nProvider>,
    );
    const button = screen.getByRole('button', { name: 'اللغة' });
    expect(button.textContent).toBe('English');
    fireEvent.click(button);
    expect(choose).toHaveBeenCalledWith('en');
  });

  it('can name the current language instead', () => {
    render(
      <I18nProvider language="en">
        <LanguageSwitch language="en" shows="current" label="Language" onChoose={() => {}} />
      </I18nProvider>,
    );
    expect(screen.getByRole('button', { name: 'Language' }).textContent).toBe('English');
  });
});

describe('a label cut with an ellipsis', () => {
  const cut = (text: string, scroll: number, client: number) => {
    const element = document.createElement('span');
    element.textContent = text;
    element.style.textOverflow = 'ellipsis';
    Object.defineProperty(element, 'scrollWidth', { value: scroll, configurable: true });
    Object.defineProperty(element, 'clientWidth', { value: client, configurable: true });
    document.body.append(element);
    return element;
  };

  it('gets its full words as a title while, and only while, it is cut', () => {
    const element = cut('A very long label', 200, 80);
    titleIfTruncated(element);
    expect(element.title).toBe('A very long label');
    Object.defineProperty(element, 'scrollWidth', { value: 80 });
    titleIfTruncated(element);
    expect(element.hasAttribute('title')).toBe(false);
  });

  it('leaves a label that fits, and one that names itself, alone', () => {
    const fits = cut('Fits', 40, 80);
    titleIfTruncated(fits);
    expect(fits.hasAttribute('title')).toBe(false);
    const own = cut('A very long label', 200, 80);
    own.title = 'Its own words';
    titleIfTruncated(own);
    expect(own.title).toBe('Its own words');
  });
});
